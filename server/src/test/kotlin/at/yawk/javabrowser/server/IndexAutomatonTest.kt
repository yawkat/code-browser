package at.yawk.javabrowser.server

import com.google.common.base.Stopwatch
import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class IndexAutomatonTest {
    @Test(enabled = false) // takes lots of memory
    fun fullLoad() {
        val automaton = IndexAutomaton(
                TestBindings.bindings,
                { SearchIndex.split(it) },
                5
        )
        val sw = Stopwatch.createStarted()
        val result = automaton.run("javuticohamap")
        sw.stop()
        println(result)
        println(sw)
    }

    @Test
    fun partial() {
        val automaton = IndexAutomaton(
                listOf("java.util.concurrent.ConcurrentHashMap",
                        "java.util.HashMap"),
                { SearchIndex.split(it) },
                5
        )
        val result = automaton.run("javuticohamap")
        Assert.assertEquals(
                result.asSequence().toList(),
                listOf("java.util.concurrent.ConcurrentHashMap")
        )
    }
}