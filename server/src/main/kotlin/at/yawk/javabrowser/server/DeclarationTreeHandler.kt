package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.artifact.ArtifactNode
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.DeclarationNodeView
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.eclipse.collections.impl.EmptyIterator
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URLEncoder
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val FETCH_NODE_LIMIT = 1000

@Singleton
class DeclarationTreeHandler @Inject constructor(
        private val dbi: DBI,
        private val ftl: Ftl,
        private val artifactIndex: ArtifactIndex
) : HttpHandler {
    companion object {
        const val PATTERN = "/declarationTree"
    }

    private data class SourceFileResult(
            val annotations: Sequence<PositionedAnnotation>,
            val path: String
    )

    private fun getSourceFile(conn: Handle, realm: Realm, artifactId: String, binding: String): SourceFileResult? {
        val result = conn.select(
                """
select binding.description, source_file.path, source_file.annotations 
from binding 
natural left join artifact
natural left join source_file
where binding.realm = ? and artifact.string_id = ? and binding.binding = ?""",
                realm.id,
                artifactId,
                binding)
        if (result.isEmpty()) {
            return null
        } else {
            val description = cborMapper.readValue(
                    result.single()["description"] as ByteArray,
                    BindingDecl.Description::class.java)
            if (description is BindingDecl.Description.Package) {
                return null // list as package instead
            } else {
                return SourceFileResult(
                        annotations = ServerSourceFile.lazyParseAnnotations(result.single()["annotations"] as ByteArray),
                        path = "/$artifactId/${result.single()["path"]}"
                )
            }
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val realmName = exchange.queryParameters["realm"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass realm")
        val realm = Realm.parse(realmName) ?: throw HttpException(404, "Realm not found")

        val artifactId = exchange.queryParameters["artifactId"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass artifact ID")
        val binding = exchange.queryParameters["binding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass binding")
        val diffArtifactId = exchange.queryParameters["diff"]?.peekFirst()

        dbi.inTransaction { conn: Handle, _ ->
            ftl.render(exchange, handleRequest(
                    conn,
                    realm,
                    artifactId = artifactId,
                    binding = binding,
                    diffArtifactId = diffArtifactId
            ))
        }
    }

    @VisibleForTesting
    internal fun handleRequest(conn: Handle,
                               realm: Realm,
                               artifactId: String,
                               binding: String,
                               diffArtifactId: String?): DeclarationNodeView {
        val new = getSourceFile(conn, realm, artifactId, binding)
        val oldFile = diffArtifactId?.let { getSourceFile(conn, realm, it, binding) }

        if (new != null || oldFile != null) {
            val tree: Iterator<DeclarationNode>

            if (diffArtifactId == null) {
                tree = sourceDeclarationTree(realm, artifactId, new!!.annotations, fullSourceFilePath = new.path)
            } else {
                val fullPath =
                        when {
                            oldFile == null -> new!!.path
                            // TODO: if new is null, links to the old file will be broken.
                            new == null -> oldFile.path
                            else -> "${new.path}?diff=${URLEncoder.encode(oldFile.path, "UTF-8")}"
                        }
                tree = DeclarationTreeDiff.diffUnordered(
                        new =
                        if (new == null) Collections.emptyIterator()
                        else sourceDeclarationTree(realm, artifactId, new.annotations, fullSourceFilePath = fullPath),
                        old =
                        if (oldFile == null) Collections.emptyIterator()
                        else sourceDeclarationTree(realm,
                                artifactId,
                                oldFile.annotations,
                                fullSourceFilePath = fullPath)
                )
            }
            for (topLevelType in tree) {
                if (topLevelType.binding == binding) {
                    return DeclarationNodeView(topLevelType.children!!, binding, diffArtifactId)
                }
            }
            throw HttpException(404, "Binding not found - is it a top-level declaration?")
        } else {
            // the binding must be a package
            var iterator = packageTree(conn, realm, artifactId, binding)
            if (diffArtifactId != null) {
                val old = packageTree(conn, realm, diffArtifactId, binding)
                iterator = DeclarationTreeDiff.diffOrdered(old, iterator, DatabasePackageTreeComparator)
            }
            if (!iterator.hasNext()) throw HttpException(404, "Binding not found")
            return DeclarationNodeView(iterator, binding, diffArtifactId)
        }
    }

    private data class PrefixIterator(
            /* work around kotlinc bug: Passing a captured variable to the super constructor is broken */
            val types: PeekingIterator<DeclarationNode>,
            val prefix: String) : TreeIterator<DeclarationNode, DeclarationNode>(types) {
        override fun mapOneItem(): DeclarationNode {
            var item = flatDelegate.next()
            if (item.description is BindingDecl.Description.Package) {
                // add children to package
                val subIterator = copy(prefix = item.binding + '.')
                registerSubIterator(subIterator)
                if (subIterator.hasNext()) {
                    item = item.copy(children = subIterator)
                }
            }
            return item
        }

        override fun returnToParent(item: DeclarationNode): Boolean {
            return !item.binding.startsWith(prefix)
        }
    }

    /**
     * Get a set of bindings for the given parents.
     *
     * @return `null` iff there are more than [limit] parents.
     */
    private fun getBindings(
            conn: Handle,
            realm: Realm,
            artifact: ArtifactNode,
            parents: LongArray,
            limit: Long?
    ): List<DeclarationNode>? {
        require(parents.isNotEmpty())
        var query = conn.createQuery("""
select binding.binding_id, binding.binding, binding.description, binding.modifiers, source_file.path
from binding
natural left join source_file
where binding.realm = ?
and binding.artifact_id = ?
and binding.parent = any(?)
${if (limit != null) "limit ? + 1" else ""}
""")
                .bind(0, realm.id)
                .bind(1, artifact.dbId)
                .bind(2, parents)

        if (limit != null) query = query.bind(3, limit)
        val nodes = query
                .map { _, rs, _ ->
                    val bindingId = BindingId(rs.getLong(1))
                    val binding = rs.getString(2)
                    val description = rs.getBytes(3)
                    val modifiers = rs.getInt(4)
                    val sourceFile: String? = rs.getString(5)
                    DeclarationNode(
                            realm = realm,
                            artifactId = artifact.stringId,
                            bindingId = bindingId,
                            binding = binding,
                            fullSourceFilePath = sourceFile?.let { "/${artifact.stringId}/$it" },
                            modifiers = modifiers,
                            description =
                            cborMapper.readValue(description, BindingDecl.Description::class.java),
                            children = null
                    )
                }
                .list()
        if (limit != null && nodes.size > limit) return null
        else return nodes
    }

    private fun getBindingId(conn: Handle, realm: Realm, artifactId: Long, binding: String): BindingId? {
        val result = conn.select("select binding_id from binding where realm = ? and artifact_id = ? and binding = ?",
                realm.id, artifactId, binding)
        val id = (result.singleOrNull() ?: return null)["binding_id"] as Number
        return BindingId(id.toLong())
    }

    fun packageTree(conn: Handle,
                    realm: Realm,
                    artifactStringId: String,
                    packageName: String? = null): Iterator<DeclarationNode> {
        val artifact = artifactIndex.allArtifactsByStringId[artifactStringId]
                ?: return EmptyIterator.getInstance()
        if (artifact.dbId == null) return EmptyIterator.getInstance()

        val allNodes = ArrayList<DeclarationNode>()
        val root =
                if (packageName == null) BindingId.TOP_LEVEL_PACKAGE
                else getBindingId(conn, realm, artifact.dbId, packageName) ?: return EmptyIterator.getInstance()
        var parents = longArrayOf(root.hash)
        while (allNodes.size < FETCH_NODE_LIMIT) {
            val bindings = getBindings(conn, realm, artifact, parents,
                    if (allNodes.isEmpty()) null else FETCH_NODE_LIMIT.toLong() - allNodes.size)
            // if we hit the limit, null is returned
            if (bindings.isNullOrEmpty()) break
            allNodes.addAll(bindings)
            parents = LongArray(bindings.size) { bindings[it].bindingId.hash }
        }
        allNodes.sortBy { it.binding }

        return PrefixIterator(Iterators.peekingIterator(allNodes.iterator()), "")
    }

    fun packageTreeDiff(conn: Handle,
                        realm: Realm,
                        artifactIdOld: String,
                        artifactIdNew: String,
                        packageName: String? = null): Iterator<DeclarationNode> {
        return DeclarationTreeDiff.diffOrdered(
                packageTree(conn, realm, artifactIdOld, packageName),
                packageTree(conn, realm, artifactIdNew, packageName),
                DatabasePackageTreeComparator
        )
    }

    // postgres does case insensitive comparison for ORDER BY on linux.
    object DatabasePackageTreeComparator : Comparator<DeclarationNode> {
        override fun compare(o1: DeclarationNode, o2: DeclarationNode) =
                String.CASE_INSENSITIVE_ORDER.compare(o1.binding, o2.binding)
    }

    private data class SourceDeclarationTreeItr(
            /* work around kotlinc bug: Passing a captured variable to the constructor is broken */
            val flat_capture: PeekingIterator<BindingDecl>,
            val fullSourceFilePath_capture: String?,
            val realm_capture: Realm,
            val artifactId_capture: String,

            val parent: BindingId?
    ) : TreeIterator<BindingDecl, DeclarationNode>(flat_capture) {
        override fun mapOneItem(): DeclarationNode {
            val item = flatDelegate.next()
            assert(!returnToParent(item)) // checked in hasNext
            val newItr = copy(parent = item.id)
            registerSubIterator(newItr)
            return DeclarationNode(
                    realm = realm_capture,
                    artifactId = artifactId_capture,
                    bindingId = item.id,
                    binding = item.binding,
                    description = item.description,
                    modifiers = item.modifiers,
                    fullSourceFilePath = fullSourceFilePath_capture,
                    children = newItr
            )
        }

        override fun returnToParent(item: BindingDecl): Boolean {
            // sanity check. The topmost iterator should never return to its non-existent parent
            if (parent == null && item.parent != null) throw AssertionError()
            return item.parent != parent
        }
    }

    fun sourceDeclarationTree(
            realm: Realm,
            artifactId: String,
            annotations: Sequence<PositionedAnnotation>,
            fullSourceFilePath: String? = null
    ): Iterator<DeclarationNode> {
        val flat = Iterators.peekingIterator(annotations.mapNotNull { it.annotation as? BindingDecl }.iterator())

        return SourceDeclarationTreeItr(flat, fullSourceFilePath, realm, artifactId, flat.peek().parent)
    }
}