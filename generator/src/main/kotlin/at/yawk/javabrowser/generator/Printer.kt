package at.yawk.javabrowser.generator

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer

/**
 * @author yawkat
 */
interface Printer {
    val concurrencyControl: ParserConcurrencyControl
        get() = ParserConcurrencyControl.NoLimit

    fun hashBinding(binding: String): BindingId

    fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>, realm: Realm)

    class SimplePrinter : Printer {
        val sourceFiles: MutableMap<String, GeneratorSourceFile> = HashMap()

        override fun hashBinding(binding: String) = BindingId(binding.hashCode().toLong())

        override fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>,
                                   realm: Realm) {
            sourceFiles[path] = sourceFile
        }
    }
}