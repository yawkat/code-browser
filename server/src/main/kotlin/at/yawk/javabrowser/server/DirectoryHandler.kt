package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.artifact.ArtifactNode
import at.yawk.javabrowser.server.view.Alternative
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.DirectoryView
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.Iterators
import org.jdbi.v3.core.Handle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectoryHandler @Inject constructor(
    private val artifactIndex: ArtifactIndex
) {
    companion object {
        val DEFAULT_DIRECTORY_REALM = Realm.SOURCE

        // contains / means either the entry is a directory or it's a collapsed directory entry
        private val ENTRY_COMPARATOR = Comparator.comparing<DirectoryEntry, Boolean> { !it.name.contains('/') }
            .thenComparing<String> { it.name }
    }

    private fun withTrailingSlash(parsedPath: ParsedPath.SourceFile) =
        if (parsedPath.sourceFilePath.endsWith("/")) parsedPath
        else ParsedPath.SourceFile(parsedPath.artifact, "${parsedPath.sourceFilePath}/")

    @VisibleForTesting
    internal fun getDirectoryEntries(
        conn: Handle,
        realm: Realm,
        parsedPath: ParsedPath.SourceFile
    ): List<DirectoryEntry>? {
        require(parsedPath.sourceFilePath.endsWith("/") || parsedPath.sourceFilePath.isEmpty())
        if (parsedPath.artifact.dbId == null) {
            return null
        }
        val entries = conn.createQuery(
            """with relative_files as (
    select substr(path, length(:prefix) + 1) as relative,
           hash
    from source_file
    where realm = :realm
      and artifact_id = :artifactId
      and path like ${escapeLike(":prefix")} || '%'
)
select common_prefix(relative) as common_prefix,
       min(relative)           as sample,
       -- xor of approximately uniform hashes is a good hash for a directory
       xor(hash)               as hash
from relative_files
-- group by next directory level
group by coalesce(substr(relative, 0, nullif(position('/' in relative), 0) + 1), relative)
order by common_prefix
        """
        )
            .bind("realm", realm.id)
            .bind("prefix", parsedPath.sourceFilePath)
            .bind("artifactId", parsedPath.artifact.dbId)
            .map { r, _, _ ->
                val commonPrefix = r.getString("common_prefix")
                val isDirectory = r.getString("sample") != commonPrefix
                val name =
                    if (isDirectory) commonPrefix.substring(0, commonPrefix.lastIndexOf('/') + 1)
                    else commonPrefix
                DirectoryEntry(
                    name = name,
                    realm = realm,
                    isDirectory = isDirectory,
                    fullPath = ParsedPath.SourceFile(parsedPath.artifact, parsedPath.sourceFilePath + name),
                    hash = r.getLong("hash")
                )
            }
            .list()
        if (entries.isEmpty()) {
            return null
        }
        entries.sortWith(ENTRY_COMPARATOR)
        return entries
    }

    private fun listArtifactsWithDirectory(conn: Handle, parsedPath: ParsedPath.SourceFile): List<ArtifactNode> {
        require(parsedPath.sourceFilePath.endsWith("/"))
        return conn.createQuery(
            """
select distinct artifact_id from source_file
where source_file.path like ${escapeLike("?")} || '%'
        """
        )
            .bind(0, parsedPath.sourceFilePath)
            .map { r, _, _ ->
                artifactIndex.allArtifactsByDbId[r.getLong(1)]
            }
            .list()
            .filterNotNull()
    }

    internal fun getDirectoryEntries(
        conn: Handle,
        realm: Realm,
        parsedPath: ParsedPath.SourceFile,
        diffWith: ParsedPath.SourceFile?
    ): List<DirectoryView.DirectoryEntry> {
        val newItems = getDirectoryEntries(conn, realm, parsedPath)
            ?: throw HttpException(404, "Not found")
        if (diffWith != null) {
            val oldItems = getDirectoryEntries(conn, realm, diffWith)
                ?: throw HttpException(404, "Diff path not found")
            return formatDiff(newItems, oldItems)
        } else {
            return newItems.map { it.formatNormal() }
        }
    }

    internal fun directoryView(
        conn: Handle,
        realmOverride: Realm?,
        parsedPath_: ParsedPath.SourceFile,
        diffWith_: ParsedPath.SourceFile?
    ): DirectoryView {
        val parsedPath = withTrailingSlash(parsedPath_)
        val diffWith = diffWith_?.let { withTrailingSlash(it) }
        val realm = realmOverride ?: DEFAULT_DIRECTORY_REALM
        val items = getDirectoryEntries(conn, realm, parsedPath, diffWith)
        return DirectoryView(
            realm,
            entries = items,
            newPath = parsedPath,
            oldPath = diffWith,
            alternatives = listArtifactsWithDirectory(conn, parsedPath).map {
                Alternative.fromPathPair(realm, parsedPath, ParsedPath.SourceFile(it, parsedPath.sourceFilePath))
            }
        )
    }

    private fun formatDiff(
        new: List<DirectoryEntry>,
        old: List<DirectoryEntry>
    ): List<DirectoryView.DirectoryEntry> {
        val newItr = Iterators.peekingIterator(new.iterator())
        val oldItr = Iterators.peekingIterator(old.iterator())
        val out = ArrayList<DirectoryView.DirectoryEntry>()
        while (newItr.hasNext() && oldItr.hasNext()) {
            val peekNew = newItr.peek()
            val peekOld = oldItr.peek()
            val realm = peekNew.realm
            require(peekOld.realm == realm)
            // this checks name & isDirectory flag
            val cmp = ENTRY_COMPARATOR.compare(peekNew, peekOld)
            when {
                cmp == 0 -> { // new == old
                    require(peekNew.isDirectory == peekOld.isDirectory)
                    out.add(
                        DirectoryView.DirectoryEntry(
                            name = peekNew.name,
                            fullSourceFilePath = Locations.diffPath(
                                sourceFileNew = Locations.toPath(peekNew.fullPath),
                                sourceFileOld = Locations.toPath(peekOld.fullPath)
                            ) +
                                    if (realm == (peekNew.fullPath.realmFromExtension ?: DEFAULT_DIRECTORY_REALM))
                                        ""
                                    else
                                        "&realm=$realm",

                            diffResult =
                            if (peekNew.hash == peekOld.hash)
                                DeclarationNode.DiffResult.UNCHANGED
                            else
                                DeclarationNode.DiffResult.CHANGED_INTERNALLY,
                            isDirectory = peekNew.isDirectory
                        )
                    )
                    newItr.next()
                    oldItr.next()
                }
                cmp < 0 -> // new < old
                    out.add(newItr.next().formatNormal(diffResult = DeclarationNode.DiffResult.INSERTION))
                else -> // new > old
                    out.add(oldItr.next().formatNormal(diffResult = DeclarationNode.DiffResult.DELETION))
            }
        }
        // finish up iterators
        while (newItr.hasNext()) {
            out.add(newItr.next().formatNormal(diffResult = DeclarationNode.DiffResult.INSERTION))
        }
        while (oldItr.hasNext()) {
            out.add(oldItr.next().formatNormal(diffResult = DeclarationNode.DiffResult.DELETION))
        }
        return out
    }

    @VisibleForTesting
    internal data class DirectoryEntry(
        val realm: Realm,
        val name: String,
        val hash: Long,
        val fullPath: ParsedPath.SourceFile,
        val isDirectory: Boolean
    )

    private fun DirectoryEntry.formatNormal(diffResult: DeclarationNode.DiffResult? = null) =
        DirectoryView.DirectoryEntry(
            name = name,
            diffResult = diffResult,
            fullSourceFilePath =
            Locations.toPath(fullPath) +
                    if (realm == (fullPath.realmFromExtension ?: DEFAULT_DIRECTORY_REALM))
                        ""
                    else
                        "?realm=$realm",
            isDirectory = isDirectory
        )
}