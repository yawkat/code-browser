package at.yawk.javabrowser.generator

import at.yawk.javabrowser.Tokenizer
import java.util.concurrent.ArrayBlockingQueue

/**
 * @author yawkat
 */
class ConcurrentPrinter {
    private val queue = ArrayBlockingQueue<Action>(16)
    private var running = 0

    fun createDelegate(delegate: PrinterWithEnd): PrinterWithEnd {
        running++
        return object : PrinterWithEnd {
            override val concurrencyControl: ParserConcurrencyControl
                get() = delegate.concurrencyControl

            override fun addDependency(dependency: String) {
                queue.put(Action.AddDependency(delegate, dependency))
            }

            override fun addAlias(alias: String) {
                queue.put(Action.AddAlias(delegate, alias))
            }

            override fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>) {
                queue.put(Action.AddSourceFile(delegate, path, sourceFile, tokens))
            }

            override fun finish() {
                queue.offer(Action.End(delegate))
            }
        }
    }

    @Throws(InterruptedException::class)
    fun work() {
        while (running > 0 || !queue.isEmpty()) {
            val item = queue.take()
            val exhaustiveCheck = when (item) {
                is Action.AddDependency -> item.delegate.addDependency(item.dependency)
                is Action.AddAlias -> item.delegate.addAlias(item.alias)
                is Action.AddSourceFile -> item.delegate.addSourceFile(item.path, item.sourceFile, item.tokens)
                is Action.End -> {
                    running--
                    item.delegate.finish()
                }
            }
        }
    }

    interface PrinterWithEnd : PrinterWithDependencies {
        fun finish()
    }

    private sealed class Action(val delegate: PrinterWithEnd) {
        class AddDependency(delegate: PrinterWithEnd, val dependency: String) : Action(delegate)
        class AddAlias(delegate: PrinterWithEnd, val alias: String) : Action(delegate)
        class AddSourceFile(delegate: PrinterWithEnd,
                            val path: String,
                            val sourceFile: GeneratorSourceFile,
                            val tokens: List<Tokenizer.Token>) : Action(delegate)

        class End(delegate: PrinterWithEnd) : Action(delegate)
    }
}