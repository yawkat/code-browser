package at.yawk.javabrowser.server.typesearch

import java.util.concurrent.TimeUnit

private fun gc() {
    println("Running gc...")
    System.runFinalization()
    System.gc()
    System.runFinalization()
    System.gc()
    TimeUnit.SECONDS.sleep(2)
}

fun main(args: Array<String>) {
    val entries = benchmarkGetEntries(args)

    for (chunkSize in listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)) {
        for (jumps in 0..IndexChunkSizeRuntimeBenchmark.JUMP_MAX) {
            gc()
            val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val automaton = IndexAutomaton(
                    entries,
                    { it.componentsLower.asList() },
                    jumps, chunkSize
            )
            gc()
            val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            println("$chunkSize\t$jumps\t${after - before}")
            automaton.run("foo")
        }
    }
}