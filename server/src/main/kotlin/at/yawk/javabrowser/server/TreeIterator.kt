package at.yawk.javabrowser.server

import com.google.common.collect.PeekingIterator

/**
 * @author yawkat
 */
abstract class TreeIterator<O, E>(protected val flatDelegate: PeekingIterator<O>) : Iterator<E> {
    private var eaten = false
    private var toEat: TreeIterator<*, *>? = null

    override fun hasNext(): Boolean {
        if (eaten) throw IllegalStateException("Iterator already eaten")

        // consume the toEat iterator so we're sure we reached the end of the previous subtree
        toEat?.forEach { _ -> }
        toEat?.eaten = true
        toEat = null

        if (!flatDelegate.hasNext()) return false
        return !returnToParent(flatDelegate.peek())
    }

    override fun next(): E {
        return mapOneItem()
    }

    protected fun registerSubIterator(subIterator: TreeIterator<*, *>) {
        if (toEat != null) throw IllegalStateException()
        toEat = subIterator
    }

    protected abstract fun mapOneItem(): E

    protected abstract fun returnToParent(item: O): Boolean
}