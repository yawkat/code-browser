package at.yawk.javabrowser.server

import at.yawk.numaec.BTreeConfig
import at.yawk.numaec.BufferBasedCollection
import at.yawk.numaec.LargeByteBufferAllocator
import at.yawk.numaec.MutableIntBufferListFactory
import at.yawk.numaec.MutableIntByteBTreeMapFactory
import at.yawk.numaec.MutableIntIntBTreeMapFactory
import at.yawk.numaec.MutableIntLongBTreeMapFactory
import at.yawk.numaec.MutableIntShortBTreeMapFactory
import at.yawk.numaec.MutableLongBufferListFactory
import at.yawk.numaec.MutableShortBufferListFactory
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Iterators
import org.eclipse.collections.api.LongIterable
import org.eclipse.collections.api.PrimitiveIterable
import org.eclipse.collections.api.list.primitive.CharList
import org.eclipse.collections.api.list.primitive.IntList
import org.eclipse.collections.api.list.primitive.LongList
import org.eclipse.collections.api.list.primitive.MutableIntList
import org.eclipse.collections.api.list.primitive.MutableLongList
import org.eclipse.collections.api.list.primitive.MutableShortList
import org.eclipse.collections.api.list.primitive.ShortList
import org.eclipse.collections.api.map.primitive.MutableCharObjectMap
import org.eclipse.collections.api.map.primitive.MutableObjectIntMap
import org.eclipse.collections.api.set.primitive.IntSet
import org.eclipse.collections.api.set.primitive.MutableIntSet
import org.eclipse.collections.impl.factory.primitive.CharObjectMaps
import org.eclipse.collections.impl.factory.primitive.CharSets
import org.eclipse.collections.impl.factory.primitive.IntByteMaps
import org.eclipse.collections.impl.factory.primitive.IntIntMaps
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.IntLongMaps
import org.eclipse.collections.impl.factory.primitive.IntSets
import org.eclipse.collections.impl.factory.primitive.IntShortMaps
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList
import org.eclipse.collections.impl.list.mutable.primitive.ShortArrayList
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Paths
import java.util.BitSet
import java.util.Collections

private val btreeConfig = BTreeConfig.builder()
        .blockSize(4096) // 4K pages
        .regionSize(64) // allocate 256 pages at once
        .build()

/**
 * @author yawkat
 */
class IndexAutomaton<E : Any> private constructor(private val automata: List<Automaton<E>>) : Serializable, BufferBasedCollection {
    constructor(
            entries: List<E>,
            components: (E) -> List<String>,
            jumps: Int,
            /**
             * Chunk size for individual automata. Larger chunk size leads to faster querying at cost of memory consumption.
             *
             * Rough measurements: https://s.yawk.at/l5G8Up7Z
             */
            chunkSize: Int = 512,
            storage: LargeByteBufferAllocator? = null
    ) : this(Iterables.partition(entries, chunkSize).map { chunk ->
        val nfa = Automaton(ImmutableList.copyOf(chunk)) // copy for serialization
        val root = nfa.stateBuilder()
        val cache = ObjectIntMaps.mutable.empty<StateRequest>()
        for ((i, entry) in chunk.withIndex()) {
            root.addTransition(Automaton.EPSILON, fromInput(
                    nfa = nfa,
                    cache = cache,
                    components = components(entry),
                    finalIndex = i,
                    request = StateRequest(0, 0, jumps, true)
            ))
            cache.clear()
        }
        root.finish()
        nfa.startState = root.state

        nfa.pruneDead()
        val dfa = nfa.nfaToDfa()
        dfa.compact()
        dfa.deduplicateFinals()
        if (storage != null) dfa.externalize(storage)
        dfa
    })

    fun run(query: String): Iterator<E> = Iterators.concat(
            Iterators.transform(automata.iterator()) { it!!.run(query) ?: Collections.emptyIterator<E>() })

