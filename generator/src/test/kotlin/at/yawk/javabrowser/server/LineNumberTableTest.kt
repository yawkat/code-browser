package at.yawk.javabrowser.server

import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class LineNumberTableTest {
    @Test
    fun simple() {
        val table = LineNumberTable("one\ntwo\nthree\n".trimIndent())
        Assert.assertEquals(table.lineAt(0), 1)
        Assert.assertEquals(table.lineAt(1), 1)
        Assert.assertEquals(table.lineAt(2), 1)
        Assert.assertEquals(table.lineAt(3), 1)
        Assert.assertEquals(table.lineAt(4), 2)
        Assert.assertEquals(table.lineAt(5), 2)
        Assert.assertEquals(table.lineAt(6), 2)
        Assert.assertEquals(table.lineAt(7), 2)
        Assert.assertEquals(table.lineAt(8), 3)
    }
}