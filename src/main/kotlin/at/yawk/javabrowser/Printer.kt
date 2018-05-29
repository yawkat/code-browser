package at.yawk.javabrowser

import org.eclipse.jdt.core.dom.IBinding
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

    fun registerType(binding: String, sourceFilePath: String) {
        bindings[binding] = sourceFilePath
    }

    fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        sourceFiles[path] = sourceFile
    }

    fun print(root: Path) {
        for ((name, asf) in sourceFiles) {
            val generatedName = generatedName(name)

            fun toNode(annotation: SourceAnnotation, members: List<Node>): List<Node> {
                val o = when (annotation) {
                    is BindingRef -> {
                        val tgt = bindings[annotation.binding] ?: return members
                        Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                            it.attr("href",
                                    generatedName.replace("[^/]+$".toRegex(), "").replace("[^/]+/".toRegex(),
                                            "../") + generatedName(tgt) + "#" + annotation.binding)
                        }
                    }
                    is BindingDecl -> {
                        return listOf(Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                            it.attr("id", annotation.binding)
                        }) + members
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