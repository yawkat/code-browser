package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile

/**
 * @author yawkat
 */
interface Printer {
    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile)

    class SimplePrinter : Printer {
        val sourceFiles: MutableMap<String, AnnotatedSourceFile> = HashMap()

        override fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
            sourceFiles[path] = sourceFile
        }
    }
}