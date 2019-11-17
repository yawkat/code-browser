package at.yawk.javabrowser.server

import com.google.common.collect.Iterables
import com.google.common.collect.Iterators
import org.eclipse.collections.api.map.primitive.MutableCharObjectMap
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap
import org.eclipse.collections.api.set.primitive.IntSet
import org.eclipse.collections.api.set.primitive.MutableIntSet
import org.eclipse.collections.impl.factory.primitive.CharObjectMaps
import org.eclipse.collections.impl.factory.primitive.IntIntMaps
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.IntSets
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import java.nio.file.Files
import java.nio.file.Paths
import java.util.BitSet
import java.util.Collections

/**
 * @author yawkat
 */
class IndexAutomaton<E : Any>(
        entries: List<E>,
        components: (E) -> List<String>,
        jumps: Int
) {
    private companion object {
        private const val CHUNK_SIZE = 512
    }

    private val automata = Iterables.partition(entries, CHUNK_SIZE).map { chunk ->
        val nfa = Automaton(chunk)
        val root = nfa.StateBuilder()
        val cache = ObjectIntMaps.mutable.empty<StateRequest>()
        for ((i, entry) in chunk.withIndex()) {
            root.addTransition(Automaton.EPSILON, fromInput(
                    nfa = nfa,
                    cache = cache,
                    components = components(entry),
                    finalIndex = i,
                    request = StateRequest(0, 0, jumps, false)
            ))
            cache.clear()
        }
        root.finish()
        nfa.startState = root.state

        nfa.pruneDead()
        val dfa = nfa.nfaToDfa()
        dfa.deduplicateFinals()
        dfa
    }

    fun run(query: String): Iterator<E> = Iterators.concat(
            Iterators.transform(automata.iterator()) { it!!.run(query) ?: Collections.emptyIterator<E>() })

    private data class StateRequest(
            val componentsIndex: Int,
            val stringIndex: Int,
            val remainingJumps: Int,
            val justJumped: Boolean
    )

    private fun fromInput(
            nfa: Automaton<E>,
            cache: MutableObjectIntMap<StateRequest>,
            components: List<String>,
            finalIndex: Int,
            request: StateRequest
    ): Int {
        cache.getIfAbsent(request, -1).let { if (it != -1) return it }

        val builder = nfa.StateBuilder()
        cache.put(request, builder.state)

        val lastComponent = request.componentsIndex >= components.size - 1
        val lastInComponent = request.stringIndex >= components[request.componentsIndex].length - 1

        if (!lastInComponent ||
                (lastComponent && request.stringIndex == components[request.componentsIndex].length - 1)) {
            // normal step
            builder.addTransition(components[request.componentsIndex][request.stringIndex],
                    fromInput(nfa, cache, components, finalIndex, StateRequest(
                            componentsIndex = request.componentsIndex,
                            stringIndex = request.stringIndex + 1,
                            remainingJumps = request.remainingJumps,
                            justJumped = false
                    )))
        }
        if (!lastComponent) {
            if (lastInComponent) {
                // step without jump to next component
                builder.addTransition(components[request.componentsIndex][request.stringIndex],
                        fromInput(nfa, cache, components, finalIndex, StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps = request.remainingJumps,
                                justJumped = false
                        )))
            } else if (request.remainingJumps > 0) {
                // jump to next component
                builder.addTransition(Automaton.EPSILON,
                        fromInput(nfa, cache, components, finalIndex, StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps = request.remainingJumps - 1,
                                justJumped = true
                        )))
            }
        }
        if (request.remainingJumps == 0 && !request.justJumped) {
            builder.setFinal(finalIndex)
        }
        builder.finish()
        return builder.state
    }
}

private class StaticBitSet private constructor(private val data: LongArray) {
    private companion object {
        private const val BITS_BITS = 6 // 64 = 2**6

        private fun roundToNextPower(i: Int) = (((i - 1) shr BITS_BITS) + 1) shl BITS_BITS
    }

    constructor(capacity: Int) : this(LongArray(roundToNextPower(capacity) shr BITS_BITS))

    private fun remainingWord(i: Int) = data[i ushr BITS_BITS] ushr i

    operator fun get(i: Int): Boolean {
        // the shr implicitly does a % 64
        return (remainingWord(i) and 1) != 0L
    }

    fun set(i: Int) {
        val arrayIndex = i ushr BITS_BITS
        // the shl implicitly does a % 64
        data[arrayIndex] = data[arrayIndex] or (1L shl i)
    }

