package at.yawk.javabrowser.generator

import at.yawk.javabrowser.Tokenizer
import java.util.concurrent.ArrayBlockingQueue

/**
 * @author yawkat
 */
class ConcurrentPrinter : PrinterWithDependencies {
    private val queue = ArrayBlockingQueue<Action>(16)
    @Volatile
    private var done = false

    override fun addDependency(dependency: String) {
        queue.put(Action.AddDependency(dependency))
    }

    override fun addAlias(alias: String) {
        queue.put(Action.AddAlias(alias))
    }

    override fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>) {
        queue.put(Action.AddSourceFile(path, sourceFile, tokens))
    }

    fun finish() {
        done = true
        queue.offer(Action.End)
    }

    fun work(delegate: PrinterWithDependencies) {
        while (!done || !queue.isEmpty()) {
            val item = queue.take()
            val exhaustiveCheck = when (item) {
                is Action.AddDependency -> delegate.addDependency(item.dependency)
                is Action.AddAlias -> delegate.addDependency(item.alias)
                is Action.AddSourceFile -> delegate.addSourceFile(item.path, item.sourceFile, item.tokens)
                is Action.End -> {}
            }
        }
    }

    private sealed class Action {
        data class AddDependency(val dependency: String) : Action()
        data class AddAlias(val alias: String) : Action()
        data class AddSourceFile(val path: String,
                                 val sourceFile: GeneratorSourceFile,
                                 val tokens: List<Tokenizer.Token>) : Action()
        object End : Action()
    }
}