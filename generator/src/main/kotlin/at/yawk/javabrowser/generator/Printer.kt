package at.yawk.javabrowser.generator

import at.yawk.javabrowser.Tokenizer

/**
 * @author yawkat
 */
interface Printer {
    val concurrencyControl: ParserConcurrencyControl
        get() = ParserConcurrencyControl.NoLimit

    fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>)

    class SimplePrinter : Printer {
        val sourceFiles: MutableMap<String, GeneratorSourceFile> = HashMap()

        override fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>) {
            sourceFiles[path] = sourceFile
        }
    }
}