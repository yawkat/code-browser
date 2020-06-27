package at.yawk.javabrowser

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport
import org.testng.Assert
import org.testng.annotations.Test

class SourceAnnotationTest {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))

    private inline fun <reified T> testDeser(o: T) {
        val s = objectMapper.writerFor(T::class.java).writeValueAsString(o)
        val d = objectMapper.readerFor(T::class.java).readValue<T>(s)
        Assert.assertEquals(d, o)
    }

    @Test
    fun testDeser() {
        testDeser(BindingRef(
                BindingRefType.SUPER_TYPE,
                BindingId(123),
                234,
                false
        ))
        testDeser(BindingDecl(
                id = BindingId(123),
                binding = "abc",
                description = BindingDecl.Description.Package,
                modifiers = 456,
                superBindings = emptyList(),
                parent = BindingId(789)
        ))
        testDeser(BindingDecl.Description.Type(
                BindingDecl.Description.Type.Kind.CLASS,
                BindingId(123),
                "abc",
                emptyList()
        ))
        testDeser(BindingDecl.Super(
                "abc",
                BindingId(123)
        ))
    }
}