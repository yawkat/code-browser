package at.yawk.javabrowser.server

import com.google.common.collect.ImmutableSet
import org.eclipse.collections.api.list.primitive.LongList
import org.eclipse.collections.api.list.primitive.MutableLongList
import org.eclipse.collections.api.map.primitive.CharObjectMap
import org.eclipse.collections.api.map.primitive.MutableCharObjectMap
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap
import org.eclipse.collections.api.set.primitive.MutableIntSet
import org.eclipse.collections.impl.factory.primitive.CharObjectMaps
import org.eclipse.collections.impl.factory.primitive.IntObjectMaps
import org.eclipse.collections.impl.factory.primitive.IntSets
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import org.eclipse.collections.impl.map.mutable.primitive.CharObjectHashMap
import java.util.BitSet

/**
 * @author yawkat
 */
private const val EPSILON = '\u0000'

private fun encodeTransition(symbol: Char, to: Int): Long {
    return (symbol.toLong() shl 32) or to.toLong()
}

private fun decodeTransitionSymbol(transition: Long): Char {
    return (transition ushr 32).toChar()
}

private fun decodeTransitionTarget(transition: Long): Int {
    return transition.toInt()
}

private inline fun BitSet.forEach(f: (Int) -> Unit) {
    var i = 0
    while (true) {
        i = nextSetBit(i)
        if (i == -1) return
        f(i)
    }
}

private inline fun LongList.forEachKt(f: (Long) -> Unit) {
    for (i in 0 until size()) {
        f(this[i])
    }
}

private class SipHash {
    companion object {
        private const val c = 2
        private const val d = 4
    }

    var v0 = 0x736f6d6570736575
    var v1 = 0x646f72616e646f6d
    var v2 = 0x6c7967656e657261
    var v3 = 0x7465646279746573L

    fun sipRound(itr: Int) {
        for (i in 0 until itr) {
            v0 += v1
            v2 += v3
            v1 = java.lang.Long.rotateLeft(v1, 13)
            v3 = java.lang.Long.rotateLeft(v3, 16)
            v1 = v1 xor v0
            v3 = v3 xor v2
            v0 = java.lang.Long.rotateLeft(v0, 32)
            v2 += v1
            v0 += v3
            v1 = java.lang.Long.rotateLeft(v1, 17)
            v3 = java.lang.Long.rotateLeft(v3, 21)
            v1 = v1 xor v2
            v3 = v3 xor v0
            v2 = java.lang.Long.rotateLeft(v2, 32)
        }
    }

    fun feed(v: Long) {
        v3 = v3 xor v
        sipRound(c)
        v0 = v0 xor v
    }

    fun finish1(): Long {
        // don't include length

        v2 = v2 xor 0xee // 128 bit outlen
        sipRound(d)
        return v0 xor v1 xor v2 xor v3
    }

    fun finish2(): Long {
        // don't include length

        v1 = v1 xor 0xdd
        sipRound(d)
        return v0 xor v1 xor v2 xor v3
    }
}

private class BitSetHash(set: BitSet) {
    private val r1: Long
    private val r2: Long

    init {
        val hash = SipHash()

        var last = -1
        var i = 0
        while (true) {
            i = set.nextSetBit(i)
            if (i == -1) break
            if (last == -1) {
                last = i
            } else {
                hash.feed((last.toLong() shl 32) or i.toLong())
                last = -1
            }
        }
        if (last != -1) {
            hash.feed(last.toLong())
        }
        r1 = hash.finish1()
        r2 = hash.finish2()
    }

    override fun equals(other: Any?) = other is BitSetHash && other.r1 == r1 && other.r2 == r2
    override fun hashCode() = r1.toInt()
}

private class IntSetBuilder(private val max: Int) {
    private companion object {
        private const val NEXT_SINGLE = -1
        private const val USING_INT_SET = -2
        private const val USING_BIT_SET = -3
    }

    private val maxIntSetSize: Int
        get() = max / 32

    var single: Int = NEXT_SINGLE
    var intSet: MutableIntSet? = null
    var bitSet: BitSet? = null

    fun add(v: Int) {
        when (single) {
            NEXT_SINGLE -> single = v
            USING_INT_SET -> {
                intSet!!.add(v)
                if (intSet!!.size() > maxIntSetSize) {
                    bitSet = BitSet(max)
                    intSet!!.forEach(bitSet!!::set)
                    intSet = null
                    single = USING_BIT_SET
                }
            }
            USING_BIT_SET -> bitSet!!.set(v)
            else -> {
                intSet = IntSets.mutable.empty()
                single = USING_INT_SET
            }
        }
    }

    inline fun forEach(crossinline f: (Int) -> Unit) {
        when (single) {
            NEXT_SINGLE -> {} // empty
            USING_INT_SET -> intSet!!.forEach { f(it) }
            USING_BIT_SET -> bitSet!!.forEach(f)
            else -> f(single)
        }
    }

    fun build(): Any {
        return when (single) {
            NEXT_SINGLE -> -1 // empty
            USING_INT_SET -> intSet!!
            USING_BIT_SET -> BitSetHash(bitSet!!)
            else -> single
        }
    }
}

private fun getBitSetHash(set: BitSet): Any {
    if (set.length() > 8 * 500_000) {
        return BitSetHash(set)
    } else {
        return set
    }
}

