package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.DeclarationNode
import com.google.common.collect.Iterators
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator
import java.net.URLEncoder
import java.util.Objects

/**
 * @author yawkat
 */
object DeclarationTreeDiff {

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
                    return nodeA.binding == nodeB.binding &&
                            nodeA.description == nodeB.description &&
                            nodeA.modifiers == nodeB.modifiers
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

    fun diffUnordered(
            old: Iterator<DeclarationNode>,
            new: Iterator<DeclarationNode>
    ): Iterator<DeclarationNode> {
        val worker = DiffDeclarationTree()
        worker.fullDumpOld[null] = worker.dump(worker.fullDumpOld, old)
        worker.fullDumpNew[null] = worker.dump(worker.fullDumpNew, new)

        return worker.diffNode(null).iterator()
    }

    fun diffOrdered(
            old_: Iterator<DeclarationNode>,
            new_: Iterator<DeclarationNode>,
            comparator: Comparator<DeclarationNode>
    ): Iterator<DeclarationNode> {

        fun mapToResult(node: DeclarationNode, result: DeclarationNode.DiffResult): DeclarationNode = node.copy(
                diffResult = result,
                children = node.children?.let { Iterators.transform(it) { child -> mapToResult(child!!, result) } }
        )

        val old = Iterators.peekingIterator(old_)
        val new = Iterators.peekingIterator(new_)

        return object : Iterator<DeclarationNode> {
            override fun hasNext() = old.hasNext() || new.hasNext()

            override fun next(): DeclarationNode {
                if (!old.hasNext()) return mapToResult(new.next(), DeclarationNode.DiffResult.INSERTION)
                if (!new.hasNext()) return mapToResult(old.next(), DeclarationNode.DiffResult.DELETION)

                val cmp = comparator.compare(old.peek(), new.peek())
                when {
                    cmp < 0 -> return mapToResult(old.next(), DeclarationNode.DiffResult.DELETION)
                    cmp > 0 -> return mapToResult(new.next(), DeclarationNode.DiffResult.INSERTION)
                    else -> {
                        val newNode = new.next()
                        val oldNode = old.next()
                        val diffPath =
                                if (newNode.fullSourceFilePath != null && oldNode.fullSourceFilePath != null)
                                    "${newNode.fullSourceFilePath}" +
                                            "?diff=${URLEncoder.encode(oldNode.fullSourceFilePath, "UTF-8")}"
                                else
                                    null
                        return newNode.copy(
                                diffResult = DeclarationNode.DiffResult.UNCHANGED,
                                children =
                                if (oldNode.children == null || newNode.children == null) null
                                else diffOrdered(oldNode.children, newNode.children, comparator),
                                fullSourceFilePath = diffPath
                        )
                    }
                }
            }
        }
    }
}