package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.Printer
import at.yawk.javabrowser.generator.SourceFileParser
import com.google.common.io.MoreFiles
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import java.nio.file.Files

fun getOutput(
        @Language("java") code: String,
        visitor: (BytecodePrinter) -> ClassVisitor
): String {
    val output = BytecodePrinter()

    val tmp = Files.createTempDirectory("MethodPrinterTest")
    try {
        Files.write(tmp.resolve("Main.java"), code.toByteArray())

        val out = tmp.resolve("out")
        Files.createDirectory(out)
        val sourceFileParser = SourceFileParser(tmp, object : Printer {
            override fun addSourceFile(path: String,
                                       sourceFile: GeneratorSourceFile,
                                       tokens: List<Tokenizer.Token>) {
            }
        })
        sourceFileParser.outputClassesTo = out
        runBlocking {
            sourceFileParser.compile()
        }
        Files.newDirectoryStream(out).use { classes ->
            for (cl in classes) {
                val classReader = ClassReader(Files.readAllBytes(cl))
                classReader.accept(visitor(output), 0)
            }
        }
    } finally {
        @Suppress("UnstableApiUsage")
        MoreFiles.deleteRecursively(tmp)
    }

    return output.finishString().trimIndent()
}