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
    private val bindings = HashMap<String, String>()

    fun registerBinding(binding: String, sourceFilePath: String) {
        bindings[binding] = sourceFilePath
    }

    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        sourceFiles[path] = sourceFile
    }

    fun print(root: Path) {
        for ((name, asf) in sourceFiles) {
            val generatedName = generatedName(name)
            val toRoot = generatedName.replace("[^/]+$".toRegex(), "").replace("[^/]+/".toRegex(), "../")

            fun toNode(annotation: SourceAnnotation, members: List<Node>): List<Node> {
                val o = when (annotation) {
                    is BindingRef -> {
                        val tgt = bindings[annotation.binding] ?: return members
                        Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                            it.attr("href", toRoot + generatedName(tgt) + "#" + annotation.binding)
                        }
                    }
                    is BindingDecl -> {
                        Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                            it.attr("id", annotation.binding)
                        }
                    }
                    is Style -> {
                        Element(Tag.valueOf("span"), AnnotatedSourceFile.URI).also {
                            it.attr("class", annotation.styleClass.joinToString(" "))
                        }
                    }
                }
                members.forEach { o.appendChild(it) }
                return listOf(o)
            }

            val document = Document.createShell(AnnotatedSourceFile.URI)

            val styleLink = document.head().appendElement("link")
            styleLink.attr("rel", "stylesheet")
            styleLink.attr("href", "$toRoot../code.css")

            val pre = document.body().appendElement("code").appendElement("pre")

            asf.toHtml(::toNode).forEach { pre.appendChild(it) }
            val to = root.resolve(generatedName)
            Files.createDirectories(to.parent)
            Files.write(to, document.html().toByteArray())
        }
    }

    private fun generatedName(name: String) = name.removeSuffix(".java").replace('.', '/') + ".html"
}