package at.yawk.javabrowser.generator

import at.yawk.javabrowser.RenderedJavadoc
import at.yawk.javabrowser.generator.artifact.tempDir
import at.yawk.javabrowser.generator.bytecode.testHashBinding
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.FileASTRequestor
import org.eclipse.jdt.internal.compiler.parser.PrepareMonkeyPatch
import org.intellij.lang.annotations.Language
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import org.zeroturnaround.exec.ProcessExecutor
import java.nio.file.Files

private const val COMMENT_PREFIX = "public class Test { /**"
private const val COMMENT_SUFFIX = "*/ public void test() {} }"

class JavadocRendererTest {
    @BeforeClass
    fun before() {
        PrepareMonkeyPatch
    }

    private fun compile(@Language("java") sourceFile: String): CompilationUnit {
        lateinit var unit: CompilationUnit
        tempDir { tmp ->
            val path = tmp.resolve("Test.java")
            Files.write(path, sourceFile.toByteArray())

            val parser = ASTParser.newParser(AST.JLS10)!!
            parser.setCompilerOptions(
                mapOf(
                    JavaCore.COMPILER_SOURCE to JavaCore.VERSION_10,
                    JavaCore.CORE_ENCODING to "UTF-8",
                    JavaCore.COMPILER_DOC_COMMENT_SUPPORT to JavaCore.ENABLED
                )
            )
            parser.setResolveBindings(true)
            parser.setKind(ASTParser.K_COMPILATION_UNIT)
            parser.setEnvironment(emptyArray(), emptyArray(), emptyArray(), true)
            parser.createASTs(
                arrayOf(path.toString()),
                arrayOf("UTF-8"),
                emptyArray<String>(),
                object : FileASTRequestor() {
                    override fun acceptAST(sourceFilePath: String, ast: CompilationUnit) {
                        unit = ast
                    }
                },
                null
            )
        }
        return unit
    }

    private fun realJavadocOutput(
        @Language(value = "java") java: String
    ): List<Node> {
        val document = tempDir { tmp ->
            val path = tmp.resolve("Test.java")
            Files.write(path, (java).toByteArray())

            ProcessExecutor()
                .directory(tmp.toFile())
                .command(
                    "/usr/lib/jvm/java-14-openjdk/bin/javadoc",
                    "Test.java",
                    "-quiet",
                    "-link",
                    "https://docs.oracle.com/en/java/javase/14/docs/api/",
                    "-author"
                )
                .redirectOutput(System.out)
                .redirectError(System.err)
                .exitValue(0)
                .execute()

            Jsoup.parse(tmp.resolve("Test.html").toFile(), "UTF-8")
        }
        val blocks = document.getElementsByClass("detail")
        Assert.assertEquals(blocks.size, 2)
        val childNodes = blocks.last().childNodes()
        return childNodes.drop(childNodes.indexOfFirst { it is Element && it.hasClass("memberSignature") } + 1)
    }

    private fun parseHtml(@Language("html") html: String) = Jsoup.parseBodyFragment(html).body().childNodes()
    private fun renderHtml(nodes: List<Node>) = nodes.joinToString { it.outerHtml() }

    private fun binding(binding: String): String {
        return RenderedJavadoc.bindingToAttributeValue(testHashBinding(binding))
    }

    private fun assertEquals(actual: List<Node>, expected: List<Node>) {
        Assert.assertEquals(renderHtml(actual), renderHtml(expected))
    }

    private fun assertRenderedEquals(
        @Language(value = "java", prefix = COMMENT_PREFIX, suffix = COMMENT_SUFFIX) javadoc: String,
        @Language(value = "html") html: String
    ) = assertRenderedEqualsFull(
        java = COMMENT_PREFIX + javadoc + COMMENT_SUFFIX,
        html = html
    )

    private fun assertRenderedEqualsFull(
        @Language(value = "java") java: String,
        @Language(value = "html") html: String
    ) {
        val unit = compile(java)
        val sourceFile = GeneratorSourceFile(pkg = null, text = java)

        val visitor = JavadocRenderVisitor(::testHashBinding, sourceFile)
        unit.accept(visitor)
        visitor.finish()

        val rendered = sourceFile.entries.map { it.annotation }.filterIsInstance<RenderedJavadoc>().single().html

        try {
            assertEquals(parseHtml(rendered), parseHtml(html))
        } catch (e: AssertionError) {
            try {
                println("Real javadoc: " + renderHtml(realJavadocOutput(java)))
            } catch (f: Exception) {
                e.addSuppressed(f)
            }
            throw e
        }
    }