    fun orFrom(other: StaticBitSet) {
        if (other.data.size != this.data.size)
            throw IllegalArgumentException()
        for (index in data.indices) {
            data[index] = data[index] or other.data[index]
        }
    }

    fun <E> filter(list: List<E>): Iterator<E> = object : Iterator<E> {
        private var i = -1

        init {
            proceedNextBit()
        }

        private fun proceedNextBit() {
            i++
            while (i < list.size) {
                val remainingWord = remainingWord(i)
                if (remainingWord == 0L) {
                    i = roundToNextPower(i + 1)
                } else {
                    i += java.lang.Long.numberOfTrailingZeros(remainingWord)
                    break
                }
            }
        }

        override fun next(): E {
            val value = list[i]
            proceedNextBit()
            return value
        }

        override fun hasNext() = i < list.size
    }

    override fun equals(other: Any?) = other is StaticBitSet && this.data.contentEquals(other.data)
    override fun hashCode() = data.contentHashCode()

    class IntStaticBitSetMap(private val memberCapacity: Int) {
        private val data = LongLists.mutable.empty()
        private val indices = IntIntMaps.mutable.empty()

        fun put(key: Int, value: StaticBitSet) {
            val index = indices.getIfAbsent(key, -1)
            if (index == -1) {
                indices.put(key, data.size())
                data.addAll(*value.data)
            } else {
                for ((offset, item) in value.data.withIndex()) {
                    data[index + offset] = item
                }
            }
        }

        operator fun get(key: Int): StaticBitSet? {
            val index = indices.getIfAbsent(key, -1)
            if (index == -1) return null
            val res = StaticBitSet(memberCapacity)
            for (i in res.data.indices) {
                res.data[i] = data[index + i]
            }
            return res
        }

        fun containsKey(key: Int) = indices.containsKey(key)

        inline fun forEachKeyValue(f: (Int, StaticBitSet) -> Unit) {
            val itr = indices.keysView().intIterator()
            while (itr.hasNext()) {
                val key = itr.next()
                f(key, get(key)!!)
            }
        }

        fun deduplicated(): IntStaticBitSetMap {
            val cache = ObjectIntMaps.mutable.empty<StaticBitSet>()
            val result = IntStaticBitSetMap(memberCapacity)
            forEachKeyValue { k, v ->
                val index = cache.getIfAbsent(v, -1)
                if (index == -1) {
                    cache.put(v, result.data.size())
                    result.put(k, v)
                } else {
                    result.indices.put(k, index)
                }
            }
            return result
        }
    }
}

private class Automaton<F : Any>(val finals: List<F>) {
    companion object {
        private const val NO_TRANSITIONS = -1
        private const val TRANSITIONS_END = -1L
        const val EPSILON = '\u0000'

        /**
         * Reusable to avoid allocation
         */
        private val createIntSet: Function0<MutableIntSet> = { IntSets.mutable.empty() }

        private fun encodeTransition(symbol: Char, to: Int): Long {
            return (symbol.toLong() shl 32) or to.toLong()
        }

        private fun decodeTransitionSymbol(transition: Long): Char {
            return (transition ushr 32).toChar()
        }

        private fun decodeTransitionTarget(transition: Long): Int {
            return transition.toInt()
        }
    }

    var startState = -1

    private val transitionIndices = IntLists.mutable.empty()
    private val transitions = LongLists.mutable.empty()

    private var finalIndices = StaticBitSet.IntStaticBitSetMap(finals.size)

    private inline fun forTransitions(from: Int, f: (Char, Int) -> Unit) {
        var i = transitionIndices[from]
        if (i == NO_TRANSITIONS) return
        while (i < transitions.size() && transitions[i] != TRANSITIONS_END) {
            val transition = transitions[i++]
            f(decodeTransitionSymbol(transition), decodeTransitionTarget(transition))
        }
    }

    fun run(query: String): Iterator<F>? {
        var state = startState
        for (c in query) {
            // if we sort transitions we could binary search here, but it's probably not worth it for the maybe
            // max 50 transitions we accumulate at one state
            var next = -1
            forTransitions(state) { ch, to ->
                if (ch == c) {
                    if (next != -1) throw IllegalStateException("Not a DFA")
                    next = to
                }
            }
            if (next == -1) return null
            state = next
        }
        return getFinals(state)
    }

