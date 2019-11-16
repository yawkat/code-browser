package at.yawk.javabrowser.server

import com.google.common.collect.Iterables
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.eclipse.collections.api.IntIterable
import org.eclipse.collections.api.block.function.Function0
import org.eclipse.collections.api.list.primitive.IntList
import org.eclipse.collections.api.list.primitive.LongList
import org.eclipse.collections.api.map.primitive.MutableCharObjectMap
import org.eclipse.collections.api.map.primitive.MutableLongIntMap
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap
import org.eclipse.collections.api.set.primitive.MutableIntSet
import org.eclipse.collections.impl.factory.primitive.CharLongMaps
import org.eclipse.collections.impl.factory.primitive.CharObjectMaps
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.IntObjectMaps
import org.eclipse.collections.impl.factory.primitive.IntSets
import org.eclipse.collections.impl.factory.primitive.LongIntMaps
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import java.nio.file.Files
import java.nio.file.Paths
import java.util.BitSet
import java.util.concurrent.atomic.AtomicInteger

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
        i++
    }
}

private inline fun LongList.forEachKt(f: (Long) -> Unit) {
    for (i in 0 until size()) {
        f(this[i])
    }
}

private inline fun IntIterable.forEachItr(f: (Int) -> Unit) {
    val itr = intIterator()
    while (itr.hasNext()) {
        f(itr.next())
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

private class BitSetHash private constructor(hash: SipHash) {
    private companion object {
        private fun hash(set: BitSet): SipHash {
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
                i++
            }
            if (last != -1) {
                hash.feed(last.toLong())
            }
            return hash
        }

        private fun hash(set: IntIterable): SipHash {
            val hash = SipHash()

            var last = -1
            set.forEachItr { i ->
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
            return hash
        }
    }

    private val r1: Long = hash.finish1()
    private val r2: Long = hash.finish2()

    constructor(set: BitSet) : this(hash(set))
    constructor(list: IntList) : this(hash(list))

    override fun equals(other: Any?) = other is BitSetHash && other.r1 == r1 && other.r2 == r2
    override fun hashCode() = r1.toInt()
}

private class IntSetBuilder(private val max: Int) {
    private companion object {
        private const val NEXT_SINGLE = -1
        private const val USING_INT_SET = -2
        private const val USING_BIT_SET = -3

        private const val HASH_THRESHOLD = 1000
    }

    private val maxIntSetSize: Int
        get() = max / 32

    var size: Int = 0
        private set

    var single: Int = NEXT_SINGLE
    var intSet: MutableIntSet? = null
    var bitSet: BitSet? = null

    fun add(v: Int) {
        size++
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
                intSet = IntSets.mutable.of(single, v)
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
            USING_INT_SET -> {
                if (intSet!!.size() > HASH_THRESHOLD) {
                    return BitSetHash(intSet!!.toSortedList())
                } else {
                    return intSet!!
                }
            }
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

private const val INITIAL_CHUNK_SIZE = 1024

class IndexAutomaton<V : Any>(
        values: Iterable<V>,
        components: (V) -> List<String>,
        jumps: Int
) {
    private val chunks: List<Chunk>

    init {
        chunks = runBlocking(context = Dispatchers.Default) {
            println("Building initial NFAs")

            val chunkCount = AtomicInteger()

            var chunks: List<Deferred<Chunk>> = Iterables.partition(values, INITIAL_CHUNK_SIZE).map { initChunk ->
                async {
                    Chunk.fromInput(initChunk, components, jumps).also {
                        println("Loaded chunk ${chunkCount.incrementAndGet()} with ${it.dfa.size} nodes")
                    }
                }
            }

            while (false && chunks.size > 1) {
                chunks = Iterables.partition(chunks, 2).map { part ->
                    if (part.size == 1) {
                        part[0]
                    } else {
                        async {
                            val c1 = part[0].await()
                            val c2 = part[1].await()
                            Chunk.consolidate(c1, c2).also {
                                println("Consolidated chunks with ${c1.dfa.size + c2.dfa.size} -> ${it.dfa.size} (now ${chunkCount.decrementAndGet()} chunks)")
                            }
                        }
                    }
                }
            }
            chunks.map { it.await() }
        }
    }

    fun run(s: String): List<V> {
        @Suppress("UNCHECKED_CAST")
        return chunks.flatMap { it.dfa.run(it.start, s) as Collection<V> }
    }

    private class Chunk private constructor(val dfa: Automaton, val start: Int) {
        companion object {
            fun <V : Any> fromInput(values: List<V>, components: (V) -> List<String>, jumps: Int): Chunk {
                val valuesPadded: MutableList<Any> = values.toMutableList()
                while (valuesPadded.size % 64 != 0) valuesPadded.add(Any())

                val nfa = Automaton(valuesPadded)
                val roots = IntLists.mutable.empty()
                for ((index, value) in values.withIndex()) {
                    roots.add(NfaBuilder(nfa, index, components(value), jumps).root)
                }
                val initialState = nfa.createState()
                roots.forEach { nfa.addTransition(initialState, EPSILON, it) }

                nfa.pruneDead()

                val (dfa, start) = nfa.toDfa(initialState)
                return Chunk(dfa, start)
            }

            fun consolidate(chunk1: Chunk, chunk2: Chunk): Chunk {
                val (merged, start) = Automaton.or(
                        dfa1 = chunk1.dfa,
                        dfa1Start = chunk1.start,
                        dfa2 = chunk2.dfa,
                        dfa2Start = chunk2.start
                )

                return Chunk(merged, start)
            }

        }
    }

    private class NfaBuilder(
            val nfa: Automaton,
            val finalIndex: Int,
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

                var s1: Int = -1
                var s2: Int = -1
                var s3: Int = -1
                if (!lastInComponent ||
                        (lastComponent && request.stringIndex == components[request.componentsIndex].length - 1)) {
                    // normal step
                    s1 = getState(StateRequest(
                            componentsIndex = request.componentsIndex,
                            stringIndex = request.stringIndex + 1,
                            remainingJumps = request.remainingJumps,
                            justJumped = false
                    ))
                }
                if (!lastComponent) {
                    if (lastInComponent) {
                        // step without jump to next component
                        s2 = getState(StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps = request.remainingJumps,
                                justJumped = false
                        ))
                    } else if (request.remainingJumps > 0) {
                        // jump to next component
                        s3 = getState(StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps = request.remainingJumps - 1,
                                justJumped = true
                        ))
                    }
                }
                if (s1 != -1) nfa.addTransition(state, components[request.componentsIndex][request.stringIndex], s1)
                if (s2 != -1) nfa.addTransition(state, components[request.componentsIndex][request.stringIndex], s2)
                if (s3 != -1) nfa.addTransition(state, EPSILON, s3)
                if (request.remainingJumps == 0 && !request.justJumped) {
                    nfa.markFinal(state, finalIndex)
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

    private class Automaton(private val finals: List<Any>) {
        companion object {
            private const val NO_TRANSITIONS = -1
            private const val REJECT = -1
            private const val TRANSITIONS_END = -1L

            private fun cartesian(dfa1State: Int, dfa2State: Int): Long =
                    (dfa1State.toLong() shl 32) or (dfa2State.toLong() and 0xffffffffL)

            private fun state1(cartesian: Long): Int = (cartesian shr 32).toInt()
            private fun state2(cartesian: Long): Int = cartesian.toInt()

            private fun orImpl(
                    to: Automaton,
                    dfa1: Automaton,
                    dfa1State: Int,
                    dfa2: Automaton,
                    dfa2State: Int,
                    cache: MutableLongIntMap
            ): Int {
                val key = cartesian(dfa1State, dfa2State)
                var state = cache.getIfAbsent(key, -1)
                if (state == -1) {
                    state = to.createState()
                    cache.put(key, state)

                    val transitionStates = LongLists.mutable.empty()
                    if (dfa1State != REJECT && dfa2State != REJECT) {
                        val transitions = CharLongMaps.mutable.empty()
                        dfa1.forTransitions(dfa1State) { chr, dest1 ->
                            transitions.put(chr, cartesian(dest1, REJECT))
                        }
                        dfa2.forTransitions(dfa2State) { chr, dest2 ->
                            // on -1, this is exactly REJECT, so we don't need to check if the key existed.
                            val dest1 = state1(transitions.getIfAbsent(chr, -1))
                            transitions.put(chr, cartesian(dest1, dest2))
                        }
                        transitions.forEachKeyValue { chr, cartesian ->
                            val dest1 = state1(cartesian)
                            val dest2 = state2(cartesian)
                            transitionStates.add(encodeTransition(chr, orImpl(
                                    to = to,
                                    dfa1 = dfa1,
                                    dfa1State = dest1,
                                    dfa2 = dfa2,
                                    dfa2State = dest2,
                                    cache = cache
                            )))
                        }
                    } else if (dfa1State != REJECT) {
                        dfa1.forTransitions(dfa1State) { chr, dest1 ->
                            transitionStates.add(encodeTransition(chr, orImpl(
                                    to = to,
                                    dfa1 = dfa1,
                                    dfa1State = dest1,
                                    dfa2 = dfa2,
                                    dfa2State = REJECT,
                                    cache = cache
                            )))
                        }
                    } else if (dfa2State != REJECT) {
                        dfa2.forTransitions(dfa2State) { chr, dest2 ->
                            transitionStates.add(encodeTransition(chr, orImpl(
                                    to = to,
                                    dfa1 = dfa1,
                                    dfa1State = REJECT,
                                    dfa2 = dfa2,
                                    dfa2State = dest2,
                                    cache = cache
                            )))
                        }
                    }
                    transitionStates.forEachKt { trans -> to.addTransition(state, trans) }

                    val final1 = if (dfa1State == REJECT) null else dfa1.finalsByState[dfa1State]
                    val final2 = if (dfa2State == REJECT) null else dfa2.finalsByState[dfa2State]
                    if (final1 != null || final2 != null) {
                        to.finalsByState.put(state,
                                (final1 ?: StaticBitSet(dfa1.finals.size))
                                        .concat(final2 ?: StaticBitSet(dfa2.finals.size)))
                    }
                }
                return state
            }

            fun or(dfa1: Automaton, dfa1Start: Int, dfa2: Automaton, dfa2Start: Int): Pair<Automaton, Int> {
                val to = Automaton(dfa1.finals + dfa2.finals)
                val st = orImpl(
                        to = to,
                        dfa1 = dfa1,
                        dfa1State = dfa1Start,
                        dfa2 = dfa2,
                        dfa2State = dfa2Start,
                        cache = LongIntMaps.mutable.empty()
                )
                return to to st
            }
        }

        private val transitionIndices = IntLists.mutable.empty()
        private val transitions = LongLists.mutable.empty()

        private var currentModifyingState = -1

        private val finalsByState = IntObjectMaps.mutable.empty<StaticBitSet>()

        val size: Int
            get() = transitionIndices.size()
        private val createIntSetBuilder = Function0 { IntSetBuilder(size) }

        fun createState(): Int {
            transitionIndices.add(NO_TRANSITIONS)
            return transitionIndices.size() - 1
        }

        fun addTransition(from: Int, transition: Long) {
            if (currentModifyingState != from) {
                transitions.add(TRANSITIONS_END)

                currentModifyingState = from
                if (transitionIndices[from] != NO_TRANSITIONS) throw IllegalStateException()
                transitionIndices[from] = transitions.size()
            }
            transitions.add(transition)
        }

        fun addTransition(from: Int, symbol: Char, to: Int) {
            addTransition(from, encodeTransition(symbol, to))
        }

        fun markFinal(state: Int, finalIndex: Int) {
            val bs = StaticBitSet(finals.size)
            bs.set(finalIndex)
            finalsByState.put(state, bs)
        }

        private inline fun forTransitions(from: Int, f: (Char, Int) -> Unit) {
            var i = transitionIndices[from]
            if (i == NO_TRANSITIONS) return
            while (i < transitions.size() && transitions[i] != TRANSITIONS_END) {
                val transition = transitions[i++]
                f(decodeTransitionSymbol(transition), decodeTransitionTarget(transition))
            }
        }

        private fun recordTransitions(targets: MutableCharObjectMap<IntSetBuilder>, fromKey: Int) {
            forTransitions(fromKey) { symbol, to ->
                if (symbol == EPSILON) {
                    recordTransitions(targets, to)
                } else {
                    targets.getIfAbsentPut(symbol, createIntSetBuilder).add(to)
                }
            }
        }

        private fun toDfa(dfa: Automaton, dfaStates: MutableObjectIntMap<Any>, key: IntSetBuilder): Int {
            val hash = key.build()
            var dfaState = dfaStates.getIfAbsent(hash, -1)
            if (dfaState == -1) {
                dfaState = dfa.createState()
                if (dfa.size % 1 == 0) {
                    //println("DFA size: ${dfa.size}")
                }
                dfaStates.put(hash, dfaState)
                val targets = CharObjectMaps.mutable.empty<IntSetBuilder>()
                var final: StaticBitSet? = null
                key.forEach {
                    recordTransitions(targets, it)
                    finalsByState[it]?.let { f ->
                        if (final == null) {
                            final = StaticBitSet(dfa.finals.size)
                        }
                        final!!.orFrom(f)
                    }
                }
                val dfaTargets = LongLists.mutable.empty()
                targets.forEachKeyValue { k, v ->
                    dfaTargets.add(encodeTransition(k, toDfa(dfa, dfaStates, v)))
                }
                dfaTargets.forEachKt {
                    dfa.addTransition(dfaState, it)
                }
                if (final != null) {
                    dfa.finalsByState.put(dfaState, final)
                }
            }
            return dfaState
        }

        fun toDfa(initial: Int): Pair<Automaton, Int> {
            val dfa = Automaton(this.finals)
            val initialState = IntSetBuilder(size)
            initialState.add(initial)
            val f = toDfa(dfa, ObjectIntMaps.mutable.empty(), initialState)
            return dfa to f
        }

        fun run(state: Int, s: String, index: Int = 0): Collection<Any> {
            if (index == s.length) {
                return finalsByState[state]?.let {
                    finals.withIndex()
                            .filter { (ix, _) -> it[ix] }
                            .map { (_, item) -> item }
                } ?: emptyList()
            }
            forTransitions(state) { symbol, target ->
                if (s[index] == symbol) {
                    return run(target, s, index + 1)
                }
            }
            return emptyList()
        }

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
                            finalsByState.containsKey(dest)
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

        fun dumpDot(name: String = "automaton.dot") {
            Files.newBufferedWriter(Paths.get(name)).use { wr ->
                wr.appendln("digraph g {")
                for (state in 0 until transitionIndices.size()) {
                    wr.appendln("$state [shape=${if (finalsByState.containsKey(state)) "doublecircle" else "circle"}];")
                    forTransitions(state) { ch, to ->
                        wr.appendln("$state -> $to [label=\"${if (ch == EPSILON) 'ε' else ch}\"];")
                    }
                }
                wr.appendln("}")
            }
        }
    }
}