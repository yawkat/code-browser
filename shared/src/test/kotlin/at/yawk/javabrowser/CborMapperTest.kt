package at.yawk.javabrowser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import org.testng.Assert
import org.testng.annotations.Test

class CborMapperTest {
    private val compressedMapper = ObjectMapper(CompressedFactory()).findAndRegisterModules()
    private val normalMapper = ObjectMapper(CBORFactory()).findAndRegisterModules()

    @Test
    fun testFieldName() {
        testFieldName("id", 1, A("irrelevant"))
        testFieldName("annotation", 1, B("irrelevant"))
        testFieldName("javadoc", 2, C("irrelevant"))
    }

    @Test
    fun testFieldValue() {
        for (s in CommonStrings.COMMON_STRINGS_1) {
            testFieldValue(s, 1)
        }
        for (s in CommonStrings.COMMON_STRINGS_2) {
            testFieldValue(s, 2)
        }
    }

    data class A(val id: String)
    data class B(val annotation: String)
    data class C(val javadoc: String)
    data class NotCompressed(val `not compressed`: String)

    private inline fun <reified C> testFieldName(s: String, expectedLength: Int, obj: C) {
        val normalBytes = normalMapper.writeValueAsBytes(obj)
        val compressedBytes = compressedMapper.writeValueAsBytes(obj)
        Assert.assertEquals(
                compressedBytes.size,
                // normal: 1 byte header + string data
                // compressed: expectedLength
                normalBytes.size - (s.length + 1) + expectedLength
        )
        Assert.assertEquals(
                normalMapper.readValue(normalBytes, C::class.java),
                obj,
                "sanity check"
        )
        Assert.assertEquals(
                compressedMapper.readValue(normalBytes, C::class.java),
                obj,
                "should still be able to read normal ser"
        )
        Assert.assertEquals(
                compressedMapper.readValue(compressedBytes, C::class.java),
                obj,
                "should be able to read compressed ser"
        )
    }

    private fun testFieldValue(s: String, expectedLength: Int) {
        val obj = NotCompressed(s)
        val normalBytes = normalMapper.writeValueAsBytes(obj)
        val compressedBytes = compressedMapper.writeValueAsBytes(obj)
        Assert.assertEquals(
                compressedBytes.size,
                // normal: 1 byte header + string data
                // compressed: 1 byte tag + expectedLength
                normalBytes.size - (s.length + 1) + expectedLength + 1,
                "compressed size for '$s'"
        )
        Assert.assertEquals(
                normalMapper.readValue(normalBytes, NotCompressed::class.java),
                obj,
                "sanity check"
        )
        Assert.assertEquals(
                compressedMapper.readValue(normalBytes, NotCompressed::class.java),
                obj,
                "should still be able to read normal ser"
        )
        Assert.assertEquals(
                compressedMapper.readValue(compressedBytes, NotCompressed::class.java),
                obj,
                "should be able to read compressed ser"
        )
    }
}