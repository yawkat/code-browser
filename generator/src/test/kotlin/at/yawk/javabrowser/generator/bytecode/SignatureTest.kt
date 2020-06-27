package at.yawk.javabrowser.generator.bytecode

import org.testng.Assert
import org.testng.annotations.Test

class SignatureTest {
    @Test
    fun `method signature 1`() {
        val printer = BytecodePrinter(::testHashBinding)
        printer.printMethodSignature("<E:Ljava/lang/Exception;>(Ljava/util/List<TE;>;I)Ljava/util/List<Ljava/lang/String;>;^TE;^Ljava/lang/Throwable;",
                access = 0,
                name = "test")

        Assert.assertEquals(printer.finishString(),
                "<E extends java.lang.Exception> java.util.List<java.lang.String> test(java.util.List<E>, int) throws E, java.lang.Throwable")
    }

    @Test
    fun `method signature 2`() {
        val printer = BytecodePrinter(::testHashBinding)
        printer.printMethodSignature("<E:Ljava/lang/Exception;>()V^TE;", access = 0, name = "test")

        Assert.assertEquals(printer.finishString(), "<E extends java.lang.Exception> void test() throws E")
    }

    @Test
    fun `method signature 3`() {
        val printer = BytecodePrinter(::testHashBinding)
        printer.printMethodSignature("<T:Ljava/lang/Object;>()V", access = 0, name = "test")

        Assert.assertEquals(printer.finishString(), "<T> void test()")
    }
}