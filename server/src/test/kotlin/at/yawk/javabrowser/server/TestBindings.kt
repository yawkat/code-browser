package at.yawk.javabrowser.server

import com.google.common.base.Stopwatch
import org.tukaani.xz.XZInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * @author yawkat
 */
object TestBindings {
    val bindings: List<String>
    init {
        println("Loading test data...")
        val stopwatch = Stopwatch.createStarted()
        bindings = InputStreamReader(XZInputStream(
                TestBindings::class.java.getResourceAsStream("bindings.tsv.xz")), StandardCharsets.UTF_8).readLines()
        stopwatch.stop()
        println("Test data loaded in $stopwatch")
    }

}