    @Test
    fun plain() {
        assertRenderedEquals("hi", "<div>hi</div>")
    }

    @Test
    fun codeSimple() {
        assertRenderedEquals(
            "{@code null}",
            "<div><code>null</code></div>"
        )
    }

    @Test
    fun codeNestedJavadocTag() {
        assertRenderedEquals(
            "{@code {@code null}}",
            "<div><code>{@code null}</code></div>"
        )
    }

    @Test
    fun codeNestedHtml() {
        assertRenderedEquals(
            "{@code <p>null}",
            "<div><code>&lt;p&gt;null</code></div>"
        )
    }

    @Test
    fun indexNoDesc() {
        assertRenderedEquals(
            "{@index abc}",
            "<div>abc</div>"
        )
    }

    @Test
    fun indexDesc() {
        assertRenderedEquals(
            "{@index abc def ghi}",
            "<div><span title='def ghi' class='javadoc-tag-index'>abc</span></div>"
        )
    }

    @Test
    fun indexDescQuoted() {
        assertRenderedEquals(
            "{@index \"abc def\" ghi}",
            "<div><span title='ghi' class='javadoc-tag-index'>abc def</span></div>"
        )
    }

    @Test
    fun indexMultiLine() {
        assertRenderedEquals(
            "{@index \"abc\n * def\"}",
            "<div>abc def</div>"
        )
    }

    @Test
    fun literalNestedHtml() {
        assertRenderedEquals(
            "{@literal <p>null}",
            "<div>&lt;p&gt;null</div>"
        )
    }

    @Test
    fun docRoot() {
        assertRenderedEquals(
            "{@docRoot}",
            "<div>{@docRoot}</div>"
        )
    }

    @Test
    fun inheritDoc() {
        assertRenderedEquals(
            "{@inheritDoc}",
            "<div>{@inheritDoc}</div>"
        )
    }

    @Test
    fun linkPackageCollide() {
        assertRenderedEquals(
            "{@link java.util.List#hashCode()} {@link java.awt.List#add(String)}",
            "<div><code><a data-binding=\"3f49a5a1\">List.hashCode()</a></code> <code><a data-binding=\"ffffffffe80669d4\">List.add(String)</a></code></div>"
        )
    }

    @Test
    fun linkParamName() {
        assertRenderedEquals(
            "{@link java.awt.List#add(String foo)}",
            "<div><code><a data-binding=\"ffffffffe80669d4\">List.add(String foo)</a></code></div>"
        )
    }

    @Test
    fun linkVarargsExplicit() {
        assertRenderedEquals(
            "{@link String#format(String, Object...)}",
            "<div><code><a data-binding=\"ffffffffa77df240\">String.format(String, Object...)</a></code></div>"
        )
    }

    @Test
    fun linkVarargsImplicit() {
        assertRenderedEquals(
            "{@link String#format(String, Object[])}",
            "<div><code><a data-binding=\"ffffffffa77df240\">String.format(String, Object[])</a></code></div>"
        )
    }

    @Test
    fun linkPlain() {
        assertRenderedEquals(
            "{@linkplain String#hashCode()}",
            "<div><a data-binding=\"4f51cf3a\">String.hashCode()</a></div>"
        )
    }

    @Test
    fun linkName() {
        assertRenderedEquals(
            "{@link java.lang.String}",
            "<div><code><a data-binding=\"473e3665\">String</a></code></div>"
        )
    }

    @Test
    fun linkField() {
        assertRenderedEquals(
            "{@link java.lang.System#out}",
            "<div><code><a data-binding=\"fffffffffae8e74e\">System.out</a></code></div>"
        )
    }

    @Test
    fun linkConstructor() {
        assertRenderedEquals(
            "{@link String#String()}",
            "<div><code><a data-binding=\"708a3626\">String()</a></code></div>"
        )
    }

    @Test
    fun linkLabel() {
        assertRenderedEquals(
            "{@link java.lang.System#out foo}",
            "<div><code><a data-binding=\"fffffffffae8e74e\">foo</a></code></div>"
        )
    }

    @Test
    fun linkSameClass() {
        assertRenderedEquals(
            "{@link #test()}",
            "<div><code><a data-binding=\"ffffffffeffc0204\">test()</a></code></div>"
        )
    }

