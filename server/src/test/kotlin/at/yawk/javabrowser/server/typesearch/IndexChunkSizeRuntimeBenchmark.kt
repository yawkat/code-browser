package at.yawk.javabrowser.server.typesearch

import com.google.common.io.MoreFiles
import org.jdbi.v3.core.Jdbi
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.options.TimeValue
import java.nio.file.Files
import java.nio.file.Paths

fun benchmarkGetEntries(args: Array<String>): List<SearchIndex.SplitEntry> {
    val dbi = Jdbi.open(args[0])
    return dbi.createQuery("select binding from data.bindings where realm = 0 and isType")
            .map { rs, _, _ ->
                SearchIndex.SplitEntry(rs.getString(1), BindingTokenizer.Java)
            }
            .toList()
            .sorted()
}

fun main(args: Array<String>) {
    val entries = benchmarkGetEntries(args)

    val automataDir = Files.createTempDirectory(Paths.get("/var/tmp"), "automata")
    try {
        val bindingsFile = automataDir.resolve("bindings.txt")
        Files.write(bindingsFile, entries.map { it.string })

        println("Running benchmark...")
        val options = OptionsBuilder()
                .forks(1)
                .warmupIterations(5)
                .warmupTime(TimeValue.seconds(10))
                .measurementIterations(10)
                .measurementTime(TimeValue.seconds(10))
                .include(IndexChunkSizeRuntimeBenchmark::class.java.name)
                .param("automataDir", automataDir.toString())
                .jvmArgsPrepend("-Xmx10G", "-Xms10G")
                .build()

        Runner(options).run()
    } finally {
        MoreFiles.deleteRecursively(automataDir)
    }
}