    override fun close() {
        automata.forEach { it.close() }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

    private data class StateRequest(
            val componentsIndex: Int,
            val stringIndex: Int,
            val remainingJumps: Int,
            val justJumped: Boolean
    )

    private fun <E : Any> fromInput(
            nfa: Automaton<E>,
            cache: MutableObjectIntMap<StateRequest>,
            components: List<String>,
            finalIndex: Int,
            request: StateRequest
    ): Int {
        cache.getIfAbsent(request, -1).let { if (it != -1) return it }

        val builder = nfa.stateBuilder()
        cache.put(request, builder.state)

        val lastComponent = request.componentsIndex >= components.size - 1

        if (request.componentsIndex < components.size) {
            val lastInComponent = request.stringIndex == components[request.componentsIndex].length - 1

            // normal step
            val normalStepRequest = if (!lastInComponent)
                StateRequest(
                        componentsIndex = request.componentsIndex,
                        stringIndex = request.stringIndex + 1,
                        remainingJumps = request.remainingJumps,
                        justJumped = false
                )
            else
                StateRequest(
                        componentsIndex = request.componentsIndex + 1,
                        stringIndex = 0,
                        remainingJumps = request.remainingJumps,
                        justJumped = false
                )
            builder.addTransition(components[request.componentsIndex][request.stringIndex],
                    fromInput(nfa, cache, components, finalIndex, normalStepRequest))
        }

        if (!lastComponent) {
            if (request.remainingJumps > 0 || request.justJumped) {
                // jump to next component
                builder.addTransition(Automaton.EPSILON,
                        fromInput(nfa, cache, components, finalIndex, StateRequest(
                                componentsIndex = request.componentsIndex + 1,
                                stringIndex = 0,
                                remainingJumps =
                                if (request.justJumped) request.remainingJumps
                                else request.remainingJumps - 1,
                                justJumped = true
                        )))
            }
        }
        if (request.remainingJumps == 0 && !request.justJumped) {
            nfa.setFinal(builder.state, finalIndex)
        }
        builder.finish()
        return builder.state
    }
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

    abstract class IntStaticBitSetMap : Serializable, BufferBasedCollection {
        companion object {
            operator fun invoke(memberCapacity: Int): IntStaticBitSetMap = when {
                memberCapacity <= 8 -> IntStaticBitSetMapImpl8(memberCapacity)
                memberCapacity <= 16 -> IntStaticBitSetMapImpl16(memberCapacity)
                memberCapacity <= 32 -> IntStaticBitSetMapImpl32(memberCapacity)
                memberCapacity <= 64 -> IntStaticBitSetMapImpl64(memberCapacity)
                else -> IntStaticBitSetMapImplGeneric(memberCapacity)
            }
        }

        abstract fun put(key: Int, value: StaticBitSet)

        abstract operator fun get(key: Int): StaticBitSet?

        abstract fun keySet(): IntSet

        abstract fun containsKey(key: Int): Boolean

        open fun deduplicated(): IntStaticBitSetMap = this

        abstract fun externalize(allocator: LargeByteBufferAllocator)

        inline fun forEachKeyValue(crossinline f: (Int, StaticBitSet) -> Unit) {
            when (this) {
                is IntStaticBitSetMapImpl8 -> forEachKeyValue0(f)
                is IntStaticBitSetMapImpl16 -> forEachKeyValue0(f)
                is IntStaticBitSetMapImpl32 -> forEachKeyValue0(f)
                is IntStaticBitSetMapImpl64 -> forEachKeyValue0(f)
                is IntStaticBitSetMapImplGeneric -> forEachKeyValue0(f)
                else -> throw AssertionError()
            }
        }
    }

    private class IntStaticBitSetMapImpl8(private val memberCapacity: Int) : IntStaticBitSetMap() {
        private var data = IntByteMaps.mutable.empty()

        private fun createBitSet(value: Byte): StaticBitSet {
            val res = StaticBitSet(memberCapacity)
            res.data[0] = value.toLong()
            return res
        }

        override fun put(key: Int, value: StaticBitSet) {
            data.put(key, value.data[0].toByte())
        }

        override operator fun get(key: Int): StaticBitSet? {
            if (!data.containsKey(key)) return null
            return createBitSet(data.get(key))
        }

        override fun keySet(): IntSet = data.keySet()

        override fun containsKey(key: Int) = data.containsKey(key)

        inline fun forEachKeyValue0(crossinline f: (Int, StaticBitSet) -> Unit) {
            data.forEachKeyValue { i1, i2 ->
                f(i1, createBitSet(i2))
            }
        }

        override fun close() {
            (data as? BufferBasedCollection)?.close()
        }

        override fun externalize(allocator: LargeByteBufferAllocator) {
            data = MutableIntByteBTreeMapFactory.withAllocatorAndConfig(allocator, btreeConfig).ofAll(data)
        }
    }

    private class IntStaticBitSetMapImpl16(private val memberCapacity: Int) : IntStaticBitSetMap() {
        private var data = IntShortMaps.mutable.empty()

        private fun createBitSet(value: Short): StaticBitSet {
            val res = StaticBitSet(memberCapacity)
            res.data[0] = value.toLong()
            return res
        }

        override fun put(key: Int, value: StaticBitSet) {
            data.put(key, value.data[0].toShort())
        }

        override operator fun get(key: Int): StaticBitSet? {
            if (!data.containsKey(key)) return null
            return createBitSet(data.get(key))
        }

        override fun keySet(): IntSet = data.keySet()

        override fun containsKey(key: Int) = data.containsKey(key)

        inline fun forEachKeyValue0(crossinline f: (Int, StaticBitSet) -> Unit) {
            data.forEachKeyValue { i1, i2 ->
                f(i1, createBitSet(i2))
            }
        }

        override fun close() {
            (data as? BufferBasedCollection)?.close()
        }

        override fun externalize(allocator: LargeByteBufferAllocator) {
            data = MutableIntShortBTreeMapFactory.withAllocatorAndConfig(allocator, btreeConfig).ofAll(data)
        }
    }

    private class IntStaticBitSetMapImpl32(private val memberCapacity: Int) : IntStaticBitSetMap() {
        private var data = IntIntMaps.mutable.empty()

        private fun createBitSet(value: Int): StaticBitSet {
            val res = StaticBitSet(memberCapacity)
            res.data[0] = value.toLong()
            return res
        }

        override fun put(key: Int, value: StaticBitSet) {
            data.put(key, value.data[0].toInt())
        }

        override operator fun get(key: Int): StaticBitSet? {
            if (!data.containsKey(key)) return null
            return createBitSet(data.get(key))
        }

        override fun keySet(): IntSet = data.keySet()

        override fun containsKey(key: Int) = data.containsKey(key)

        inline fun forEachKeyValue0(crossinline f: (Int, StaticBitSet) -> Unit) {
            data.forEachKeyValue { i1, i2 ->
                f(i1, createBitSet(i2))
            }
        }

        override fun close() {
            (data as? BufferBasedCollection)?.close()
        }

        override fun externalize(allocator: LargeByteBufferAllocator) {
            data = MutableIntIntBTreeMapFactory.withAllocatorAndConfig(allocator, btreeConfig).ofAll(data)
        }
    }

    private class IntStaticBitSetMapImpl64(private val memberCapacity: Int) : IntStaticBitSetMap() {
        private var data = IntLongMaps.mutable.empty()

        private fun createBitSet(value: Long): StaticBitSet {
            val res = StaticBitSet(memberCapacity)
            res.data[0] = value
            return res
        }

        override fun put(key: Int, value: StaticBitSet) {
            data.put(key, value.data[0])
        }

        override operator fun get(key: Int): StaticBitSet? {
            if (!data.containsKey(key)) return null
            return createBitSet(data.get(key))
        }

        override fun keySet(): IntSet = data.keySet()

        override fun containsKey(key: Int) = data.containsKey(key)

        inline fun forEachKeyValue0(crossinline f: (Int, StaticBitSet) -> Unit) {
            data.forEachKeyValue { i1, i2 ->
                f(i1, createBitSet(i2))
            }
        }

        override fun close() {
            (data as? BufferBasedCollection)?.close()
        }

        override fun externalize(allocator: LargeByteBufferAllocator) {
            data = MutableIntLongBTreeMapFactory.withAllocatorAndConfig(allocator, btreeConfig).ofAll(data)
        }
    }

    private class IntStaticBitSetMapImplGeneric(private val memberCapacity: Int) : IntStaticBitSetMap() {
        private var data = LongLists.mutable.empty()
        private var indices = IntIntMaps.mutable.empty()

        override fun put(key: Int, value: StaticBitSet) {
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

        override operator fun get(key: Int): StaticBitSet? {
            val index = indices.getIfAbsent(key, -1)
            if (index == -1) return null
            val res = StaticBitSet(memberCapacity)
            for (i in res.data.indices) {
                res.data[i] = data[index + i]
            }
            return res
        }

        override fun keySet(): IntSet = indices.keySet()

        override fun containsKey(key: Int) = indices.containsKey(key)

        inline fun forEachKeyValue0(f: (Int, StaticBitSet) -> Unit) {
            val itr = indices.keysView().intIterator()
            while (itr.hasNext()) {
                val key = itr.next()
                f(key, get(key)!!)
            }
        }

        override fun deduplicated(): IntStaticBitSetMap {
            val cache = ObjectIntMaps.mutable.empty<StaticBitSet>()
            val result = IntStaticBitSetMapImplGeneric(memberCapacity)
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

        override fun close() {
            (data as? BufferBasedCollection)?.close()
            (indices as? BufferBasedCollection)?.close()
        }

        override fun externalize(allocator: LargeByteBufferAllocator) {
            data = MutableLongBufferListFactory.withAllocator(allocator).ofAll(data)
            indices = MutableIntIntBTreeMapFactory.withAllocatorAndConfig(allocator, btreeConfig).ofAll(indices)
        }
    }
}

private class TransitionSet : Serializable, BufferBasedCollection {
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
            val newTransitionIndices = ShortArrayList(oldTransitionIndices.size())
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
            stateBits + alphabet.requiredBits <= 16 -> newTransitions = ShortArrayList(oldTransitions.size())
            stateBits + alphabet.requiredBits <= 32 -> newTransitions = IntArrayList(oldTransitions.size())
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
            is IntList -> MutableIntBufferListFactory.withAllocator(allocator).ofAll(oldIndices)
            is ShortList -> MutableShortBufferListFactory.withAllocator(allocator).ofAll(oldIndices)
            else -> throw IllegalStateException()
        }
        val oldTransitions = transitions
        transitions = when (oldTransitions) {
            is LongList -> MutableLongBufferListFactory.withAllocator(allocator).ofAll(oldTransitions)
            is IntList -> MutableIntBufferListFactory.withAllocator(allocator).ofAll(oldTransitions)
            is ShortList -> MutableShortBufferListFactory.withAllocator(allocator).ofAll(oldTransitions)
            else -> throw IllegalStateException()
        }
    }

    private class Alphabet private constructor(private val items: CharList) : Serializable {
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

private class Automaton<F : Any>(val finals: List<F>) : Serializable, BufferBasedCollection {
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
    private var finalIndices = StaticBitSet.IntStaticBitSetMap(finals.size)

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