package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.LocalVariableRef
import at.yawk.javabrowser.SourceAnnotation
import at.yawk.javabrowser.Style
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.Escaper
import at.yawk.javabrowser.server.SourceFilePrinter
import at.yawk.javabrowser.server.artifact.ArtifactNode
import freemarker.core.Environment
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateModel
import org.intellij.lang.annotations.Language
import java.io.Writer

/**
 * @author yawkat
 */
@Suppress("unused")
class SourceFileView(
        val newInfo: FileInfo,
        val oldInfo: FileInfo?,
        val alternatives: List<Alternative>,
        val artifactMetadata: ArtifactMetadata,
        val declarations: Iterator<DeclarationNode>,

        private val bindingResolver: BindingResolver
) : View("source-file.ftl") {
    class FileInfo(
            val artifactId: ArtifactNode,
            val sourceFile: AnnotatedSourceFile,
            val classpath: Set<String>,
            sourceFilePath: String
    ) {
        val sourceFilePathDir: String
        val sourceFilePathFile: String

        init {
            val separator = sourceFilePath.lastIndexOf('/')
            sourceFilePathDir = sourceFilePath.substring(0, separator + 1)
            sourceFilePathFile = sourceFilePath.substring(separator + 1)
        }
    }

    val diff = oldInfo?.let { SourceFilePrinter.Diff(newInfo.sourceFile, it.sourceFile) }

    val printerDirective = PrinterDirective()

    data class Alternative(
            val artifactId: String,
            val sourceFilePath: String,
            val diffPath: String?
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
                SourceFilePrinter.toHtmlSingle(emitter, newInfo.sourceFile)
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
            val cp = if (scope == SourceFilePrinter.Scope.OLD) oldInfo!!.classpath else newInfo.classpath
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
                    "<a href='${Escaper.HTML.escape(uri)}'" +
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
                        val superUris = (memory as EmitterImplMemory.Decl).superBindingUris
                        val superHtml = if (!annotation.superBindings.isEmpty()) {
                            "<ul>" +
                                    annotation.superBindings.withIndex().joinToString { (i, binding) ->
                                        "<li>" +
                                                linkBindingStart(scope, superUris[i], refId = null) +
                                                Escaper.HTML.escape(binding.name) +
                                                "</li>"
                                    } +
                                    "</ul>"
                        } else {
                            ""
                        }
                        html("<a class='show-refs' href='javascript:;' onclick='showReferences(this); return false' data-binding='${Escaper.HTML.escape(annotation.binding)}' data-super-html='${Escaper.HTML.escape(superHtml)}'></a>")
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

        override fun html(@Language("HTML") s: String) {
            writer.write(s)
        }

        override fun text(s: String, start: Int, end: Int) {
            Escaper.HTML.escape(writer, s, start, end)
        }
    }
}
