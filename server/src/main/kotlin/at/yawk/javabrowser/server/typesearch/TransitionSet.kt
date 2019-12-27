package at.yawk.javabrowser.server.typesearch

import at.yawk.numaec.BufferBasedCollection
import at.yawk.numaec.LargeByteBufferAllocator
import at.yawk.numaec.MutableIntBufferListFactory
import at.yawk.numaec.MutableLongBufferListFactory
import at.yawk.numaec.MutableShortBufferListFactory
import org.eclipse.collections.api.LongIterable
import org.eclipse.collections.api.PrimitiveIterable
import org.eclipse.collections.api.list.primitive.CharList
import org.eclipse.collections.api.list.primitive.IntList
import org.eclipse.collections.api.list.primitive.LongList
import org.eclipse.collections.api.list.primitive.MutableIntList
import org.eclipse.collections.api.list.primitive.MutableLongList
import org.eclipse.collections.api.list.primitive.MutableShortList
import org.eclipse.collections.api.list.primitive.ShortList
import org.eclipse.collections.api.set.primitive.IntSet
import org.eclipse.collections.impl.factory.primitive.CharSets
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList
import org.eclipse.collections.impl.list.mutable.primitive.ShortArrayList
import java.io.Serializable
import java.util.BitSet

internal class TransitionSet : Serializable, BufferBasedCollection {
    companion object {
        private fun encodeTransition(symbol: Char, to: Int): Long {
            return (symbol.toLong() shl 32) or to.toLong()
        }

        private fun decodeTransitionSymbol(transition: Long): Char {
            return (transition ushr 32).toChar()
        }

        private fun decodeTransitionTarget(transition: Long): Int {
            return transition.toInt()
        }

        const val NO_TRANSITIONS_INT = -1
        const val NO_TRANSITIONS_SHORT = (-1).toShort()

        const val TRANSITIONS_END_LONG = -1L
        const val TRANSITIONS_END_INT = -1
        const val TRANSITIONS_END_SHORT = (-1).toShort()
    }

    var alphabet: Alphabet? = null
    var transitionIndices: PrimitiveIterable = IntLists.mutable.empty()!!
    var transitions: PrimitiveIterable = LongLists.mutable.empty()!!

    val size: Int
        get() = transitionIndices.size()

    fun createState(): Int {
        (transitionIndices as MutableIntList).add(NO_TRANSITIONS_INT)
        return transitionIndices.size() - 1
    }

    fun putTransitions(state: Int, stateTransitions: LongIterable) {
        val transitionIndices = this.transitionIndices as MutableIntList
        val transitions = this.transitions as MutableLongList

        transitionIndices[state] = transitions.size()
        transitions.addAll(stateTransitions)
        transitions.add(TRANSITIONS_END_LONG)
    }

    inline fun forTransitions(from: Int, f: (Char, Int) -> Unit) {
        val transitionIndices = this.transitionIndices

        var i: Int
        when (transitionIndices) {
            is IntList -> {
                i = transitionIndices.get(from)
                if (i == NO_TRANSITIONS_INT) return
            }
            is ShortList -> {
                i = transitionIndices.get(from).toInt() and 0xffff
                if (i.toShort() == NO_TRANSITIONS_SHORT) return
            }
            else -> throw IllegalStateException()
        }

        val transitions = this.transitions
        val alphabet = this.alphabet
        loop@while (i < transitions.size()) {
            val symbol: Char
            val target: Int
            when (transitions) {
                is LongList -> {
                    val transition = transitions[i]
                    if (transition == TRANSITIONS_END_LONG) break@loop
                    symbol = decodeTransitionSymbol(transition)
                    target = decodeTransitionTarget(transition)
                }
                is IntList -> {
                    val transition = transitions[i]
                    if (transition == TRANSITIONS_END_INT) break@loop
                    val targetBits = 32 - alphabet!!.requiredBits
                    symbol = alphabet.unmapFromAlphabet((transition ushr targetBits).toChar())
                    target = (transition and ((1 shl targetBits) - 1))
                }
                is ShortList -> {
                    val transition = transitions[i]
                    if (transition == TRANSITIONS_END_SHORT) break@loop
                    val targetBits = 16 - alphabet!!.requiredBits
                    symbol = alphabet.unmapFromAlphabet(((transition.toInt() and 0xffff) ushr targetBits).toChar())
                    target = (transition.toInt() and ((1 shl targetBits) - 1))
                }
                else -> throw IllegalStateException()
            }
            f(symbol, target)
            i++
        }
    }

    fun pruneDead(finals: IntSet) {
        val transitionIndices = this.transitionIndices as MutableIntList
        val transitions = this.transitions as MutableLongList

        val transitionsToRemove = BitSet()
        var start = 0
        while (start < transitions.size()) {
            var anyTransitionsAlive = false
            var oldEnd = 0
            while (oldEnd + start < transitions.size() && transitions[oldEnd + start] != TRANSITIONS_END_LONG) {
                val dest = decodeTransitionTarget(transitions[oldEnd + start])
                val destAlive = (
                        transitionIndices[dest] != NO_TRANSITIONS_INT &&
                                transitions[transitionIndices[dest]] != TRANSITIONS_END_LONG) ||
                        finals.contains(dest)
                if (destAlive) {
                    anyTransitionsAlive = true
                } else {
                    transitionsToRemove.set(oldEnd)
                }
                oldEnd++
            }
            if (anyTransitionsAlive) {
                val newEnd = oldEnd - transitionsToRemove.cardinality()
                if (newEnd != oldEnd) {
                    var srcI = oldEnd - 1
                    var destI = newEnd - 1
                    while (destI >= 0) {
                        if (transitionsToRemove[srcI]) {
                            srcI--
                        } else {
                            transitions[start + destI] = transitions[start + srcI]
                            srcI--
                            destI--
                        }
                    }
                    transitions[newEnd] = TRANSITIONS_END_LONG
                }
            } else {
                transitions[start] = TRANSITIONS_END_LONG
            }

            start += oldEnd + 1
            transitionsToRemove.clear()
        }
    }

