package at.yawk.javabrowser

import com.google.common.io.MoreFiles
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.intellij.lang.annotations.Language
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * @author yawkat
 */
class SourceFileParserTest {
    private var tmp = Paths.get("/null")

    @BeforeMethod
    fun setup() {
        tmp = Files.createTempDirectory("SourceFileParserTest")
    }

    @AfterMethod
    fun tearDown() {
        MoreFiles.deleteRecursively(tmp)
    }

    private val src: Path
        get() = tmp.resolve("src")

    private fun write(path: String, @Language("Java") content: String) {
        val p = src.resolve(path)
        Files.createDirectories(p.parent)
        Files.write(p, content.toByteArray())
    }

    private fun annotate(code: String,
                         annotation: SourceAnnotation,
                         word: String,
                         index: Int = 0): AnnotatedSourceFile.Entry {
        var i = index
        var j = -1
        while (i-- >= 0) {
            j = code.indexOf(word, j + 1)
            if (j == -1) throw NoSuchElementException()
        }

        return AnnotatedSourceFile.Entry(j, word.length, annotation)
    }

    private fun compile(): Map<String, AnnotatedSourceFile> {
        val printer = Printer.SimplePrinter()
        val parser = SourceFileParser(src, printer)
        parser.compile()
        return printer.sourceFiles
    }

    private fun compileOne() = compile().values.single()

    @Test
    fun superMethodCall() {
        val a = "class A { public int hashCode() { return super.hashCode(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(a, BindingRef(BindingRefType.SUPER_METHOD_CALL, "java.lang.Object#hashCode()", 3), "hashCode", 1))
        )
    }

    @Test
    fun superConstructorCall() {
        val a = "class A extends B { public A() { super(); } }"
        write("A.java", a)
        write("B.java", "class B { public B() {} }")
        MatcherAssert.assertThat(
                compile()["A.java"]!!.entries,
                Matchers.hasItem(annotate(a, BindingRef(BindingRefType.SUPER_CONSTRUCTOR_CALL, "B()", 1), "super"))
        )
    }

    @Test
    fun superConstructorCallImplicit() {
        val a = "class A { public A() { super(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(
                        a,
                        BindingRef(BindingRefType.SUPER_CONSTRUCTOR_CALL, "java.lang.Object()", 1),
                        "super"
                ))
        )
    }

    @Test
    fun staticCall() {
        val a = "class A { static void x() { A.x(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(a, BindingRef(BindingRefType.UNCLASSIFIED, "A", 3), "A", 1))
        )
    }

    @Test
    fun local() {
        val a = "class A { { int variable; variable++; } }"
        write("A.java", a)
        val entries = compileOne().entries
        val lvr = entries.find { it.annotation is LocalVariableRef }!!.annotation
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(annotate(a, lvr, "variable"))
        )
        MatcherAssert.assertThat(
                entries,
                Matchers.hasItem(annotate(a, lvr, "variable", 1))
        )
    }

    @Test
    fun genericCall() {
        val a = "class A { { x(3); } static <T> T x(T t) { return t; } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(
                        a,
                        BindingRef(BindingRefType.METHOD_CALL, "A#x(java.lang.Object)", 1),
                        "x",
                        0
                ))
        )
    }
}