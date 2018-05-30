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
    private val sourceFiles = HashMap<String, AnnotatedSourceFile>()
    private val bindings = HashMap<String, String>()
    private val types = HashSet<String>()

    fun registerBinding(binding: String, sourceFilePath: String) {
        bindings[binding] = sourceFilePath
    }

    fun registerType(type: String) {
        types.add(type)
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

        val byPackage = HashMap<String, MutableList<String>>()
        for (type in types) {
            val sourceFilePath = bindings[type]!!
            var pkg = sourceFilePath
            while (!pkg.isEmpty()) {
                pkg = pkg.substring(0, pkg.lastIndexOf('/') + 1)
                byPackage.computeIfAbsent(pkg, { ArrayList() }).add(type)
            }
        }

        val objectMapper = ObjectMapper()
        for ((pkg, types) in byPackage) {
            types.sort()
            Files.newOutputStream(root.resolve(pkg + "package.json")).use {
                objectMapper.writeValue(it, types)
            }
        }
    }

    private fun generatedName(name: String) = name.removeSuffix(".java") + ".html"
}