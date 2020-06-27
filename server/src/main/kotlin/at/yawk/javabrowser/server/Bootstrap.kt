package at.yawk.javabrowser.server

import at.yawk.javabrowser.DbConfig
import at.yawk.javabrowser.server.typesearch.SearchResource
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.google.inject.Guice
import com.google.inject.Module
import io.undertow.Undertow
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.BlockingHandler
import io.undertow.server.handlers.ExceptionHandler
import io.undertow.server.handlers.PathHandler
import io.undertow.server.handlers.PathTemplateHandler
import io.undertow.server.handlers.accesslog.AccessLogHandler
import io.undertow.server.handlers.resource.ClassPathResourceManager
import io.undertow.server.handlers.resource.Resource
import io.undertow.server.handlers.resource.ResourceChangeEvent
import io.undertow.server.handlers.resource.ResourceChangeListener
import io.undertow.server.handlers.resource.ResourceHandler
import io.undertow.server.handlers.resource.ResourceManager
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
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

    val guice = Guice.createInjector(Module { binder ->
        binder.bind(DBI::class.java).toInstance(config.database.start(mode = DbConfig.Mode.FRONTEND))
        binder.bind(Config::class.java).toInstance(config)
        binder.bind(ObjectMapper::class.java).toInstance(
                ObjectMapper().findAndRegisterModules()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        )
    })

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

    guice.getInstance(DBI::class.java).inTransaction { conn, _ ->
        conn.createScript("at/yawk/javabrowser/server/InitInteractiveSchema.sql").execute()
    }
    guice.getInstance(ArtifactUpdater::class.java).listenForUpdates(guice.getInstance(DBI::class.java))

    var handler: HttpHandler = PathTemplateHandler(guice.getInstance(BaseHandler::class.java)).also {
        it.add(SearchResource.PATTERN, guice.getInstance(SearchResource::class.java))
        it.add(ReferenceResource.PATTERN, guice.getInstance(ReferenceResource::class.java))
        it.add(ImageCache.PATTERN, guice.getInstance(ImageCache::class.java).handler)
        it.add(ReferenceDetailResource.PATTERN, guice.getInstance(ReferenceDetailResource::class.java))
        it.add(DeclarationTreeHandler.PATTERN, guice.getInstance(DeclarationTreeHandler::class.java))
        it.add(JavabotSearchResource.PATTERN, guice.getInstance(JavabotSearchResource::class.java))
        it.add(FullTextSearchResource.PATTERN, guice.getInstance(FullTextSearchResource::class.java))
    }

    handler = ExceptionHandler(handler).also(exceptions)
    handler = BlockingHandler(handler)
    handler = PathHandler(handler).also {
        val webjars = ResourceHandler(
                ClassPathResourceManager(object {}.javaClass.classLoader, "META-INF/resources/webjars"))
        webjars.cacheTime = TimeUnit.DAYS.toSeconds(1).toInt()
        it.addPrefixPath("/webjars", webjars)
        val assets = ClassPathResourceManager(object {}.javaClass.classLoader, "assets")
        it.addPrefixPath("/assets", ResourceHandler(assets))
        it.addPrefixPath("/robots.txt", ResourceHandler(ExactResourceManager("robots.txt", assets)))
    }
    handler = ExceptionHandler(handler).also(exceptions)
    handler = AccessLogHandler.Builder()
            .build(mapOf("format" to "common")).wrap(handler)

    val undertow = Undertow.builder()
            .addHttpListener(config.bindPort, config.bindAddress)
            .setHandler(handler)
            .build()
    undertow.start()

    guice.getInstance(SearchResource::class.java).firstUpdate()
}

private class ExactResourceManager(path: String,
                                   private val next: ResourceManager) : ResourceManager {
    private val path = if (path.startsWith('/')) path.substring(1) else path
    private val listeners = mutableMapOf<ResourceChangeListener, ResourceChangeListener>()

    override fun isResourceChangeListenerSupported() = next.isResourceChangeListenerSupported

    override fun getResource(path: String): Resource? =
            if (path == "" || path == "/") {
                next.getResource(this.path)
            } else {
                null
            }

    override fun registerResourceChangeListener(listener: ResourceChangeListener) {
        val wrapped = ResourceChangeListener { evts ->
            for (evt in evts) {
                if (evt.resource == path || evt.resource == "/$path") {
                    listener.handleChanges(listOf(ResourceChangeEvent("", evt.type)))
                }
            }
        }
        listeners[listener] = wrapped
        next.registerResourceChangeListener(wrapped)
    }

    override fun removeResourceChangeListener(listener: ResourceChangeListener) {
        next.removeResourceChangeListener(listeners.remove(listener)!!)
    }

    override fun close() {
        next.close()
    }
}