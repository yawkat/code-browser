package at.yawk.javabrowser.server

import org.testng.Assert
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import java.io.StringWriter

/**
 * @author yawkat
 */
class EscaperTest {
    @DataProvider
    fun testStrings(): Array<Array<Any>> = arrayOf(
            arrayOf<Any>("abc<def>ghi&jkl'mno\"pqr", "abc&lt;def&gt;ghi&amp;jkl&#39;mno&quot;pqr")
    )

    @Test(dataProvider = "testStrings")
    fun test(unescaped: String, escaped: String) {
        Assert.assertEquals(Escaper.HTML.escape(unescaped), escaped)
    }

    @Test(dataProvider = "testStrings")
    fun testWriter(unescaped: String, escaped: String) {
        val writer = StringWriter()
        Escaper.HTML.escape(writer, unescaped)
        Assert.assertEquals(writer.toString(), escaped)
    }

    @Test(dataProvider = "testStrings")
    fun testRange(unescaped: String, escaped: String) {
        Assert.assertEquals(Escaper.HTML.escape("fo'<'o" + unescaped + "bar", start = 6, end = unescaped.length + 6),
                escaped)
    }
}