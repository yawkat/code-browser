package at.yawk.javabrowser.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import javax.annotation.concurrent.ThreadSafe

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(SearchResource::class.java)

@ThreadSafe
class SearchResource(private val dbi: DBI,
                     private val objectMapper: ObjectMapper,
                     artifactUpdater: ArtifactUpdater) : HttpHandler {
    companion object {
        const val PATTERN = "/api/search/{query}"
    }

    private val searchIndex = SearchIndex<String, String>()

    init {
        artifactUpdater.addArtifactUpdateListener { artifactId ->
            dbi.inTransaction { conn: Handle, _ ->
                update(conn, artifactId)
            }
        }
    }

    fun firstUpdate() {
        dbi.inTransaction { conn: Handle, _ ->
            for (artifactId in conn.createQuery("select id from artifacts").map { _, r, _ -> r.getString(1) }) {
                update(conn, artifactId)
            }
        }
    }

    private fun update(conn: Handle, artifactId: String) {
        log.info("Triggering search index update for {}", artifactId)
        val itr = conn.createQuery("select binding, sourceFile from bindings where isType and artifactId = ?")
                .bind(0, artifactId)
                .map { _, r, _ ->
                    SearchIndex.Input(
                            string = r.getString(1),
                            value = r.getString(2))
                }
                .iterator()
        searchIndex.replace(artifactId, itr)
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val query = exchange.queryParameters["query"]?.peekFirst()
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Query not given")

        val artifactId = exchange.queryParameters["artifactId"]?.peekFirst()
        val limit = exchange.queryParameters["limit"]?.peekFirst()?.toInt() ?: 100
        val includeDependencies = exchange.queryParameters["includeDependencies"]?.peekFirst()?.toBoolean() ?: true

        val f = if (artifactId == null) {
            val seq = searchIndex.find(query)
            // avoid duplicating search results - prefer newer artifacts.
            sequence<SearchIndex.SearchResult<String, String>> {
                var prev: SearchIndex.SearchResult<String, String>? = null
                for (item in seq) {
                    if (prev != null) {
                        if (item.entry.name == prev.entry.name) {
                            val keyHere = item.key
                            val keyPrev = prev.key
                            if (VersionComparator.compare(keyHere, keyPrev) > 0) {
                                // 'prev' will be preferred over 'here' because it has a newer version.
                                continue
                            }
                        } else {
                            yield(prev)
                        }
                    }
                    prev = item
                }
                if (prev != null) yield(prev)
            }
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
            val componentLengths = IntArray(it.entry.name.componentsLower.size) { i -> it.entry.name.componentsLower[i].length }
            Result(it.key, it.entry.name.string, it.entry.value, componentLengths, it.match)
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