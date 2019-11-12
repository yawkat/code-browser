package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.DeclarationNodeView
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URLEncoder
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
@Singleton
class DeclarationTreeHandler @Inject constructor(
        private val dbi: DBI,
        private val ftl: Ftl,
        private val objectMapper: ObjectMapper
) : HttpHandler {
    companion object {
        const val PATTERN = "/declarationTree"
    }

    private data class SourceFileResult(
            val annotations: Sequence<PositionedAnnotation>,
            val path: String
    )

    private fun getSourceFile(conn: Handle, artifactId: String, binding: String): SourceFileResult? {
        val result = conn.select(
                "select description, path, annotations from bindings " +
                        "left join sourceFiles on sourceFiles.artifactId = bindings.artifactId and sourceFiles.path = bindings.sourceFile " +
                        "where bindings.artifactId = ? and bindings.binding = ?",
                artifactId,
                binding)
        if (result.isEmpty()) {
            return null
        } else {
            val description = objectMapper.readValue(
                    result.single()["description"] as ByteArray,
                    BindingDecl.Description::class.java)
            if (description is BindingDecl.Description.Package) {
                return null // list as package instead
            } else {
                return SourceFileResult(
                        annotations = ServerSourceFile.lazyParseAnnotations(objectMapper, result.single()["annotations"] as ByteArray),
                        path = "/$artifactId/${result.single()["path"]}"
                )
            }
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val artifactId = exchange.queryParameters["artifactId"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass artifact ID")
        val binding = exchange.queryParameters["binding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass binding")
        val diffArtifactId = exchange.queryParameters["diff"]?.peekFirst()

        dbi.inTransaction { conn: Handle, _ ->
            val new = getSourceFile(conn, artifactId, binding)
            val oldFile = diffArtifactId?.let { getSourceFile(conn, it, binding) }

            if (new != null || oldFile != null) {
                val tree: Iterator<DeclarationNode>

                if (diffArtifactId == null) {
                    tree = sourceDeclarationTree(artifactId, new!!.annotations, fullSourceFilePath = new.path)
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
                            else sourceDeclarationTree(artifactId, new.annotations, fullSourceFilePath = fullPath),
                            old =
                            if (oldFile == null) Collections.emptyIterator()
                            else sourceDeclarationTree(artifactId, oldFile.annotations, fullSourceFilePath = fullPath)
                    )
                }
                for (topLevelType in tree) {
                    if (topLevelType.binding == binding) {
                        ftl.render(exchange, DeclarationNodeView(topLevelType.children!!, binding, diffArtifactId))
                        return@inTransaction
                    }
                }
                throw HttpException(404, "Binding not found - is it a top-level declaration?")
            } else {
                // the binding must be a package
                var iterator = packageTree(conn, artifactId, binding)
                if (diffArtifactId != null) {
                    val old = packageTree(conn, diffArtifactId, binding)
                    iterator = DeclarationTreeDiff.diffOrdered(old, iterator, DatabasePackageTreeComparator)
                }
                if (!iterator.hasNext()) throw HttpException(404, "Binding not found")
                ftl.render(exchange, DeclarationNodeView(iterator, binding, diffArtifactId))
            }
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

    fun packageTree(conn: Handle, artifactId: String, packageName: String? = null): Iterator<DeclarationNode> {
        val itemsByDepth = conn.createQuery("select depth, typeCount from type_count_by_depth_view where artifactId = ? and (package = ? or ?)")
                .bind(0, artifactId)
                .bind(1, packageName)
                .bind(2, packageName == null)
                .map { _, r, _ ->
                    r.getInt("depth") to r.getInt("typeCount")
                }
                .toMap()
        var expectedItemCount = 0
        var depth = 0
        do {
            expectedItemCount += itemsByDepth[depth] ?: 0
            depth++
        } while (expectedItemCount < 1000 && itemsByDepth.containsKey(depth))

        val types = Iterators.peekingIterator(conn.createQuery(
                """
                    select name, description, modifiers, sourceFile 
                    from (
                        select artifactId, binding as name, description, modifiers, sourceFile from bindings where parent is null 
                        union select artifactId, name, NULL as description, 0 as modifiers, NULL as sourceFile from packages_view where not exists(select 1 from bindings where bindings.artifactId = packages_view.artifactId and binding = packages_view.name)
                    ) sq where artifactId = ? and name like ? and count_dots(name) < ? order by name
                    """)
                .bind(0, artifactId)
                .bind(1, if (packageName == null) "%" else "$packageName.%")
                .bind(2, depth + (packageName?.count { it == '.' } ?: -1))
                .map { _, rs, _ ->
                    val binding = rs.getString(1)
                    val description: ByteArray? = rs.getBytes(2)
                    val modifiers = rs.getInt(3)
                    val sourceFile: String? = rs.getString(4)
                    DeclarationNode(
                            artifactId = artifactId,
                            binding = binding,
                            fullSourceFilePath = sourceFile?.let { "/$artifactId/$it" },
                            modifiers = modifiers,
                            description = description?.let {
                                objectMapper.readValue(it, BindingDecl.Description::class.java) }
                                    ?: BindingDecl.Description.Package,
                            children = null
                    )
                }
                .iterator())

        return PrefixIterator(types, "")
    }

    fun packageTreeDiff(conn: Handle, artifactIdOld: String, artifactIdNew: String, packageName: String? = null): Iterator<DeclarationNode> {
        return DeclarationTreeDiff.diffOrdered(
                packageTree(conn, artifactIdOld, packageName),
                packageTree(conn, artifactIdNew, packageName),
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
            val artifactId_capture: String,

            val parent: String?
    ) : TreeIterator<BindingDecl, DeclarationNode>(flat_capture) {
        override fun mapOneItem(): DeclarationNode {
            val item = flatDelegate.next()
            assert(!returnToParent(item)) // checked in hasNext
            val newItr = copy(parent = item.binding)
            registerSubIterator(newItr)
            return DeclarationNode(
                    artifactId = artifactId_capture,
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
            artifactId: String,
            annotations: Sequence<PositionedAnnotation>,
            fullSourceFilePath: String? = null
    ): Iterator<DeclarationNode> {
        val flat = Iterators.peekingIterator(annotations.mapNotNull { it.annotation as?BindingDecl }.iterator())

        return SourceDeclarationTreeItr(flat, fullSourceFilePath, artifactId, null)
    }
}