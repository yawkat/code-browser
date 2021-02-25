package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.ParsedPath
import at.yawk.javabrowser.server.ServerSourceFile
import at.yawk.javabrowser.server.SourceFilePrinter
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
        val realm: Realm,
        val sourceFile: ServerSourceFile,
        val classpath: Set<String>,
        val sourceFilePath: ParsedPath.SourceFile
    )

    val diff = oldInfo?.let { SourceFilePrinter.Diff(newInfo.sourceFile, it.sourceFile) }

    val printerDirective = PrinterDirective()

    inner class PrinterDirective : TemplateDirectiveModel {
        override fun execute(env: Environment,
                             params: MutableMap<Any?, Any?>,
                             loopVars: Array<TemplateModel?>,
                             body: TemplateDirectiveBody?) {
            val newScopeInfo =
                HtmlEmitter.ScopeInfo(newInfo.realm, newInfo.sourceFilePath.artifact.stringId, newInfo.classpath)
            val emitter = HtmlEmitter(
                bindingResolver,
                if (oldInfo != null)
                    mapOf(
                        SourceFilePrinter.Scope.OLD to
                                HtmlEmitter.ScopeInfo(
                                    oldInfo.realm,
                                    oldInfo.sourceFilePath.artifact.stringId,
                                    oldInfo.classpath
                                ),
                        SourceFilePrinter.Scope.NEW to newScopeInfo
                    )
                else
                    mapOf(SourceFilePrinter.Scope.NORMAL to newScopeInfo),
                    env.out,

                    hasOverlay = true,
                    referenceThisUrl = true,
                    renderJavadoc = diff == null
            )
            if (diff != null) {
                diff.toHtml(emitter)
            } else {
                SourceFilePrinter.toHtmlSingle(emitter, newInfo.sourceFile)
            }
        }
    }
}
