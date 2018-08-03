package at.yawk.javabrowser.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(SearchResource::class.java)

class SearchResource(private val dbi: DBI, private val objectMapper: ObjectMapper) : HttpHandler {
    companion object {
        const val PATTERN = "/api/search/{query}"
    }

    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.MINUTES,
            ArrayBlockingQueue(4),
            ThreadFactory { Thread(it, "Search index update worker").also { it.isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy())

    private val searchIndex = SearchIndex<String, String>()

    private val lastKnownVersion: ConcurrentMap<String, Int> = ConcurrentHashMap()

    private fun refresh() {
        dbi.inTransaction { conn: Handle, _ ->
            for ((artifactId, newVersion) in conn.createQuery("select id, lastCompileVersion from artifacts")
                    .map { _, r, _ -> r.getString(1) to r.getInt(2) }) {
                lastKnownVersion.compute(artifactId) { _, oldVersion ->
                    if (newVersion != oldVersion) {
                        log.info("Triggering search index update for {}", artifactId)
                        val itr = conn.createQuery("select binding, sourceFile from bindings where isType and artifactId = ?")
                                .bind(0, artifactId)
                                .map { _, r, _ -> r.getString(1) to r.getString(2) }
                                .iterator()
                        searchIndex.replace(artifactId, itr)
                    }
                    newVersion
                }
            }
        }
    }

    fun checkRefresh() {
        worker.execute {
            refresh()
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val query = exchange.pathParameters["query"]?.peekFirst() ?: ""

        val artifactId = exchange.queryParameters["artifactId"]?.peekFirst()
        val limit = exchange.queryParameters["limit"]?.peekFirst()?.toInt() ?: 100
        val includeDependencies = exchange.queryParameters["includeDependencies"]?.peekFirst()?.toBoolean() ?: true

        checkRefresh()

        val f = if (artifactId == null) {
            searchIndex.find(query)
        } else {
            val dependencies =
                    if (includeDependencies)
                        dbi.inTransaction { conn: Handle, _ ->
                            conn.attach(DependencyDao::class.java).getDependencies(artifactId)
                        }
                    else emptySet<String>()
            searchIndex.find(query, setOf(artifactId) + dependencies)
        }

        val response = Response(f.take(limit).map {
            val componentLengths = IntArray(it.entry.componentsLower.size) { i -> it.entry.componentsLower[i].length }
            Result(it.key, it.entry.string, it.entry.value, componentLengths, it.match)
        }.asIterable())

        objectMapper.writeValue(exchange.outputStream, response)
    }

    @Suppress("unused")
    class Response(
            @JsonSerialize(typing = JsonSerialize.Typing.STATIC)
            val items: Iterable<Result>
    )

    @Suppress("unused")
    class Result(
            val artifactId: String,
            val binding: String,
            val path: String,
            val components: IntArray,
            val match: IntArray
    )
}