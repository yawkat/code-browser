package at.yawk.javabrowser.generator.source

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.GeneratorSourceFile

/**
 * @author yawkat
 */
interface Printer {
    fun hashBinding(binding: String): BindingId

    suspend fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>, realm: Realm)

    class SimplePrinter : Printer {
        val sourceFiles: MutableMap<String, GeneratorSourceFile> = HashMap()

        override fun hashBinding(binding: String) = BindingId(binding.hashCode().toLong())

        override suspend fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>,
                                   realm: Realm) {
            sourceFiles[path] = sourceFile
        }
    }
}