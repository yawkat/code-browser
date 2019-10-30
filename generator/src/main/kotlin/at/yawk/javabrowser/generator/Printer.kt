package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.Tokenizer

/**
 * @author yawkat
 */
interface Printer {
    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile, tokens: List<Tokenizer.Token>)

    class SimplePrinter : Printer {
        val sourceFiles: MutableMap<String, AnnotatedSourceFile> = HashMap()

        override fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile, tokens: List<Tokenizer.Token>) {
            sourceFiles[path] = sourceFile
        }
    }
}