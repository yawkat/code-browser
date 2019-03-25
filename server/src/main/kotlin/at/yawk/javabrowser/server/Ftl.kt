package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.view.View
import freemarker.core.HTMLOutputFormat
import freemarker.template.Configuration
import freemarker.template.TemplateDirectiveModel
import freemarker.template.TemplateExceptionHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import java.io.OutputStreamWriter

/**
 * @author yawkat
 */
class Ftl {
    private val configuration = Configuration(Configuration.VERSION_2_3_28)

    init {
        configuration.setClassLoaderForTemplateLoading(Ftl::class.java.classLoader, "/at/yawk/javabrowser/server/view")
        configuration.defaultEncoding = "UTF-8"
        configuration.templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        configuration.logTemplateExceptions = false
        configuration.wrapUncheckedExceptions = true
        configuration.outputFormat = HTMLOutputFormat.INSTANCE
    }

    fun render(exchange: HttpServerExchange, view: View) {
        val template = configuration.getTemplate(view.templateFile)
        exchange.responseHeaders.put(Headers.CONTENT_TYPE, "text/html")
        OutputStreamWriter(exchange.outputStream).use {
            template.process(view, it)
        }
    }

    fun putDirective(name: String, directive: TemplateDirectiveModel) {
        configuration.setSharedVariable(name, directive)
    }
}