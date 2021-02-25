package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import org.testng.Assert
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import java.io.StringWriter

private val zwsp = '\u200b'

class PathDirectiveTest {
    private lateinit var java8: ArtifactNode
    private lateinit var java11: ArtifactNode

    @BeforeClass
    fun setup() {
        // mock artifact nodes
        val artifacts = ArtifactNode.build(
            listOf(
                ArtifactNode.Prototype(0, "java/8"),
                ArtifactNode.Prototype(1, "java/11")
            )
        )
        java8 = artifacts.allNodesByDbId[0]
        java11 = artifacts.allNodesByDbId[1]
    }

    @Test
    fun `normal group artifact`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.Group(java8.parent!!), null)
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> ")
    }

    @Test
    fun `normal leaf artifact`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.LeafArtifact(java8), null)
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/8'>8/</a> ")
    }

    @Test
    fun `diff leaf artifact`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.LeafArtifact(java11), ParsedPath.LeafArtifact(java8))
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/11?diff=%2Fjava%2F8'><span class='path-diff'><span class='foreground-new'>${zwsp}11/</span><span class='foreground-old'>${zwsp}8/</span></span></a> ")
    }

    @Test
    fun `normal directory`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.SourceFile(java8, "java/lang/"), null)
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/8'>8/</a> <span class='source-file-dir'><a href='/java/8/java/'>java/</a><a href='/java/8/java/lang/'>lang/</a></span>")
    }

    @Test
    fun `diff directory empty path`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.SourceFile(java11, "java.base/java/lang/"), ParsedPath.SourceFile(java8, "java/lang/"))
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/11?diff=%2Fjava%2F8'><span class='path-diff'><span class='foreground-new'>${zwsp}11/</span><span class='foreground-old'>${zwsp}8/</span></span></a> <span class='source-file-dir'><span class='path-diff'><span class='foreground-new'>${zwsp}java.base/</span><span class='foreground-old'>${zwsp}</span></span><a href='/java/11/java.base/java/?diff=%2Fjava%2F8%2Fjava%2F'>java/</a><a href='/java/11/java.base/java/lang/?diff=%2Fjava%2F8%2Fjava%2Flang%2F'>lang/</a></span>")
    }

    @Test
    fun `diff directory`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.SourceFile(java11, "java.base/java/lang/"), ParsedPath.SourceFile(java8, "java.base/lang/"))
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/11?diff=%2Fjava%2F8'><span class='path-diff'><span class='foreground-new'>${zwsp}11/</span><span class='foreground-old'>${zwsp}8/</span></span></a> <span class='source-file-dir'><a href='/java/11/java.base/?diff=%2Fjava%2F8%2Fjava.base%2F'>java.base/</a><a href='/java/11/java.base/java/?diff=%2Fjava%2F8%2Fjava.base%2F'><span class='path-diff'><span class='foreground-new'>${zwsp}java/</span><span class='foreground-old'>${zwsp}</span></span></a><a href='/java/11/java.base/java/lang/?diff=%2Fjava%2F8%2Fjava.base%2Flang%2F'>lang/</a></span>")
    }

    @Test
    fun `normal file`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.SourceFile(java8, "java/lang/String.java"), null)
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/8'>8/</a> <span class='source-file-dir'><a href='/java/8/java/'>java/</a><a href='/java/8/java/lang/'>lang/</a></span><a href='/java/8/java/lang/String.java'>String.java</a>")
    }

    @Test
    fun `diff file`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.SourceFile(java11, "java/lang/String.java"), ParsedPath.SourceFile(java8, "java/lang/Stringx.java"))
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/11?diff=%2Fjava%2F8'><span class='path-diff'><span class='foreground-new'>${zwsp}11/</span><span class='foreground-old'>${zwsp}8/</span></span></a> <span class='source-file-dir'><a href='/java/11/java/?diff=%2Fjava%2F8%2Fjava%2F'>java/</a><a href='/java/11/java/lang/?diff=%2Fjava%2F8%2Fjava%2Flang%2F'>lang/</a></span><a href='/java/11/java/lang/String.java?diff=%2Fjava%2F8%2Fjava%2Flang%2FStringx.java'><span class='path-diff'><span class='foreground-new'>${zwsp}String.java</span><span class='foreground-old'>${zwsp}Stringx.java</span></span></a>")
    }

    @Test
    fun `diff file and dir`() {
        val out = StringWriter()
        PathDirective.Impl(out).run(ParsedPath.SourceFile(java11, "java/lang/String.java"), ParsedPath.SourceFile(java8, "java/langx/Stringx.java"))
        Assert.assertEquals(out.toString(), "<a href='/java'>java/</a> <a href='/java/11?diff=%2Fjava%2F8'><span class='path-diff'><span class='foreground-new'>${zwsp}11/</span><span class='foreground-old'>${zwsp}8/</span></span></a> <span class='source-file-dir'><a href='/java/11/java/?diff=%2Fjava%2F8%2Fjava%2F'>java/</a></span><a href='/java/11/java/lang/String.java?diff=%2Fjava%2F8%2Fjava%2Flangx%2FStringx.java'><span class='path-diff'><span class='foreground-new'>${zwsp}lang/String.java</span><span class='foreground-old'>${zwsp}langx/Stringx.java</span></span></a>")
    }
}