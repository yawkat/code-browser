package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
import java.util.concurrent.ArrayBlockingQueue

/**
 * @author yawkat
 */
class ConcurrentPrinter(private val delegate: PrinterWithDependencies) : PrinterWithDependencies {
    private val queue = ArrayBlockingQueue<Action>(16)
    @Volatile
    private var done = false

    override fun addDependency(dependency: String) {
        queue.put(Action.AddDependency(dependency))
    }

    override fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        queue.put(Action.AddSourceFile(path, sourceFile))
    }

    fun finish() {
        done = true
        queue.offer(Action.End)
    }

    fun work(delegate: PrinterWithDependencies) {
        while (!done || !queue.isEmpty()) {
            val item = queue.take()
            when (item) {
                is Action.AddDependency -> delegate.addDependency(item.dependency)
                is Action.AddSourceFile -> delegate.addSourceFile(item.path, item.sourceFile)
                is Action.End -> {}
            }
        }
    }

    private sealed class Action {
        data class AddDependency(val dependency: String) : Action()
        data class AddSourceFile(val path: String, val sourceFile: AnnotatedSourceFile) : Action()
        object End : Action()
    }
}