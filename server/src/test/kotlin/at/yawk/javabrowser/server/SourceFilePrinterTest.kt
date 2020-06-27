package at.yawk.javabrowser.server

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.PositionedAnnotation
import at.yawk.javabrowser.SourceAnnotation
import org.testng.Assert
import org.testng.annotations.DataProvider
import org.testng.annotations.Test
import java.util.ArrayDeque

/**
 * @author yawkat
 */
class SourceFilePrinterTest {
    @DataProvider
    fun dataNormal(): Array<Array<Any>> = arrayOf(
            arrayOf<Any>(
                    """abc""",
                    """<0>abc"""
            ),
            arrayOf<Any>(
                    "ab[tag]c\nde[/tag]f",
                    "<0>ab[tag]c\n<1>de[/tag]f"
            ),
            arrayOf<Any>(
                    "ab[tag][/tag]c",
                    "<0>ab[tag][/tag]c"
            )
    )

    @Test(dataProvider = "dataNormal")
    fun testNormal(markup: String, expect: String) {
        val emitter = MockEmitter()
        SourceFilePrinter.toHtmlSingle(emitter, parseSourceFile(markup))
        Assert.assertEquals(
                emitter.output.toString(),
                expect
        )
    }

    @DataProvider
    fun dataDiff(): Array<Array<Any>> = arrayOf(
            arrayOf<Any>(
                    "abc",
                    "def",
                    """---
<null 0>abc/---
+++
<0 null>def/+++
"""
            ),
            arrayOf<Any>(
                    "ab[tag1]c\nde[/tag1]f",
                    "ab[tag2]d\nde[/tag2]f",
                    """---
<null 0>ab[tag1]c
[/tag1]/---
+++
<0 null>ab[tag2]d
[/tag2]/+++
[tag2]<1 1>de[/tag2]f"""
            ),
            arrayOf<Any>(
                    "ab[tag1]c\nde[/tag1]f",
                    "ab[tag2]c\nxe[/tag2]f",
                    """<0 0>ab[tag2]c
[/tag2]---
[tag1]<null 1>de[/tag1]f/---
+++
[tag2]<1 null>xe[/tag2]f/+++
"""
            ),
            arrayOf<Any>(
                    "ab[tag1]c[/tag1]\ndef",
                    "ab[tag2]c[/tag2]\nxef",
                    """<0 0>ab[tag2]c[/tag2]
---
<null 1>def/---
+++
<1 null>xef/+++
"""
            )
    )

    @Test(dataProvider = "dataDiff")
    fun testDiff(markupOld: String, markupNew: String, expect: String) {
        val emitter = MockEmitter()
        SourceFilePrinter.Diff(parseSourceFile(markupNew), parseSourceFile(markupOld))
                .toHtml(emitter)
        Assert.assertEquals(
                emitter.output.toString(),
                expect
        )
    }
    @DataProvider
    fun dataPartial(): Array<Array<Any>> = arrayOf(
            arrayOf<Any>(
                    "abc",
                    ""
            ),
            arrayOf<Any>(
                    "ab[tag]c\nd(e)[/tag]f",
                    "<0>ab[tag]c\n<1>d{(e)}[/tag]f---\n"
            ),
            arrayOf<Any>(
                    """abc
def
g[tag1][tag2]h[/tag2]i
j[/tag1]kl
m(n)o
pqr
stu""",
                    """[tag1]<3>j[/tag1]kl
<4>m{(n)}o
<5>pqr
---
"""
            )
    )

    @Test(dataProvider = "dataPartial")
    fun testPartial(markup: String, expect: String) {
        val emitter = MockEmitter()
        val sourceFile = parseSourceFile(markup)
        val partial = SourceFilePrinter.Partial(sourceFile)

        // regions in (parentheses) are highlighted
        var i = 0
        while (true) {
            val start = sourceFile.text.indexOf('(', i)
            if (start == -1) {
                break
            }
            val end = sourceFile.text.indexOf(')', start)
            partial.addInterest(start, end + 1)
            i = end
        }

        // context of 1 line
        val renderer = partial.createRenderer<SourceAnnotation>(1, 1)
        while (renderer.hasMore()) {
            renderer.renderNextRegion(emitter)
            emitter.output.appendln("---")
        }

        Assert.assertEquals(
                emitter.output.toString(),
                expect
        )
    }

    private fun parseSourceFile(markup: String): ServerSourceFile {
        val tags = ArrayList<PositionedAnnotation>()
        val tagStack = ArrayDeque<PositionedAnnotation>()
        val clean = StringBuilder()
        var i = 0
        while (true) {
            val nextTag = markup.indexOf('[', i)
            if (nextTag == -1) {
                break
            }
            clean.append(markup, i, nextTag)
            val end = markup.indexOf(']', nextTag)
            val tag = markup.substring(nextTag + 1, end)
            if (tag.startsWith('/')) {
                val ann = tagStack.removeLast()
                assert("/" + textFromMockAnnotation(ann.annotation) == tag)
                tags.add(ann.copy(length = clean.length - ann.start))
            } else {
                tagStack.addLast(PositionedAnnotation(clean.length, 0, mockAnnotation(tag)))
            }
            i = end + 1
        }
        clean.append(markup, i, markup.length)
        return ServerSourceFile(clean.toString(), tags.asSequence())
    }

    companion object {
        private fun mockAnnotation(text: String): SourceAnnotation = BindingDecl(
                BindingId(text.hashCode().toLong()), text, parent = null, description = BindingDecl.Description.Package, modifiers = 0)

        private fun textFromMockAnnotation(annotation: SourceAnnotation) = (annotation as BindingDecl).binding
    }

    private class MockEmitter : SourceFilePrinter.Emitter<SourceAnnotation> {
        val output = StringBuilder()

        override fun computeMemory(scope: SourceFilePrinter.Scope, annotation: SourceAnnotation) = annotation

        override fun startAnnotation(scope: SourceFilePrinter.Scope,
                                     annotation: SourceAnnotation,
                                     memory: SourceAnnotation) {
            Assert.assertEquals(memory, annotation)
            output.append("[${textFromMockAnnotation(annotation)}]")
        }

        override fun endAnnotation(scope: SourceFilePrinter.Scope,
                                   annotation: SourceAnnotation,
                                   memory: SourceAnnotation) {
            Assert.assertEquals(memory, annotation)
            output.append("[/${textFromMockAnnotation(annotation)}]")
        }

        override fun text(s: String, start: Int, end: Int) {
            output.append(s, start, end)
        }

        override fun beginInsertion() {
            output.appendln("+++")
        }

        override fun beginDeletion() {
            output.appendln("---")
        }

        override fun beginHighlight() {
            output.append('{')
        }

        override fun endInsertion() {
            output.appendln("/+++")
        }

        override fun endDeletion() {
            output.appendln("/---")
        }

        override fun endHighlight() {
            output.append('}')
        }

        override fun diffLineMarker(newLine: Int?, oldLine: Int?) {
            output.append("<$newLine $oldLine>")
        }

        override fun normalLineMarker(line: Int) {
            output.append("<$line>")
        }
    }
}