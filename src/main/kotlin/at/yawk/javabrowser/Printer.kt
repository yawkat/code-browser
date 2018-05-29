package at.yawk.javabrowser

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
    private val sourceFiles = HashMap<String, AnnotatedSourceFile>()
    private val types = HashMap<String, String>()

    fun registerType(type: String, sourceFilePath: String) {
        types[type] = sourceFilePath
    }

    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        sourceFiles[path] = sourceFile
    }

    fun print(root: Path) {
        for ((name, asf) in sourceFiles) {
            val generatedName = generatedName(name)

            fun toNode(annotation: SourceAnnotation, members: List<Node>): List<Node> {
                val o = when (annotation) {
                    is TypeRef -> {
                        val tgt = types[annotation.binaryName] ?: return members
                        Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                            it.attr("href",
                                    generatedName.replace("[^/]+$".toRegex(), "").replace("[^/]+/".toRegex(),
                                            "../") + generatedName(tgt))
                        }
                    }
                }
                members.forEach { o.appendChild(it) }
                return listOf(o)
            }

            val document = Document.createShell(AnnotatedSourceFile.URI)

            val pre = document.body().appendElement("code").appendElement("pre")

            asf.toHtml(::toNode).forEach { pre.appendChild(it) }
            val to = root.resolve(generatedName)
            Files.createDirectories(to.parent)
            Files.write(to, document.html().toByteArray())
        }
    }

    private fun generatedName(name: String) = name.removeSuffix(".java").replace('.', '/') + ".html"
}