    @Test
    fun inlineHtml() {
        assertRenderedEquals(
            "<b>bold</b>",
            "<div><b>bold</b></div>"
        )
    }

    @Test
    fun summary() {
        assertRenderedEquals(
            "{@summary foo}",
            "<div>foo</div>"
        )
    }

    @Test
    fun systemProperty() {
        assertRenderedEquals(
            "{@systemProperty foo.bar}",
            "<div>foo.bar</div>"
        )
    }

    @Test
    fun valueInt() {
        assertRenderedEquals(
            "{@value Byte#MAX_VALUE}",
            "<div><a data-binding=\"121d932f\">127</a></div>"
        )
    }

    @Test
    fun valueString() {
        // it's really hard to find string consts in the stdlib...
        assertRenderedEquals(
            "{@value java.util.jar.JarFile#MANIFEST_NAME}",
            "<div><a data-binding=\"2b709ffc\">\"META-INF/MANIFEST.MF\"</a></div>"
        )
    }

    @Test
    fun valueSameProperty() {
        assertRenderedEqualsFull(
            "public class Test { /**{@value}*/ public static final int I = 5; }",
            "<div>5</div>"
        )
    }

    @Test
    fun cleanHtml() {
        assertRenderedEquals(
            "<script>test</script><blob>b</blob><a foo='bar'>x</a>",
            "<div>b<a rel='nofollow'>x</a></div>"
        )
    }

    @Test
    fun deprecated() {
        assertRenderedEquals(
            "@deprecated foo",
            "<table><tr><th>Deprecated:</th><td>foo</td></tr></table>"
        )
    }

    @Test
    fun author() {
        assertRenderedEquals(
            "@author foo",
            "<table><tr><th>Author:</th><td>foo</td></tr></table>"
        )
    }

    @Test
    fun authorMulti() {
        assertRenderedEquals(
            "@author foo\n@author bar",
            "<table><tr><th>Author:</th><td>foo, bar</td></tr></table>"
        )
    }

    @Test
    fun throws() {
        assertRenderedEquals(
            "@throws RuntimeException foo\n@throws Error bar",
            "<table><tr><th>Throws:</th><td><ul><li><a data-binding=\"${binding("java.lang.RuntimeException")}\">RuntimeException</a> – foo</li><li><a data-binding=\"${binding("java.lang.Error")}\">Error</a> – bar</li></ul></td></tr></table>"
        )
    }

    @Test
    fun hidden() {
        assertRenderedEquals(
            "@hidden",
            "<table><tr><th>Hidden</th><td></td></tr></table>"
        )
    }

    @Test
    fun params() {
        assertRenderedEquals(
            "@param foo bar\n@param bar foo",
            "<table><tr><th>Params:</th><td><ul><li>foo – bar</li><li>bar – foo</li></ul></td></tr></table>"
        )
    }

    @Test
    fun typeParams() {
        assertRenderedEquals(
            "@param <FOO> bar\n@param <BAR> foo",
            "<table><tr><th>Type parameters:</th><td><ul><li>&lt;FOO&gt; – bar</li><li>&lt;BAR&gt; – foo</li></ul></td></tr></table>"
        )
    }

    @Test
    fun provides() {
        // a link to the type would be better, but JDT doesn't parse this yet. this test will fail when it does,
        // and you will get to fix it :)
        assertRenderedEquals(
            "@provides java.lang.String foo",
            "<table><tr><th>Provides:</th><td><ul><li>java.lang.String – foo</li></ul></td></tr></table>"
        )
    }

    @Test
    fun uses() {
        // see provides
        assertRenderedEquals(
            "@uses java.lang.String foo",
            "<table><tr><th>Uses:</th><td><ul><li>java.lang.String – foo</li></ul></td></tr></table>"
        )
    }

    @Test
    fun seeText() {
        assertRenderedEquals(
            "@see \"hi\"",
            "<table><tr><th>See Also:</th><td><ul><li>hi</li></ul></td></tr></table>"
        )
    }

    @Test
    fun seeMulti() {
        assertRenderedEquals(
            "@see \"foo\"\n * @see \"bar\"",
            "<table><tr><th>See Also:</th><td><ul><li>foo</li><li>bar</li></ul></td></tr></table>"
        )
    }

