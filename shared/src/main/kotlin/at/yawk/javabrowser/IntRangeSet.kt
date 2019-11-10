package at.yawk.javabrowser

import org.eclipse.collections.impl.factory.primitive.LongLists
import kotlin.math.max

/**
 * @author yawkat
 */
class IntRangeSet : Iterable<IntRange> {
    private val ranges = LongLists.mutable.empty()

    private fun encode(startInclusive: Int, endExclusive: Int) =
            (startInclusive.toLong() shl 32) or endExclusive.toLong()

    private fun decodeStart(value: Long) = (value shr 32).toInt()
    private fun decodeEnd(value: Long) = value.toInt()

    private fun siftAbove(i: Int) {
        while (i + 1 < ranges.size()) {
            val hereEnd = decodeEnd(ranges[i])
            val nextStart = decodeStart(ranges[i + 1])
            if (hereEnd >= nextStart) {
                ranges[i] = encode(decodeStart(ranges[i]), max(hereEnd, decodeEnd(ranges[i + 1])))
                ranges.removeAtIndex(i + 1)
            } else {
                break // done!
            }
        }
    }

    private fun siftBelow(i_: Int) {
        var i = i_
        while (i > 0) {
            val hereStart = decodeStart(ranges[i])
            val previousEnd = decodeEnd(ranges[i - 1])
            if (hereStart <= previousEnd) {
                ranges[i - 1] = encode(decodeStart(ranges[i - 1]), decodeEnd(ranges[i]))
                ranges.removeAtIndex(i)
                i--
            } else {
                break // done!
            }
        }
    }

    private fun checkIndex(index: Int) {
        if (index < 0) throw IllegalArgumentException()
    }

    private fun checkRange(startInclusive: Int, endExclusive: Int) {
        checkIndex(startInclusive)
        if (endExclusive < startInclusive) throw IllegalArgumentException()
    }

    fun add(startInclusive: Int, endExclusive: Int) {
        checkRange(startInclusive, endExclusive)
        if (encloses(startInclusive, endExclusive)) return

        val value = encode(startInclusive, endExclusive)
        // binarySearch is always negative or else encloses would have returned true
        val insertionPos = ranges.binarySearch(value).inv()
        ranges.addAtIndex(insertionPos, value)
        siftAbove(insertionPos)
        siftBelow(insertionPos)
    }

    fun contains(value: Int): Boolean {
        return encloses(value, value + 1)
    }

    fun encloses(startInclusive: Int, endExclusive: Int): Boolean {
        checkRange(startInclusive, endExclusive)
        if (startInclusive == endExclusive) return true // empty range

        val insertionPoint = ranges.binarySearch(encode(startInclusive + 1, 0)).inv()
        return insertionPoint > 0 && decodeEnd(ranges[insertionPoint - 1]) >= endExclusive
    }

    fun intersects(startInclusive: Int, endExclusive: Int): Boolean {
        checkRange(startInclusive, endExclusive)
        if (startInclusive == endExclusive) return false // empty range
        if (encloses(startInclusive, endExclusive)) return true
        if (contains(startInclusive)) return true

        val insertionPointStart = ranges.binarySearch(encode(startInclusive, 0))
        val insertionPointEnd = ranges.binarySearch(encode(endExclusive, 0))
        return insertionPointStart != insertionPointEnd
    }

    private inline fun forEachRange0(f: (Int, Int) -> Unit) {
        for (i in 0 until ranges.size()) {
            f(decodeStart(ranges[i]), decodeEnd(ranges[i]))
        }
    }

    fun forEachRange(f: (Int, Int) -> Unit) {
        forEachRange0(f)
    }

    override fun iterator() = object : Iterator<IntRange> {
        var i = 0

        override fun hasNext() = i < ranges.size()

        override fun next(): IntRange {
            val range = decodeStart(ranges[i]) until decodeEnd(ranges[i])
            i++
            return range
        }
    }

    override fun toString(): String {
        val builder = StringBuilder("{")
        forEachRange0 { start, end -> builder.append('[').append(start).append(',').append(end).append("), ") }
        if (!ranges.isEmpty) {
            builder.setLength(builder.length - 2) // remove trailing comma
        }
        builder.append('}')
        return builder.toString()
    }

    override fun equals(other: Any?) = other is IntRangeSet && other.ranges == this.ranges
    override fun hashCode() = ranges.hashCode()
}