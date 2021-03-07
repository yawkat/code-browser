package at.yawk.javabrowser.generator.bytecode

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.SourceSetConfig
import at.yawk.javabrowser.generator.source.Printer
import at.yawk.javabrowser.generator.source.SourceFileParser
import com.google.common.io.MoreFiles
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import org.objectweb.asm.ClassReader
import java.nio.file.Files
import java.nio.file.Path

fun testHashBinding(binding: String) = BindingId(binding.hashCode().toLong())

fun getOutput(
        @Language("java") code: String,
        filter: (Path) -> Boolean = { true },
        visitor: (BytecodePrinter, ClassReader) -> Unit
): String {
    val output = BytecodePrinter(::testHashBinding)

    val tmp = Files.createTempDirectory("MethodPrinterTest")
    try {
        Files.write(tmp.resolve("Main.java"), code.toByteArray())

        val out = tmp.resolve("out")
        Files.createDirectory(out)
        val sourceFileParser = SourceFileParser(object : Printer {
            override suspend fun addSourceFile(path: String,
                                       sourceFile: GeneratorSourceFile,
                                       tokens: List<Tokenizer.Token>,
                                       realm: Realm) {
            }

            override fun hashBinding(binding: String) = testHashBinding(binding)
        }, SourceSetConfig("MethodPrinterTest", tmp, outputClassesTo = out, dependencies = emptyList()))
        runBlocking {
            sourceFileParser.compile()
        }
        Files.newDirectoryStream(out).use { classes ->
            for (cl in classes.filter(filter)) {
                val classReader = ClassReader(Files.readAllBytes(cl))
                visitor(output, classReader)
            }
        }
    } finally {
        @Suppress("UnstableApiUsage")
        MoreFiles.deleteRecursively(tmp)
    }

    return output.finishString().trimIndent()
}