class IndexAutomaton<V : Any>(
        values: Iterable<V>,
        components: (V) -> List<String>,
        jumps: Int
) {
    private val dfa: Automaton
    private val first: Int

    init {
        println("Building initial NFA")
        val nfa = Automaton()
        val initialState = nfa.createState()
        for ((i, value) in values.withIndex()) {
            nfa.addTransition(initialState, EPSILON, NfaBuilder(nfa, value, components(value), jumps).root)
            if (i % 1000 == 0) {
                println("$value -> ${nfa.size}")
            }
        }
        //println("Eliminating epsilon transitions")
        //initialState.eliminateEpsilon()
        println("Transforming to dfa")
        val (dfa, start) = nfa.toDfa(initialState)
        this.dfa = dfa
        this.first = start
    }

    fun run(s: String): List<V> {
        @Suppress("UNCHECKED_CAST")
        return dfa.run(first, s) as List<V>
    }

    private class NfaBuilder(
            val nfa: Automaton,
            val final: Any,
            val components: List<String>,
            totalJumps: Int
    ) {
        private val cache = ObjectIntMaps.mutable.empty<StateRequest>()
        //val root = NfaState()
        val root = nfa.createState()

        init {
            for (componentIndex in components.indices) {
                nfa.addTransition(
                        root,
                        EPSILON,
                        getState(StateRequest(
                                componentsIndex = componentIndex,
                                stringIndex = 0,
                                remainingJumps = totalJumps,
                                justJumped = false
                        )))
            }
        }

        private fun getState(request: StateRequest): Int {
            var state = cache.getIfAbsent(request, -1)
            if (state == -1) {
                state = nfa.createState()
                cache.put(request, state)

                val lastComponent = request.componentsIndex >= components.size - 1
                val lastInComponent = request.stringIndex >= components[request.componentsIndex].length - 1
                if (!lastInComponent) {
                    // normal step
                    val symbol = components[request.componentsIndex][request.stringIndex]
                    nfa.addTransition(state, symbol, getState(StateRequest(
                            componentsIndex = request.componentsIndex,
                            stringIndex = request.stringIndex + 1,
                            remainingJumps = request.remainingJumps,
                            justJumped = false
                    )))
                }
                if (!lastComponent) {
                    if (lastInComponent) {
                        // step without jump to next component
                        nfa.addTransition(state, EPSILON, getState(StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps = request.remainingJumps,
                                justJumped = false
                        )))
                    } else if (request.remainingJumps > 0) {
                        // jump to next component
                        nfa.addTransition(state, EPSILON, getState(StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps = request.remainingJumps - 1,
                                justJumped = true
                        )))
                    }
                }
                if (request.remainingJumps == 0 && !request.justJumped) {
                    nfa.addFinal(state, final)
                }
            }
            return state
        }

        private data class StateRequest(
                val componentsIndex: Int,
                val stringIndex: Int,
                val remainingJumps: Int,
                val justJumped: Boolean
        )
    }

    private class Automaton {
        private val transitions = ArrayList<MutableLongList>()
        private val finals = IntObjectMaps.mutable.empty<MutableCollection<Any>>()

        val size: Int
            get() = transitions.size

        fun createState(): Int {
            transitions.add(LongLists.mutable.empty())
            return transitions.size - 1
        }

        fun addTransition(from: Int, symbol: Char, to: Int) {
            transitions[from].add(encodeTransition(symbol, to))
        }

        fun addFinal(state: Int, final: Any) {
            finals.getIfAbsentPut(state) { ArrayList() }.add(final)
        }

        fun getFinals(state: Int) = finals[state] ?: emptyList<Any>()

        private fun recordTransitions(targets: MutableCharObjectMap<IntSetBuilder>, fromKey: Int) {
            transitions[fromKey].forEachKt { transition ->
                val symbol = decodeTransitionSymbol(transition)
                val to = decodeTransitionTarget(transition)
                if (symbol == EPSILON) {
                    recordTransitions(targets, to)
                } else {
                    targets.getIfAbsentPut(symbol) { IntSetBuilder(transitions.size) }.add(to)
                }
            }
        }

        private fun toDfa(dfa: Automaton, dfaStates: MutableObjectIntMap<Any>, key: IntSetBuilder): Int {
            val hash = key.build()
            var dfaState = dfaStates.getIfAbsent(hash, -1)
            if (dfaState == -1) {
                dfaState = dfa.createState()
                dfaStates.put(hash, dfaState)
                val targets = CharObjectMaps.mutable.empty<IntSetBuilder>()
                val final = ImmutableSet.builder<Any>()
                key.forEach {
                    recordTransitions(targets, it)
                    final.addAll(getFinals(it))
                }
                targets.forEachKeyValue { k, v ->
                    dfa.addTransition(dfaState, k, toDfa(dfa, dfaStates, v))
                }
                val prev = dfa.finals.put(dfaState, final.build())
                if (prev != null) throw AssertionError()
            }
            return dfaState
        }

        fun toDfa(initial: Int): Pair<Automaton, Int> {
            val dfa = Automaton()
            val initialState = IntSetBuilder(transitions.size)
            initialState.add(initial)
            val f = toDfa(dfa, ObjectIntMaps.mutable.empty(), initialState)
            return dfa to f
        }

        fun run(state: Int, s: String, index: Int = 0): Collection<Any> {
            if (index == s.length) return finals[state] ?: emptyList()
            transitions[state].forEachKt { transition ->
                if (s[index] == decodeTransitionSymbol(transition)) {
                    return run(decodeTransitionTarget(transition), s, index + 1)
                }
            }
            return emptyList()
        }
    }
}