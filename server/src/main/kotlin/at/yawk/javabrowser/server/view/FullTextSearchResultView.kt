package at.yawk.javabrowser.server.view

import at.yawk.javabrowser.server.BindingResolver
import at.yawk.javabrowser.server.SourceFilePrinter
import at.yawk.javabrowser.server.artifact.ArtifactNode
import freemarker.core.Environment
import freemarker.template.TemplateDirectiveBody
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateModel
import java.net.URI

/**
 * @author yawkat
 */
private const val CONTEXT_LINES = 2

class FullTextSearchResultView(
        val query: String,
        val searchArtifact: ArtifactNode?,
        val results: Iterator<SourceFileResult>
) : View("fullTextSearchResultView.ftl") {
    class SourceFileResult(
            private val bindingResolver: BindingResolver,

            val artifactId: String,
            val path: String,
            private val classpath: Set<String>,
            partial: SourceFilePrinter.Partial
    ) {
        val renderer = partial.createRenderer<HtmlEmitter.Memory>(CONTEXT_LINES, CONTEXT_LINES)

        val renderNextRegionDirective = PrinterDirective()

        inner class PrinterDirective : TemplateDirectiveModel {
            override fun execute(env: Environment,
                                 params: MutableMap<Any?, Any?>,
                                 loopVars: Array<TemplateModel?>,
                                 body: TemplateDirectiveBody?) {
                val emitter = HtmlEmitter(
                        bindingResolver,
                        mapOf(SourceFilePrinter.Scope.NORMAL to classpath),
                        env.out,

                        hasOverlay = false,
                        referenceThisUrl = false,
                        ownUri = URI.create("/$artifactId/$path")
                )
                renderer.renderNextRegion(emitter)
            }
        }
    }
}