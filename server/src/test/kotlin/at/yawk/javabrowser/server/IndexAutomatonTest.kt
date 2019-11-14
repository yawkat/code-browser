package at.yawk.javabrowser.server

import org.testng.annotations.Test

/**
 * @author yawkat
 */
class IndexAutomatonTest {
    @Test
    fun fullLoad() {
        val automaton = IndexAutomaton(
                TestBindings.bindings,
                { SearchIndex.split(it) },
                5
        )
        println(automaton.run("javuticohamap"))
    }
}