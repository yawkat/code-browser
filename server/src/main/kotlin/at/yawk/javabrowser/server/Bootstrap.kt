package at.yawk.javabrowser.server

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.DbMigration
import com.fasterxml.jackson.databind.DeserializationFeature
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
import java.util.concurrent.TimeUnit

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger("at.yawk.javabrowser.server.Bootstrap")

fun main(args: Array<String>) {
    val config = ObjectMapper(YAMLFactory()).findAndRegisterModules().readValue(File(args[0]), Config::class.java)

    val dbi = config.database.start(mode = DbConfig.Mode.FRONTEND)
    dbi.inTransaction { conn, _ -> DbMigration.initInteractiveSchema(conn) }
    val objectMapper = ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    val exceptions: (ExceptionHandler) -> Unit = {
        it.addExceptionHandler(Throwable::class.java) { exc ->
            var exception = exc.getAttachment(ExceptionHandler.THROWABLE)
            if (exception is CallbackFailedException) {
                exception = exception.cause
                if (exception is TransactionFailedException && exception.cause != null) {
                    exception = exception.cause
                }
            }
            if (exception is HttpException) {
                exc.statusCode = exception.status
                exc.responseHeaders.put(Headers.CONTENT_TYPE, "text/plain")
                exc.responseSender.send(exception.message)
            } else {
                log.error("Failed processing request", exc.getAttachment(ExceptionHandler.THROWABLE))
                if (!exc.isResponseStarted) {
                    exc.statusCode = StatusCodes.INTERNAL_SERVER_ERROR
                }
            }
        }
    }

    val updater = ArtifactUpdater(dbi)

    val bindingResolver = BindingResolver(updater, dbi)
    val imageCache = ImageCache()
    val ftl = Ftl()
    ftl.putDirective("imageCache", imageCache.directive)
    val artifactIndex = ArtifactIndex(updater, dbi)
    val packageTreeHandler = DeclarationTreeHandler(dbi, ftl, objectMapper)
    val baseHandler = BaseHandler(dbi, ftl, bindingResolver, objectMapper, artifactIndex, packageTreeHandler)
    val searchResource = SearchResource(dbi, objectMapper, updater)
    var handler: HttpHandler = PathTemplateHandler(baseHandler).also {
        it.add(SearchResource.PATTERN, searchResource)
        it.add(ReferenceResource.PATTERN, ReferenceResource(dbi, objectMapper))
        it.add(ImageCache.PATTERN, imageCache.handler)
        it.add(ReferenceDetailResource.PATTERN, ReferenceDetailResource(dbi, ftl))
        it.add(DeclarationTreeHandler.PATTERN, packageTreeHandler)
        it.add(JavabotSearchResource.PATTERN, JavabotSearchResource(dbi, objectMapper))
    }

    handler = ExceptionHandler(handler).also(exceptions)
    handler = BlockingHandler(handler)
    handler = PathHandler(handler).also {
        val webjars = ResourceHandler(
                ClassPathResourceManager(object {}.javaClass.classLoader, "META-INF/resources/webjars"))
        webjars.cacheTime = TimeUnit.DAYS.toSeconds(1).toInt()
        it.addPrefixPath("/webjars", webjars)
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

    searchResource.firstUpdate()
}