    fun compact() {
        val alphabetBuilder = Alphabet.Builder()
        val oldTransitionIndices = transitionIndices as IntList
        val oldTransitions = transitions as LongList

        // build alphabet
        for (i in 0 until oldTransitions.size()) {
            val transition = oldTransitions[i]
            if (transition != TRANSITIONS_END_LONG) {
                alphabetBuilder.put(decodeTransitionSymbol(transition))
                if (alphabetBuilder.size.toInt() > 1000) return
            }
        }

        val alphabet = alphabetBuilder.build()
        this.alphabet = alphabet

        if (oldTransitions.size() <= 0xffff) {
            val newTransitionIndices = ShortArrayList(
                    oldTransitionIndices.size())
            for (i in 0 until oldTransitionIndices.size()) {
                val item = oldTransitionIndices[i]
                // 0xffff is used for NO_TRANSITIONS_SHORT
                if (item >= 0xffff) throw AssertionError()
                newTransitionIndices.add(item.toShort())
            }
            this.transitionIndices = newTransitionIndices
        }

        val stateBits = 32 - Integer.numberOfLeadingZeros(oldTransitionIndices.size())
        val newTransitions: PrimitiveIterable
        when {
            stateBits + alphabet.requiredBits <= 16 -> newTransitions = ShortArrayList(
                    oldTransitions.size())
            stateBits + alphabet.requiredBits <= 32 -> newTransitions = IntArrayList(
                    oldTransitions.size())
            else -> return
        }

        // copy transitions
        for (i in 0 until oldTransitions.size()) {
            val transition = oldTransitions[i]
            if (transition == TRANSITIONS_END_LONG) {
                when (newTransitions) {
                    is MutableShortList -> newTransitions.add(TRANSITIONS_END_SHORT)
                    is MutableIntList -> newTransitions.add(TRANSITIONS_END_INT)
                    else -> throw AssertionError()
                }
            } else {
                val mappedSymbol = alphabet.mapToAlphabet(decodeTransitionSymbol(transition))
                val target = decodeTransitionTarget(transition)
                when (newTransitions) {
                    is MutableShortList ->
                        newTransitions.add(((mappedSymbol.toInt() shl (16 - alphabet.requiredBits)) or target).toShort())
                    is MutableIntList ->
                        newTransitions.add((mappedSymbol.toInt() shl (32 - alphabet.requiredBits)) or target)
                    else -> throw AssertionError()
                }
            }
        }
        this.transitions = newTransitions
    }

    override fun close() {
        (transitionIndices as? BufferBasedCollection)?.close()
        (transitions as? BufferBasedCollection)?.close()
    }

    fun externalize(allocator: LargeByteBufferAllocator) {
        val oldIndices = transitionIndices
        transitionIndices = when (oldIndices) {
            is IntList -> MutableIntBufferListFactory.withAllocator(
                    allocator).ofAll(oldIndices)
            is ShortList -> MutableShortBufferListFactory.withAllocator(
                    allocator).ofAll(oldIndices)
            else -> throw IllegalStateException()
        }
        val oldTransitions = transitions
        transitions = when (oldTransitions) {
            is LongList -> MutableLongBufferListFactory.withAllocator(
                    allocator).ofAll(oldTransitions)
            is IntList -> MutableIntBufferListFactory.withAllocator(
                    allocator).ofAll(oldTransitions)
            is ShortList -> MutableShortBufferListFactory.withAllocator(
                    allocator).ofAll(oldTransitions)
            else -> throw IllegalStateException()
        }
    }

    class Alphabet private constructor(private val items: CharList) : Serializable {
        val size: Char
            get() = items.size().toChar()

        val requiredBits: Int
            get() = 32 - Integer.numberOfLeadingZeros(size.toInt())

        fun get(c: Char, default: Char): Char {
            val r = items.binarySearch(c)
            return if (r < 0) default else r.toChar()
        }

        fun mapToAlphabet(c: Char): Char {
            val r = items.binarySearch(c)
            if (r < 0)
                throw NoSuchElementException()
            return r.toChar()
        }

        fun unmapFromAlphabet(c: Char): Char {
            return items[c.toInt()]
        }

        class Builder {
            private val items = CharSets.mutable.empty()

            val size: Char
                get() = items.size().toChar()

            fun put(c: Char) {
                items.add(c)
            }

            fun build() = Alphabet(items.toSortedList())
        }
    }

    inner class StateBuilder {
        @Suppress("JoinDeclarationAndAssignment")
        val state: Int

        private val stateTransitions = LongLists.mutable.empty()

        init {
            state = createState()
        }

        fun addTransition(transition: Long) {
            stateTransitions.add(transition)
        }

        fun addTransition(symbol: Char, target: Int) {
            addTransition(encodeTransition(symbol, target))
        }

        fun finish() {
            if (!stateTransitions.isEmpty) {
                putTransitions(state, stateTransitions)
            }
        }
    }
}