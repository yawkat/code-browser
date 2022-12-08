package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.view.Alternative
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.IndexView
import at.yawk.javabrowser.server.view.LeafArtifactView
import at.yawk.javabrowser.server.view.SourceFileView
import at.yawk.javabrowser.server.view.View
import com.google.common.annotations.VisibleForTesting
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.HttpString
import io.undertow.util.StatusCodes
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.function.Function
import javax.inject.Inject

/**
 * @author yawkat
 */
class BaseHandler @Inject constructor(
        private val dbi: Jdbi,
        private val ftl: Ftl,
        private val bindingResolver: BindingResolver,
        private val artifactIndex: ArtifactIndex,
        private val declarationTreeHandler: DeclarationTreeHandler,
        private val siteStatisticsService: SiteStatisticsService,
        private val aliasIndex: AliasIndex,
        private val metadataCache: ArtifactMetadataCache,
        private val directoryHandler: DirectoryHandler
) : HttpHandler {

    private fun parsePath(rawPath: String): ParsedPath {
        val parsed = artifactIndex.parse(rawPath)
        when {
            parsed.remainingPath != null ->
                return ParsedPath.SourceFile(parsed.node, parsed.remainingPath)
            parsed.node.children.isEmpty() ->
                // artifact overview
                return ParsedPath.LeafArtifact(parsed.node)
            else ->
                return ParsedPath.Group(parsed.node)
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val path = parsePath(exchange.relativePath)

        dbi.inTransaction<Unit, Exception> { conn: Handle ->
            val view = when (path) {
                is ParsedPath.SourceFile -> sourceFile(exchange, conn, path)
                is ParsedPath.LeafArtifact -> leafArtifact(exchange, conn, path)
                is ParsedPath.Group -> IndexView(path, siteStatisticsService.statistics)
            }
            ftl.render(exchange, view)
        }
    }

    private fun leafArtifact(
        exchange: HttpServerExchange,
        conn: Handle,
        path: ParsedPath.LeafArtifact
    ): LeafArtifactView {
        val diffWith = exchange.queryParameters["diff"]?.peekFirst()?.let { parsePath(it) }
        if (diffWith !is ParsedPath.LeafArtifact?) {
            throw HttpException(StatusCodes.BAD_REQUEST, "Can't diff with that")
        }
        return leafArtifact(conn, path, diffWith)
    }

    @VisibleForTesting
    internal fun leafArtifact(
        conn: Handle,
        path: ParsedPath.LeafArtifact,
        diffWith: ParsedPath.LeafArtifact?
    ): LeafArtifactView {
        val topLevelPackages: Iterator<DeclarationNode>
        if (diffWith == null) {
            topLevelPackages = declarationTreeHandler.packageTree(conn, Realm.SOURCE, path.artifact.stringId)
        } else {
            topLevelPackages = declarationTreeHandler.packageTreeDiff(conn,
                    Realm.SOURCE,
                    diffWith.artifact.stringId,
                    path.artifact.stringId)
        }
        val alternatives = path.artifact.parent!!.children.values
                .sortedWith(Comparator.comparing(Function { it.stringId }, VersionComparator))
                .map {
                    Alternative.fromPathPair(null, path, ParsedPath.LeafArtifact(it))
                }
        return LeafArtifactView(
            path,
            diffWith,
            metadataCache.getArtifactMetadata(path.artifact),
            conn.attach(DependencyDao::class.java).getDependencies(path.artifact.dbId!!).map {
                buildDependencyInfo(it)
            },
            topLevelPackages,
            alternatives,
            Realm.values().associate {
                it.name to directoryHandler.getDirectoryEntries(
                    conn, it,
                    ParsedPath.SourceFile(path.artifact, ""),
                    diffWith?.let { ParsedPath.SourceFile(diffWith.artifact, "") }
                )
            }
        )
    }

    private fun buildDependencyInfo(it: String): LeafArtifactView.Dependency {
        if (artifactIndex.allArtifactsByStringId.containsKey(it)) {
            return LeafArtifactView.Dependency(it, "", null)
        }

        val matchingAlias = aliasIndex.findAliasedTo(it)?.let { artifactIndex.allArtifactsByDbId[it] }

        val parts = it.split("/")
        for (i in parts.size - 1 downTo 1) {
            val prefix = parts.subList(0, i).joinToString("/")

            val node = artifactIndex.allArtifactsByStringId[prefix]
            if (node != null) {
                return LeafArtifactView.Dependency(
                        "$prefix/",
                        parts.subList(i, parts.size).joinToString("/"),
                        matchingAlias?.stringId)
            }
        }
        return LeafArtifactView.Dependency(null, it, matchingAlias?.stringId)
    }

    private fun requestSourceFile(
        conn: Handle,
        realm: Realm,
        parsedPath: ParsedPath.SourceFile
    ): ServerSourceFile? {
        val result = conn.select(
            "select text, annotations from source_file where realm = ? and artifact_id = ? and path = ?",
            realm.id,
            parsedPath.artifact.dbId,
            parsedPath.sourceFilePath
        ).mapToMap().toList()
        if (result.isEmpty()) {
            return null
        }
        val sourceFile = ServerSourceFile(result.single()["text"] as ByteArray,
                result.single()["annotations"] as ByteArray)
        sourceFile.bakeAnnotations() // need two passes - one for the side bar, one for the actual code.
        return sourceFile
    }

    private fun sourceFile(exchange: HttpServerExchange,
                           conn: Handle,
                           parsedPath: ParsedPath.SourceFile): View {
        val diffWith = exchange.queryParameters["diff"]?.peekFirst()?.let { parsePath(it) }
        if (diffWith !is ParsedPath.SourceFile?) {
            throw HttpException(StatusCodes.BAD_REQUEST, "Can't diff with that")
        }
        val realmOverride = exchange.queryParameters["realm"]?.peekFirst()?.let { Realm.parse(it) }
        val view = sourceFile(conn, realmOverride, parsedPath, diffWith) ?:
        directoryHandler.directoryView(conn, realmOverride, parsedPath, diffWith)
        if (!exchange.isCrawler()) {
            // increment hit counter for this time bin
            val timestamp = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
            conn.createUpdate("insert into hits (timestamp, sourceFile, artifactId, hits) values (?, ?, ?, 1) on conflict (timestamp, sourceFile, artifactId) do update set hits = hits.hits + 1")
                .bind(0, timestamp.toLocalDateTime())
                .bind(1, parsedPath.sourceFilePath)
                .bind(2, parsedPath.artifact.stringId)
                .execute()
        }
        if (diffWith != null) {
            // do not index diff pages
            exchange.responseHeaders.put(HttpString("X-Robots-Tag"), "noindex")
        }
        return view
    }

    @VisibleForTesting
    internal fun sourceFile(
        conn: Handle,
        realmOverride: Realm?,
        parsedPath: ParsedPath.SourceFile,
        diffWith: ParsedPath.SourceFile?
    ): SourceFileView? {
        val parsedPathRealm = realmOverride ?: parsedPath.realmFromExtension ?: return null
        val sourceFile = requestSourceFile(conn, parsedPathRealm, parsedPath) ?: return null

        val artifactId = parsedPath.artifact.dbId
        val dependencies =
            if (artifactId == null) emptyList()
            else conn.attach(DependencyDao::class.java).getDependencies(artifactId)

        // potential source files that could match the given parsedPath, but may come from a different artifact.
        val alternatives = ArrayList<ParsedPath.SourceFile>()
        fun tryExactMatch(path: String) {
            conn.createQuery("select artifact_id, path from source_file where realm = ? and path = ?")
                    .bind(0, parsedPathRealm.id)
                    .bind(1, path)
                    .map { r, _, _ ->
                        ParsedPath.SourceFile(
                            artifactIndex.allArtifactsByDbId[r.getLong(1)]!!,
                            r.getString(2)
                        )
                    }
                    .forEach { alternatives.add(it) }
        }

        // first, try the path itself
        tryExactMatch(parsedPath.sourceFilePath)

        if (parsedPath.artifact.stringIdList[0] == "java") {
            // for java artifacts, also try with / without module

            if (parsedPath.artifact.stringIdList[1].toInt() < 9) {
                // java 9 introduced the module path at the start
                val modernJavaArtifacts = artifactIndex.leafArtifacts.filter {
                    it.stringIdList[0] == "java" && it.stringIdList[1].toInt() >= 9
                }.map { it.dbId!! }.toLongArray()
                // we do LIKE escaping on the db side
                conn.createQuery(
                    "select artifact_id, path from source_file where realm = ? and artifact_id = any(?) and path like '%/' || ${
                        escapeLike(
                            "?"
                        )
                    }"
                )
                        .bind(0, parsedPathRealm.id)
                        .bind(1, modernJavaArtifacts)
                        .bind(2, parsedPath.sourceFilePath)
                        .map { r, _, _ ->
                            ParsedPath.SourceFile(
                                artifactIndex.allArtifactsByDbId[r.getLong(1)]!!,
                                r.getString(2)
                            )
                        }
                        .forEach { alternatives.add(it) }
            } else {
                // try without module
                tryExactMatch(parsedPath.sourceFilePath.substring(parsedPath.sourceFilePath.indexOf('/') + 1))
            }
        }

        alternatives.sortWith(Comparator.comparing(Function { it.artifact.stringId }, VersionComparator))

        val declarations: Iterator<DeclarationNode>
        val oldInfo: SourceFileView.FileInfo?
        if (diffWith == null) {
            oldInfo = null
            declarations = declarationTreeHandler.sourceDeclarationTree(
                parsedPathRealm,
                parsedPath.artifact.stringId,
                sourceFile.annotations
            )
        } else {
            val diffRealm = realmOverride ?: diffWith.realmFromExtension
                // this can only happen if the normal source has a normal extension, but the diff file does not
                ?: throw HttpException(StatusCodes.BAD_REQUEST, "Diff source file not compatible")
            val oldSourceFile = requestSourceFile(conn, diffRealm, diffWith)
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Diff source file not found")
            @Suppress("USELESS_CAST")
            oldInfo = SourceFileView.FileInfo(
                realm = diffWith.realmFromExtension!!,
                sourceFile = oldSourceFile,
                classpath = listOf(diffWith.artifact.stringId) + conn.attach(DependencyDao::class.java).getDependencies(diffWith.artifact.dbId!!),
                sourceFilePath = diffWith
            )
            declarations = DeclarationTreeDiff.diffUnordered(
                declarationTreeHandler.sourceDeclarationTree(
                    diffRealm,
                    diffWith.artifact.stringId,
                    oldInfo.sourceFile.annotations
                ),
                declarationTreeHandler.sourceDeclarationTree(
                    parsedPathRealm,
                    parsedPath.artifact.stringId,
                    sourceFile.annotations
                )
            )
        }

        return SourceFileView(
            newInfo = SourceFileView.FileInfo(
                realm = parsedPathRealm,
                classpath = listOf(parsedPath.artifact.stringId) + dependencies,
                sourceFile = sourceFile,
                sourceFilePath = parsedPath
            ),
            oldInfo = oldInfo,
            alternatives = alternatives.map { Alternative.fromPathPair(parsedPathRealm, parsedPath, it) },
            artifactMetadata = metadataCache.getArtifactMetadata(parsedPath.artifact),
            declarations = declarations,
            bindingResolver = bindingResolver
        )
    }
}