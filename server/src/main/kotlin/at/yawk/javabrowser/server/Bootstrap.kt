package at.yawk.javabrowser.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.BlockingHandler
import io.undertow.server.handlers.ExceptionHandler
import io.undertow.server.handlers.PathHandler
import io.undertow.server.handlers.PathTemplateHandler
import io.undertow.server.handlers.accesslog.AccessLogHandler
import io.undertow.server.handlers.resource.ClassPathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.exceptions.CallbackFailedException
import org.skife.jdbi.v2.exceptions.TransactionFailedException
import org.slf4j.LoggerFactory
import java.io.File

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.server.Bootstrap")

fun main(args: Array<String>) {
    val config = ObjectMapper(YAMLFactory()).findAndRegisterModules().readValue(File(args[0]), Config::class.java)

    val dbi = config.database.start()
    val objectMapper = ObjectMapper().findAndRegisterModules()

    val exceptions: (ExceptionHandler) -> Unit = {
        it.addExceptionHandler(HttpException::class.java) {

        }
        it.addExceptionHandler(Throwable::class.java) {
            var exception = it.getAttachment(ExceptionHandler.THROWABLE)
            if (exception is CallbackFailedException) {
                exception = exception.cause
                if (exception is TransactionFailedException && exception.cause != null) {
                    exception = exception.cause
                }
            }
            if (exception is HttpException) {
                it.statusCode = exception.status
                it.responseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
                it.responseSender.send(exception.message)
            } else {
                log.error("Failed processing request", it.getAttachment(ExceptionHandler.THROWABLE))
                if (!it.isResponseStarted) {
                    it.statusCode = StatusCodes.INTERNAL_SERVER_ERROR
                }
            }
        }
    }

    val bindingResolver = BindingResolver(dbi)
    var handler: HttpHandler = PathTemplateHandler(BaseHandler(dbi, Ftl(), bindingResolver, objectMapper)).also {
        it.add(SearchResource.PATTERN, SearchResource(dbi, objectMapper).also { it.checkRefresh() })
        it.add(ReferenceResource.PATTERN, ReferenceResource(dbi, objectMapper))
    }

    handler = ExceptionHandler(handler).also(exceptions)
    handler = BlockingHandler(handler)
    handler = PathHandler(handler).also {
        it.addPrefixPath("/webjars", ResourceHandler(
                ClassPathResourceManager(object {}.javaClass.classLoader, "META-INF/resources/webjars")))
        it.addPrefixPath("/assets", ResourceHandler(
                ClassPathResourceManager(object {}.javaClass.classLoader, "assets")))
    }
    handler = ExceptionHandler(handler).also(exceptions)
    handler = AccessLogHandler.Builder()
            .build(mapOf("format" to "common")).wrap(handler)

    val undertow = Undertow.builder()
            .addHttpListener(config.bindPort, config.bindAddress)
            .setHandler(handler)
            .build()
    undertow.start()
}