    private fun getFinals(state: Int): Iterator<F>? {
        val bitSet = finalIndices.get(state)
        if (bitSet == null) {
            return null
        } else {
            return bitSet.filter(finals)
        }
    }

    private fun recordTransitions(to: MutableCharObjectMap<MutableIntSet>, from: Int) {
        forTransitions(from) { ch, dest ->
            if (ch == EPSILON) {
                recordTransitions(to, dest)
            } else {
                to.getIfAbsentPut(ch, createIntSet).add(dest)
            }
        }
    }

    private fun <F : Any> nfaToDfaImpl(
            dfa: Automaton<F>,
            cache: MutableObjectIntMap<IntSet>,
            key: IntSet
    ): Int {
        val s = cache.getIfAbsent(key, -1)
        if (s != -1) {
            return s
        }
        val builder = dfa.StateBuilder()
        cache.put(key, builder.state)

        val transitions = CharObjectMaps.mutable.empty<MutableIntSet>()
        var finals: StaticBitSet? = null
        val itr = key.intIterator()
        while (itr.hasNext()) {
            val memberState = itr.next()
            recordTransitions(transitions, memberState)
            val memberFinals = finalIndices[memberState]
            if (memberFinals != null) {
                if (finals == null) finals = StaticBitSet(this.finals.size)
                finals.orFrom(memberFinals)
            }
        }

        transitions.forEachKeyValue { symbol, nextKey ->
            builder.addTransition(symbol, nfaToDfaImpl(dfa, cache, nextKey))
        }

        if (finals != null) {
            builder.setFinals(finals)
        }

        builder.finish()
        return builder.state
    }

    fun nfaToDfa(): Automaton<F> {
        val automaton = Automaton(finals)
        automaton.startState = nfaToDfaImpl(automaton, ObjectIntMaps.mutable.empty(), IntSets.immutable.of(startState))
        return automaton
    }

    /**
     * Remove states that have no transitions to final states. Does not change the behavior of this automaton.
     */
    fun pruneDead() {
        val transitionsToRemove = BitSet()
        var start = 0
        while (start < transitions.size()) {
            var anyTransitionsAlive = false
            var oldEnd = 0
            while (oldEnd + start < transitions.size() && transitions[oldEnd + start] != TRANSITIONS_END) {
                val dest = decodeTransitionTarget(transitions[oldEnd + start])
                val destAlive = (
                        transitionIndices[dest] != NO_TRANSITIONS &&
                                transitions[transitionIndices[dest]] != TRANSITIONS_END) ||
                        finalIndices.containsKey(dest)
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
                    transitions[newEnd] = TRANSITIONS_END
                }
            } else {
                transitions[start] = TRANSITIONS_END
            }

            start += oldEnd + 1
            transitionsToRemove.clear()
        }
    }

    fun deduplicateFinals() {
        finalIndices = finalIndices.deduplicated()
    }

    /**
     * For debugging
     */
    @Suppress("unused")
    fun dumpDot(name: String = "automaton.dot") {
        Files.newBufferedWriter(Paths.get(name)).use { wr ->
            wr.appendln("digraph g {")
            for (state in 0 until transitionIndices.size()) {
                wr.appendln("$state [shape=${if (finalIndices.containsKey(state)) "doublecircle" else "circle"}];")
                forTransitions(state) { ch, to ->
                    wr.appendln("$state -> $to [label=\"${if (ch == EPSILON) 'ε' else ch}\"];")
                }
            }
            wr.appendln("}")
        }
    }

    inner class StateBuilder {
        @Suppress("JoinDeclarationAndAssignment")
        val state: Int

        private val stateTransitions = LongLists.mutable.empty()

        init {
            state = transitionIndices.size()
            transitionIndices.add(NO_TRANSITIONS)
        }

        fun addTransition(transition: Long) {
            stateTransitions.add(transition)
        }

        fun addTransition(symbol: Char, target: Int) {
            addTransition(encodeTransition(symbol, target))
        }

        fun setFinal(index: Int) {
            val set = StaticBitSet(finals.size)
            set.set(index)
            setFinals(set)
        }

        fun setFinals(indices: StaticBitSet) {
            finalIndices.put(state, indices)
        }

        fun finish() {
            if (!stateTransitions.isEmpty) {
                transitionIndices[state] = transitions.size()
                transitions.addAll(stateTransitions)
                transitions.add(TRANSITIONS_END)
            }
        }
    }
}