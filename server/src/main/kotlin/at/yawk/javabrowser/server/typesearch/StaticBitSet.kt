package at.yawk.javabrowser.server.typesearch

import at.yawk.numaec.BufferBasedCollection
import at.yawk.numaec.LargeByteBufferAllocator
import at.yawk.numaec.LinearHashMapConfig
import at.yawk.numaec.MutableIntByteLinearHashMapFactory
import at.yawk.numaec.MutableIntIntLinearHashMapFactory
import at.yawk.numaec.MutableIntLongLinearHashMapFactory
import at.yawk.numaec.MutableIntShortLinearHashMapFactory
import at.yawk.numaec.MutableLongBufferListFactory
import org.eclipse.collections.api.set.primitive.IntSet
import org.eclipse.collections.impl.factory.primitive.IntByteMaps
import org.eclipse.collections.impl.factory.primitive.IntIntMaps
import org.eclipse.collections.impl.factory.primitive.IntLongMaps
import org.eclipse.collections.impl.factory.primitive.IntShortMaps
import org.eclipse.collections.impl.factory.primitive.LongLists
import org.eclipse.collections.impl.factory.primitive.ObjectIntMaps
import java.io.Serializable

private val lhtConfig = LinearHashMapConfig.builder()
        .bucketSize(4096)
        .regionSize(64)
        .dontStoreHash()
        .build()

internal class StaticBitSet private constructor(private val data: LongArray) {
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

    class IntStaticBitSetMapImpl8(private val memberCapacity: Int) : IntStaticBitSetMap() {
        var data = IntByteMaps.mutable.empty()!!

        fun createBitSet(value: Byte): StaticBitSet {
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
            data = MutableIntByteLinearHashMapFactory.withAllocatorAndConfig(allocator, lhtConfig).ofAll(data)
        }
    }

    class IntStaticBitSetMapImpl16(private val memberCapacity: Int) : IntStaticBitSetMap() {
        var data = IntShortMaps.mutable.empty()!!

        fun createBitSet(value: Short): StaticBitSet {
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
            data = MutableIntShortLinearHashMapFactory.withAllocatorAndConfig(allocator, lhtConfig).ofAll(data)
        }
    }

    class IntStaticBitSetMapImpl32(private val memberCapacity: Int) : IntStaticBitSetMap() {
        var data = IntIntMaps.mutable.empty()!!

        fun createBitSet(value: Int): StaticBitSet {
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
            data = MutableIntIntLinearHashMapFactory.withAllocatorAndConfig(allocator, lhtConfig).ofAll(data)
        }
    }

    class IntStaticBitSetMapImpl64(private val memberCapacity: Int) : IntStaticBitSetMap() {
        var data = IntLongMaps.mutable.empty()!!

        fun createBitSet(value: Long): StaticBitSet {
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
            data = MutableIntLongLinearHashMapFactory.withAllocatorAndConfig(allocator, lhtConfig).ofAll(data)
        }
    }

    class IntStaticBitSetMapImplGeneric(private val memberCapacity: Int) : IntStaticBitSetMap() {
        private var data = LongLists.mutable.empty()
        var indices = IntIntMaps.mutable.empty()!!

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
            indices = MutableIntIntLinearHashMapFactory.withAllocatorAndConfig(allocator, lhtConfig).ofAll(indices)
        }
    }
}