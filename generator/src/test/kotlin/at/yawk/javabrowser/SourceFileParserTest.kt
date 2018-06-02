package at.yawk.javabrowser

import com.google.common.io.MoreFiles
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.intellij.lang.annotations.Language
import org.testng.Assert
import org.testng.annotations.AfterTest
import org.testng.annotations.BeforeTest
import org.testng.annotations.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * @author yawkat
 */
class SourceFileParserTest {
    private var tmp = Paths.get("/null")

    @BeforeTest
    fun setup() {
        tmp = Files.createTempDirectory("SourceFileParserTest")
    }

    @AfterTest
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
        var j = 0
        while (i-- >= 0) {
            j = code.indexOf(word, j)
            if (j == -1) throw NoSuchElementException()
        }

        return AnnotatedSourceFile.Entry(j, word.length, annotation)
    }

    private fun compileOne() = SourceFileParser.compile(src, emptyList(), false).sourceFiles.values.single()

    @Test
    fun superMethodCall() {
        val a = "class A { public int hashCode() { return super.hashCode(); } }"
        write("A.java", a)
        MatcherAssert.assertThat(
                compileOne().entries,
                Matchers.hasItem(annotate(a, BindingRef("java.lang.Object#hashCode()"), "hashCode", 1))
        )
    }
}