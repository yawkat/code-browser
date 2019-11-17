package at.yawk.javabrowser.server

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