    @Test
    fun seeLink() {
        assertRenderedEquals(
            "@see <a href='http://yawk.at'>foo</a>",
            "<table><tr><th>See Also:</th><td><ul><li><a href='http://yawk.at' rel='nofollow'>foo</a></li></ul></td></tr></table>"
        )
    }

    @Test
    fun seeRef() {
        assertRenderedEquals(
            "@see String",
            "<table><tr><th>See Also:</th><td><ul><li><a data-binding='${binding("java.lang.String")}'>String</a></li></ul></td></tr></table>"
        )
    }

    @Test
    fun seeRefMulti() {
        assertRenderedEquals(
            "@see String\n * @see String",
            "<table><tr><th>See Also:</th><td><ul><li><a data-binding='${binding("java.lang.String")}'>String</a></li><li><a data-binding='${binding("java.lang.String")}'>String</a></li></ul></td></tr></table>"
        )
    }

    @Test
    fun seeRefLabel() {
        assertRenderedEquals(
            "@see String foo",
            "<table><tr><th>See Also:</th><td><ul><li><a data-binding='${binding("java.lang.String")}'>foo</a></li></ul></td></tr></table>"
        )
    }

    @Test
    fun since() {
        assertRenderedEquals(
            "@since foo",
            "<table><tr><th>Since:</th><td>foo</td></tr></table>"
        )
    }

    @Test
    fun version() {
        assertRenderedEquals(
            "@version foo",
            "<table><tr><th>Version:</th><td>foo</td></tr></table>"
        )
    }

    @Test
    fun relativeLink() {
        assertRenderedEquals(
            "<a href='../index.html'>foo</a>",
            "<div><a rel=\"nofollow\">foo</a></div>"
        )
    }

    @Test
    fun seeWeirdLabel() {
        assertRenderedEquals(
            "@see String {@code String}",
            "<table><tr><th>See Also:</th><td><ul><li><a data-binding='${binding("java.lang.String")}'><code>String</code></a></li></ul></td></tr></table>"
        )
    }

    @Test
    fun multiLine() {
        assertRenderedEquals(
            "foo\n * bar",
            "<div>foo bar</div>"
        )
    }

    @Test
    fun linkFallback() {
        assertRenderedEquals(
            "{@link Foo#bar(Boo, byte)}",
            "<div><code>bar.bar(Boo, byte)</code></div>"
        )
    }

    @Test
    fun invalidLink1() {
        assertRenderedEquals(
            "{@link Cipher.doFinal(byte[])}",
            "<div><code>Cipher.doFinal(byte[])</code></div>"
        )
    }

    @Test
    fun invalidLink2() {
        assertRenderedEquals(
            "{@link System#setProperty) or {@link System#getProperties()}",
            "<div><code></code> System#setProperty) or <code><a data-binding=\"${binding("java.lang.System#getProperties()")}\">System.getProperties()</a></code></div>"
        )
    }

    @Test
    fun invalidParam() {
        assertRenderedEquals(
            "@param foo<code></code> bar",
            "<table><tr><th>@param</th><td>foo<code></code> bar</td></tr></table>"
        )
    }

    @Test
    fun emptyParam() {
        assertRenderedEquals(
            "@param\n ",
            "<table><tr><th>@param</th><td></td></tr></table>"
        )
    }

    @Test
    fun invalidValue() {
        assertRenderedEquals(
            "{@value BLA}",
            "<div>{@value BLA}</div>"
        )
    }

    @Test
    fun valueWithSpace() {
        assertRenderedEquals(
            "{@value Byte#MAX_VALUE }",
            "<div><a data-binding='${binding("java.lang.Byte#MAX_VALUE")}'>127</a></div>"
        )
    }

    @Test
    fun invalidSee() {
        assertRenderedEqualsFull(
            "/**@see\n */ public class Test {}",
            "<table><tr><th>See Also:</th><td><ul><li></li></ul></td></tr></table>"
        )
    }

    @Test
    fun preLineBreaks() {
        assertRenderedEquals(
            "<blockquote><pre>\n * \tfoo\n * \tbar</pre></blockquote>",
            "<div><blockquote><pre>\tfoo\n\tbar</pre></blockquote></div>"
        )
    }

    @Test
    fun normalAndRoot() {
        assertRenderedEquals(
            "foo\n * @author bar",
            "<div>foo </div><table><tr><th>Author:</th><td>bar</td></tr></table>"
        )
    }
}