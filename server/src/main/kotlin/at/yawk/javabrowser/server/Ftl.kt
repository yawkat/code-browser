package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.View
import freemarker.core.HTMLOutputFormat
import freemarker.ext.beans.BeansWrapperBuilder
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import java.io.OutputStreamWriter
import java.lang.reflect.Modifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
@Singleton
class Ftl @Inject constructor(imageCache: ImageCache) {
    private val configuration = Configuration(Configuration.VERSION_2_3_28)

    init {
        configuration.setClassLoaderForTemplateLoading(Ftl::class.java.classLoader, "/at/yawk/javabrowser/server/view")
        configuration.defaultEncoding = "UTF-8"
        configuration.templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        configuration.logTemplateExceptions = false
        configuration.wrapUncheckedExceptions = true
        configuration.outputFormat = HTMLOutputFormat.INSTANCE
        configuration.whitespaceStripping = true
        configuration.urlEscapingCharset = "UTF-8"

        val statics = BeansWrapperBuilder(Configuration.VERSION_2_3_28).build().staticModels
        configuration.setSharedVariable("Modifier", statics[Modifier::class.qualifiedName])
        configuration.setSharedVariable("ConservativeLoopBlock", ConservativeLoopBlock())

        configuration.setSharedVariable("imageCache", imageCache.directive)
    }

    fun render(exchange: HttpServerExchange, view: View) {
        val template = configuration.getTemplate(view.templateFile)
        exchange.responseHeaders.put(Headers.CONTENT_TYPE, "text/html")
        OutputStreamWriter(exchange.outputStream).use {
            template.process(view, it)
        }
    }
}