package at.yawk.javabrowser

import at.yawk.javabrowser.IntRangeSet
import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class IntRangeSetTest {
    @Test
    fun `test toString`() {
        val set = IntRangeSet()
        set.add(5, 6)
        set.add(7, 8)
        Assert.assertEquals(
                set.toString(),
                "{[5,6), [7,8)}"
        )
    }

    @Test
    fun `coalesce down`() {
        val set = IntRangeSet()
        set.add(5, 10)
        set.add(7, 15)
        Assert.assertEquals(
                set.toString(),
                "{[5,15)}"
        )
    }

    @Test
    fun `coalesce up`() {
        val set = IntRangeSet()
        set.add(7, 15)
        set.add(5, 10)
        Assert.assertEquals(
                set.toString(),
                "{[5,15)}"
        )
    }

    @Test
    fun `coalesce bidi`() {
        val set = IntRangeSet()
        set.add(5, 10)
        set.add(15, 20)
        set.add(7, 15)
        Assert.assertEquals(
                set.toString(),
                "{[5,20)}"
        )
    }

    @Test
    fun `expand`() {
        val set = IntRangeSet()
        set.add(5, 8)
        set.add(0, 9)
        Assert.assertEquals(
                set.toString(),
                "{[0,9)}"
        )
    }

    @Test
    fun `coalesce smaller`() {
        val set = IntRangeSet()
        set.add(5, 10)
        set.add(7, 8)
        Assert.assertEquals(
                set.toString(),
                "{[5,10)}"
        )
    }

    @Test
    fun contains() {
        val set = IntRangeSet()
        set.add(5, 10)
        Assert.assertTrue(set.contains(7))
        Assert.assertTrue(set.contains(5))
        Assert.assertTrue(set.contains(9))
        Assert.assertFalse(set.contains(10))
        Assert.assertFalse(set.contains(4))
        Assert.assertFalse(set.contains(0))
    }

    @Test
    fun encloses() {
        val set = IntRangeSet()
        set.add(5, 10)
        Assert.assertTrue(set.encloses(5, 10))
        Assert.assertTrue(set.encloses(6, 9))
        Assert.assertTrue(set.encloses(1, 1))
        Assert.assertFalse(set.encloses(2, 3))
        Assert.assertFalse(set.encloses(4, 6))
        Assert.assertFalse(set.encloses(9, 11))
    }

    @Test
    fun intersects() {
        val set = IntRangeSet()
        set.add(5, 10)
        Assert.assertTrue(set.intersects(5, 10))
        Assert.assertTrue(set.intersects(6, 9))
        Assert.assertTrue(set.intersects(4, 6))
        Assert.assertFalse(set.intersects(4, 5))
        Assert.assertTrue(set.intersects(9, 11))
        Assert.assertTrue(set.intersects(9, 10))
        Assert.assertFalse(set.intersects(1, 5))
        Assert.assertFalse(set.intersects(10, 11))
        Assert.assertFalse(set.intersects(11, 12))
        Assert.assertFalse(set.intersects(1, 1))
        Assert.assertTrue(set.intersects(4, 11))
        Assert.assertTrue(set.intersects(2, 15))
    }
}