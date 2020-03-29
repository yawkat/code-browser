package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.ServerSourceFile
import at.yawk.javabrowser.server.SourceFilePrinter
import at.yawk.javabrowser.server.artifact.ArtifactNode
import freemarker.core.Environment
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateModel

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
            val sourceFile: ServerSourceFile,
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
            val newScopeInfo = HtmlEmitter.ScopeInfo(newInfo.artifactId.id, newInfo.classpath)
            val emitter = HtmlEmitter(
                    bindingResolver,
                    if (oldInfo != null)
                        mapOf(SourceFilePrinter.Scope.OLD to
                                HtmlEmitter.ScopeInfo(oldInfo.artifactId.id, oldInfo.classpath),
                                SourceFilePrinter.Scope.NEW to newScopeInfo)
                    else
                        mapOf(SourceFilePrinter.Scope.NORMAL to newScopeInfo),
                    env.out,

                    hasOverlay = true,
                    referenceThisUrl = true
            )
            if (diff != null) {
                diff.toHtml(emitter)
            } else {
                SourceFilePrinter.toHtmlSingle(emitter, newInfo.sourceFile)
            }
        }
    }
}
