package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.server.view.DeclarationNode
import at.yawk.javabrowser.server.view.PackageNodeView
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Iterators
import com.google.common.collect.PeekingIterator
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.util.Collections
import java.util.Objects

/**
 * @author yawkat
 */
class DeclarationTreeHandler(
        private val dbi: DBI,
        private val ftl: Ftl,
        private val objectMapper: ObjectMapper
) : HttpHandler {
    companion object {
        const val PATTERN = "/declarationTree"
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val artifactId = exchange.queryParameters["artifactId"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass artifact ID")
        val binding = exchange.queryParameters["binding"]?.peekFirst()
                ?: throw HttpException(404, "Need to pass binding")

        // TODO: handle binding source file

        dbi.inTransaction { conn: Handle, _ ->
            val json = conn.select("select path, json from sourceFiles where artifactid = ? and path = (select sourceFile from bindings where bindings.artifactid = ? and binding = ?)", artifactId, artifactId, binding)

            if (json.isNotEmpty()) {
                val sourceFile = objectMapper.readValue(
                        json.single()["json"] as ByteArray,
                        AnnotatedSourceFile::class.java)
                val fullPath = "/$artifactId/${json.single()["path"]}"
                for (topLevelType in sourceDeclarationTree(artifactId, sourceFile, fullSourceFilePath = fullPath)) {
                    if (topLevelType.binding == binding) {
                        ftl.render(exchange, PackageNodeView(topLevelType.children!!))
                        return@inTransaction
                    }
                }
                throw HttpException(404, "Binding not found - is it a top-level declaration?")
            } else {
                // the binding must be a package
                val iterator = packageTree(conn, artifactId, binding)
                // this is also some protection against XSS because it checks that `binding` is actually a valid package
                if (!iterator.hasNext()) throw HttpException(404, "Binding not found")
                ftl.render(exchange, PackageNodeView(iterator))
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
        val itemsByDepth = conn.createQuery("select depth, typeCount from data.type_count_by_depth_view where artifactId = ? and (package = ? or ?)")
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
                    select name, description, modifiers, sourcefile 
                    from (
                        select artifactId, binding as name, description, modifiers, sourcefile from data.bindings where parent is null 
                        union select artifactId, name, NULL as description, 0 as modifiers, NULL as sourcefile from data.packages_view
                    ) sq where artifactId = ? and name like ? and data.count_dots(name) < ? order by name""")
                .bind(0, artifactId)
                .bind(1, if (packageName == null) "%" else "$packageName.%")
                .bind(2, depth + (packageName?.count { it == '.' } ?: -1))
                .map { _, rs, _ ->
                    val binding = rs.getString(1)
                    val description: ByteArray? = rs.getBytes(2)
                    val modifiers = rs.getInt(3)
                    val sourceFile: String? = rs.getString(4)
                    if (description == null) {
                        DeclarationNode(
                                artifactId = artifactId,
                                binding = binding,
                                description = BindingDecl.Description.Package,
                                modifiers = 0,
                                // children may be added later
                                children = null
                        )
                    } else {
                        DeclarationNode(
                                artifactId = artifactId,
                                binding = binding,
                                fullSourceFilePath = "/$artifactId/$sourceFile",
                                modifiers = modifiers,
                                description = objectMapper.readValue(description,
                                        BindingDecl.Description::class.java),
                                children = null
                        )
                    }
                }
                .iterator())

        return PrefixIterator(types, "")
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
            sourceFile: AnnotatedSourceFile,
            fullSourceFilePath: String? = null
    ): Iterator<DeclarationNode> {
        val flat = Iterators.peekingIterator(sourceFile.declarations)

        return SourceDeclarationTreeItr(flat, fullSourceFilePath, artifactId, null)
    }

    private class DiffDeclarationTree {
        // dumps of DeclarationNode#children
        val fullDumpOld = HashMap<String?, List<DeclarationNode>>()
        val fullDumpNew = HashMap<String?, List<DeclarationNode>>()

        fun dump(target: MutableMap<String?, List<DeclarationNode>>, itr: Iterator<DeclarationNode>):
                List<DeclarationNode> {
            val items = ArrayList<DeclarationNode>()
            for (item in itr) {
                if (item.children != null) {
                    target[item.binding] = dump(target, item.children)
                }
                items.add(item)
            }
            return items
        }

        fun diffNode(parentBinding: String?): List<DeclarationNode> {
            val new = fullDumpNew[parentBinding]!!
            val old = fullDumpOld[parentBinding]!!
            val algorithm = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)

            class SequenceImpl(val items: List<DeclarationNode>) : Sequence() {
                override fun size() = items.size
            }

            class ComparatorImpl : SequenceComparator<SequenceImpl>() {
                override fun hash(seq: SequenceImpl, ptr: Int) = Objects.hash(
                        seq.items[ptr].binding.hashCode(),
                        seq.items[ptr].description.hashCode(),
                        seq.items[ptr].modifiers.hashCode()
                )
                override fun equals(a: SequenceImpl, ai: Int, b: SequenceImpl, bi: Int): Boolean {
                    val nodeA = a.items[ai]
                    val nodeB = b.items[bi]
                    val identical = nodeA.binding == nodeB.binding &&
                            nodeA.description == nodeB.description &&
                            nodeA.modifiers == nodeB.modifiers
                    return identical
                }
            }

            val diff = algorithm.diff(ComparatorImpl(), SequenceImpl(old), SequenceImpl(new))

            val result = ArrayList<DeclarationNode>()
            var newI = 0
            var oldI = 0

            for (edit in diff) {
                while (newI < edit.beginB) {
                    assert(oldI < edit.beginA)
                    result.add(mapNode(new[newI], DeclarationNode.DiffResult.UNCHANGED))
                    newI++
                    oldI++
                }

                while (oldI < edit.endA) {
                    result.add(mapNode(old[oldI++], DeclarationNode.DiffResult.DELETION))
                }
                while (newI < edit.endB) {
                    result.add(mapNode(new[newI++], DeclarationNode.DiffResult.INSERTION))
                }
            }
            while (newI < new.size) {
                assert(oldI < old.size)
                result.add(mapNode(new[newI], DeclarationNode.DiffResult.UNCHANGED))
                newI++
                oldI++
            }
            assert(oldI == old.size)

            return result
        }

        fun mapNode(node: DeclarationNode, diffResult: DeclarationNode.DiffResult): DeclarationNode {
            var ownResult = diffResult
            val mappedChildren: Iterator<DeclarationNode> = when (diffResult) {
                DeclarationNode.DiffResult.UNCHANGED -> {
                    val childrenDiff = diffNode(node.binding)
                    if (childrenDiff.any { it.diffResult != DeclarationNode.DiffResult.UNCHANGED }) {
                        ownResult = DeclarationNode.DiffResult.CHANGED_INTERNALLY
                    }
                    childrenDiff.iterator()
                }
                // should only be set in this function
                DeclarationNode.DiffResult.CHANGED_INTERNALLY -> throw AssertionError()
                DeclarationNode.DiffResult.INSERTION -> Iterators.transform(fullDumpNew[node.binding]!!.iterator()) {
                    mapNode(it!!, diffResult)
                }
                DeclarationNode.DiffResult.DELETION -> Iterators.transform(fullDumpOld[node.binding]!!.iterator()) {
                    mapNode(it!!, diffResult)
                }
            }
            return node.copy(diffResult = ownResult, children = mappedChildren)
        }
    }

    fun diffDeclarationTree(
            oldArtifactId: String,
            oldSourceFile: AnnotatedSourceFile,
            newArtifactId: String,
            newSourceFile: AnnotatedSourceFile,

            fullSourceFilePath: String? = null
    ): Iterator<DeclarationNode> {
        val worker = DiffDeclarationTree()
        worker.fullDumpOld[null] = worker.dump(worker.fullDumpOld,
                sourceDeclarationTree(oldArtifactId, oldSourceFile, fullSourceFilePath))
        worker.fullDumpNew[null] = worker.dump(worker.fullDumpNew,
                sourceDeclarationTree(newArtifactId, newSourceFile, fullSourceFilePath))

        return worker.diffNode(null).iterator()
    }
}