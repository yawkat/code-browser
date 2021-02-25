package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.DeclarationNode
import com.google.common.base.Equivalence
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator

/**
 * Diff two lists.
 */
fun <T> buildDiffEdits(
    newItems: List<T>,
    oldItems: List<T>,
    equivalence: Equivalence<in T>
) = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
    .diff<SequenceImpl<T>>(ComparatorImpl(equivalence), SequenceImpl(oldItems), SequenceImpl(newItems))!!

/**
 * Iterate over the changed and unchanged regions of a diff.
 */
inline fun iterateEdits(
    diff: List<Edit>,
    newLength: Int,
    oldLength: Int,
    visitUnchanged: (IntRange, IntRange) -> Unit,
    visitChanged: (IntRange, IntRange) -> Unit
) {
    var newI = 0
    var oldI = 0

    for (edit in diff) {
        if (newI < edit.beginB) {
            require(oldI < edit.beginA)
            visitUnchanged(
                newI until edit.beginB,
                oldI until edit.beginA
            )
        }

        visitChanged(
            edit.beginB until edit.endB,
            edit.beginA until edit.endA
        )
        newI = edit.endB
        oldI = edit.endA
    }
    if (newI < newLength) {
        visitUnchanged(
            newI until newLength,
            oldI until oldLength
        )
    }
}

/**
 * Transform a diff as returned by [buildDiffEdits] to a list of additions/deletions or unchanged items
 */
inline fun <T, R> mapEdits(
    newItems: List<T>,
    oldItems: List<T>,
    diff: List<Edit>,
    mapItem: (T, DeclarationNode.DiffResult) -> R
): List<R> {
    val result = ArrayList<R>()
    iterateEdits(
        diff,
        newLength = newItems.size,
        oldLength = oldItems.size,
        visitUnchanged = { newR, oldR ->
            require(newR.first - newR.last == oldR.first - oldR.last)
            result.addAll(
                newItems.subList(newR.first, newR.last + 1).map { mapItem(it, DeclarationNode.DiffResult.UNCHANGED) })
        },
        visitChanged = { newR, oldR ->
            result.addAll(
                oldItems.subList(oldR.first, oldR.last + 1).map { mapItem(it, DeclarationNode.DiffResult.DELETION) })
            result.addAll(
                newItems.subList(newR.first, newR.last + 1).map { mapItem(it, DeclarationNode.DiffResult.INSERTION) })
        }
    )
    return result
}

private class SequenceImpl<out T>(val items: List<T>) : Sequence() {
    override fun size() = items.size
}

private class ComparatorImpl<T>(private val equivalence: Equivalence<in T>) : SequenceComparator<SequenceImpl<T>>() {
    override fun hash(seq: SequenceImpl<T>, ptr: Int) = equivalence.hash(seq.items[ptr])

    override fun equals(a: SequenceImpl<T>, ai: Int, b: SequenceImpl<T>, bi: Int) =
        equivalence.equivalent(a.items[ai], b.items[bi])
}
