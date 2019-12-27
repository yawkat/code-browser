package at.yawk.javabrowser.server.typesearch

import at.yawk.numaec.BufferBasedCollection
import at.yawk.numaec.LargeByteBufferAllocator
import org.eclipse.collections.api.map.primitive.MutableCharObjectMap
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap
import org.eclipse.collections.api.set.primitive.IntSet
import org.eclipse.collections.api.set.primitive.MutableIntSet
import org.eclipse.collections.impl.factory.primitive.CharObjectMaps
import org.eclipse.collections.impl.factory.primitive.IntSets
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Paths

internal class Automaton<F : Any>(val finals: List<F>) : Serializable, BufferBasedCollection {
    companion object {
        private const val serialVersionUID: Long = 1L

        const val EPSILON = '\u0000'

        /**
         * Reusable to avoid allocation
         */
        private val createIntSet: Function0<MutableIntSet> = { IntSets.mutable.empty() }
    }

    var startState = -1

    private val transitions = TransitionSet()
    private var finalIndices = StaticBitSet.IntStaticBitSetMap(
            finals.size)

    fun run(query: String): Iterator<F>? {
        var state = startState
        for (c in query) {
            // if we sort transitions we could binary search here, but it's probably not worth it for the maybe
            // max 50 transitions we accumulate at one state
            var next = -1
            transitions.forTransitions(state) { ch, to ->
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
        transitions.forTransitions(from) { ch, dest ->
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
        val builder = dfa.stateBuilder()
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
            dfa.setFinals(builder.state, finals)
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
        transitions.pruneDead(finalIndices.keySet())
    }

    fun compact() {
        transitions.compact()
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
            for (state in 0 until transitions.size) {
                wr.appendln("$state [shape=${if (finalIndices.containsKey(state)) "doublecircle" else "circle"}];")
                transitions.forTransitions(state) { ch, to ->
                    wr.appendln("$state -> $to [label=\"${if (ch == EPSILON) 'ε' else ch}\"];")
                }
            }
            wr.appendln("}")
        }
    }

    fun stateBuilder() = transitions.StateBuilder()

    fun setFinal(state: Int, index: Int) {
        val set = StaticBitSet(finals.size)
        set.set(index)
        setFinals(state, set)
    }

    fun setFinals(state: Int, finals: StaticBitSet) {
        finalIndices.put(state, finals)
    }

    fun externalize(allocator: LargeByteBufferAllocator) {
        transitions.externalize(allocator)
        finalIndices.externalize(allocator)
    }

    override fun close() {
        transitions.close()
        finalIndices.close()
    }
}