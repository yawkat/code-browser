package at.yawk.javabrowser.server.typesearch

import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class IndexAutomatonTest {
    @Test
    fun partial() {
        val automaton = IndexAutomaton(
                listOf("java.util.concurrent.ConcurrentHashMap",
                        "java.util.HashMap"),
                { BindingTokenizer.Java.tokenize(it) },
                4
        )
        val result = automaton.run("javuticohamap")
        Assert.assertEquals(
                result.asSequence().toList(),
                listOf("java.util.concurrent.ConcurrentHashMap")
        )
    }

    @Test
    fun `jump direct`() {
        val automaton = IndexAutomaton(
                listOf("io.netty.buffer.ByteBuf"),
                { BindingTokenizer.Java.tokenize(it) },
                1
        )
        val result = automaton.run("nettybytebuf")
        Assert.assertEquals(
                result.asSequence().toList(),
                listOf("io.netty.buffer.ByteBuf")
        )
    }

    @Test
    fun `no jump`() {
        val automaton = IndexAutomaton(
                listOf("java.lang.String"),
                { BindingTokenizer.Java.tokenize(it) },
                0
        )
        val result = automaton.run("string")
        Assert.assertEquals(
                result.asSequence().toList(),
                listOf("java.lang.String")
        )
    }
}