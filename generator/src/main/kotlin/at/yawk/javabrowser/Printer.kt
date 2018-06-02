package at.yawk.javabrowser

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Tag
import java.nio.file.Files
import java.nio.file.Path

/**
 * @author yawkat
 */
class Printer {
    val sourceFiles: MutableMap<String, AnnotatedSourceFile> = HashMap()
    val bindings: MutableMap<String, String> = HashMap()
    val types: MutableSet<String> = HashSet()

    fun registerBinding(binding: String, sourceFilePath: String) {
        bindings[binding] = sourceFilePath
    }

    fun registerType(type: String) {
        types.add(type)
    }

    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        sourceFiles[path] = sourceFile
    }

    private fun generatedName(name: String) = name.removeSuffix(".java") + ".html"
}