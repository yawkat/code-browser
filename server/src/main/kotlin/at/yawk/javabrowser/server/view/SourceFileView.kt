package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.LocalVariableRef
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.Style
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.SourceFilePrinter
import at.yawk.javabrowser.server.appendChildren
import at.yawk.javabrowser.server.artifact.ArtifactNode
import freemarker.core.Environment
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateModel
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag
import java.io.Writer

/**
 * @author yawkat
 */
@Suppress("unused")
class SourceFileView(
        val artifactId: ArtifactNode,
        private val classpath: Set<String>,
        private val classpathOld: Set<String>?,
        val sourceFilePathDir: String,
        val sourceFilePathFile: String,
        val alternatives: List<Alternative>,
        val artifactMetadata: ArtifactMetadata,
        val declarations: Iterator<DeclarationNode>,

        private val bindingResolver: BindingResolver,
        private val sourceFile: AnnotatedSourceFile,
        sourceFileOld: AnnotatedSourceFile?
) : View("source-file.ftl") {
    val diff = sourceFileOld?.let { SourceFilePrinter.Diff(sourceFile, it) }

    val printerDirective = PrinterDirective()

    private fun toNode(scopePrefix: String,
                       annotation: SourceAnnotation,
                       members: List<Node>): List<Node> = when (annotation) {
        is BindingRef -> {
            linkToBinding(members, annotation.binding, annotation.id)
        }
        is BindingDecl -> {
            val showDeclaration = when (annotation.description) {
                is BindingDecl.Description.Initializer -> false
                else -> true
            }

            val id = scopePrefix + annotation.binding

            val link = Element(Tag.valueOf("a"), AnnotatedSourceFile.URI)
            link.attr("id", id)
            link.attr("href", BindingResolver.bindingHash(id))
            link.appendChildren(members)

            if (showDeclaration) {
                val moreInfo = Element(Tag.valueOf("a"), AnnotatedSourceFile.URI)
                moreInfo.attr("class", "show-refs")

                val superHtml = if (!annotation.superBindings.isEmpty()) {
                    val superList = Element(Tag.valueOf("ul"), AnnotatedSourceFile.URI)
                    for (superBinding in annotation.superBindings) {
                        val entry = superList.appendElement("li")
                        entry.appendChildren(linkToBinding(
                                listOf(TextNode(superBinding.name, AnnotatedSourceFile.URI)),
                                superBinding.binding,
                                refId = null
                        ))
                    }
                    StringEscapeUtils.escapeEcmaScript(superList.html())
                } else {
                    ""
                }
                moreInfo.attr("href", "javascript:showReferences('${annotation.binding}', '$superHtml')")

                listOf(moreInfo, link)
            } else {
                listOf(link)
            }
        }
        is Style -> listOf(Element(Tag.valueOf("span"), AnnotatedSourceFile.URI).also {
            it.attr("class", annotation.styleClass.joinToString(" "))
            it.appendChildren(members)
        })
        is LocalVariableRef -> listOf(Element(Tag.valueOf("span"), AnnotatedSourceFile.URI).also {
            it.attr("class", "local-variable")
            it.attr("data-local-variable", annotation.id)
            it.appendChildren(members)
        })
    }

    private fun linkToBinding(members: List<Node>,
                              binding: String,
                              refId: Int?): List<Node> {
        val uris = bindingResolver.resolveBinding(classpath, binding)
        return if (uris.isEmpty()) {
            members
        } else {
            listOf(Element(Tag.valueOf("a"), AnnotatedSourceFile.URI).also {
                it.attr("href", uris[0].toASCIIString())
                if (refId != null) it.attr("id", "ref-$refId")
                it.appendChildren(members)
            })
        }
    }

    data class Alternative(
            val artifactId: String,
            val sourceFilePath: String
    )

    inner class PrinterDirective : TemplateDirectiveModel {
        override fun execute(env: Environment,
                             params: MutableMap<Any?, Any?>,
                             loopVars: Array<TemplateModel?>,
                             body: TemplateDirectiveBody?) {
            val emitter = EmitterImpl(env.out)
            if (diff != null) {
                diff.toHtml(emitter)
            } else {
                SourceFilePrinter.toHtmlSingle(emitter, sourceFile)
            }
        }
    }

    private sealed class EmitterImplMemory {
        object None : EmitterImplMemory()
        class ResolvedBinding(val uri: String?) : EmitterImplMemory()
        class Decl(val superBindingUris: List<String?>) : EmitterImplMemory()
    }

    private inner class EmitterImpl(private val writer: Writer) : SourceFilePrinter.Emitter<EmitterImplMemory> {
        private val SourceFilePrinter.Scope.prefix: String
            get() = if (this == SourceFilePrinter.Scope.OLD) "--- " else ""

        override fun computeMemory(scope: SourceFilePrinter.Scope, annotation: SourceAnnotation): EmitterImplMemory {
            val cp = if (scope == SourceFilePrinter.Scope.OLD) classpathOld!! else classpath
            return when (annotation) {
                is BindingRef -> EmitterImplMemory.ResolvedBinding(
                        bindingResolver.resolveBinding(cp, annotation.binding).firstOrNull()?.toASCIIString())
                is BindingDecl -> EmitterImplMemory.Decl(
                        annotation.superBindings.map {
                            bindingResolver.resolveBinding(cp, it.binding).firstOrNull()?.toASCIIString()
                        })
                else -> EmitterImplMemory.None
            }
        }

        private fun linkBindingStart(scope: SourceFilePrinter.Scope, uri: String?, refId: Int?) =
                if (uri != null) {
                    "<a href='${StringEscapeUtils.escapeHtml4(uri)}'" +
                            (if (refId != null) " id='${scope.prefix}ref-$refId'" else "") +
                            ">"
                } else {
                    ""
                }

        private fun linkBindingEnd(uri: String?): String =
                if (uri != null) {
                    "</a>"
                } else {
                    ""
                }

        override fun startAnnotation(scope: SourceFilePrinter.Scope,
                                     annotation: SourceAnnotation,
                                     memory: EmitterImplMemory) {
            when (annotation) {
                is BindingRef -> html(linkBindingStart(
                        scope,
                        (memory as EmitterImplMemory.ResolvedBinding).uri,
                        annotation.id))
                is BindingDecl -> {
                    val showDeclaration = when (annotation.description) {
                        is BindingDecl.Description.Initializer -> false
                        else -> true
                    }

                    if (showDeclaration) {
                        html("<a class='show-refs'")
                        val superUris = (memory as EmitterImplMemory.Decl).superBindingUris
                        val superHtml = if (!annotation.superBindings.isEmpty()) {
                            val h = "<ul>" +
                                    annotation.superBindings.withIndex().joinToString { (i, binding) ->
                                        "<li>" +
                                                linkBindingStart(scope, superUris[i], refId = null) +
                                                StringEscapeUtils.escapeHtml4(binding.name) +
                                                "</li>"
                                    } +
                                    "</ul>"
                            StringEscapeUtils.escapeHtml4(StringEscapeUtils.escapeEcmaScript(h))
                        } else {
                            ""
                        }
                        html("<a class='show-refs' href='javascript:showReferences(\"${annotation.binding}\", \"$superHtml\")'></a>")
                    }

                    val id = scope.prefix + annotation.binding
                    html("<a id='$id' href='${BindingResolver.bindingHash(id)}'>")
                }
                is Style -> html("<span class='${annotation.styleClass.joinToString(" ")}'>")
                is LocalVariableRef -> html("<span class='local-variable' data-local-variable='${annotation.id}'>")
            }
        }

        override fun endAnnotation(scope: SourceFilePrinter.Scope,
                                   annotation: SourceAnnotation,
                                   memory: EmitterImplMemory) {
            when (annotation) {
                is BindingRef -> html(linkBindingEnd((memory as EmitterImplMemory.ResolvedBinding).uri))
                is BindingDecl -> html("</a>")
                is Style, is LocalVariableRef -> html("</span>")
            }
        }

        override fun html(s: String) {
            writer.write(s)
        }

        override fun text(s: String, start: Int, end: Int) {
            StringEscapeUtils.ESCAPE_HTML4.translate(s.substring(start, end), writer)
        }
    }
}
