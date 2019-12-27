package at.yawk.javabrowser.server.typesearch

import at.yawk.javabrowser.server.AliasIndex
import at.yawk.javabrowser.server.ArtifactIndex
import at.yawk.javabrowser.server.ArtifactUpdater
import at.yawk.javabrowser.server.Config
import at.yawk.javabrowser.server.DependencyDao
import at.yawk.javabrowser.server.HttpException
import at.yawk.javabrowser.server.VersionComparator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.google.common.net.MediaType
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import javax.annotation.concurrent.ThreadSafe
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(SearchResource::class.java)

@ThreadSafe
@Singleton
class SearchResource @Inject constructor(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper,
        artifactUpdater: ArtifactUpdater,
        private val aliasIndex: AliasIndex,
        private val artifactIndex: ArtifactIndex,
        config: Config
) : HttpHandler {
    companion object {
        const val PATTERN = "/api/search/{query}"
    }

    private val searchIndex = SearchIndex<String, String>(
            chunkSize = config.typeIndexChunkSize,
            storageDir = config.typeIndexDirectory
    )

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
                        }.mapNotNull { dependencyId ->
                            val ceiling = artifactIndex.allArtifacts.ceilingEntry(dependencyId)
                            if (ceiling != null) {
                                if (ceiling.value.children.isEmpty()) {
                                    // this exact version or a newer version is available. use that.
                                    return@mapNotNull ceiling.key
                                }
                                val floor = artifactIndex.allArtifacts.ceilingEntry(dependencyId)
                                if (floor != null
                                        && floor.value.parent?.id == ceiling.key
                                        && floor.value.children.isEmpty()) {
                                    // an older version is available.
                                    return@mapNotNull floor.key
                                }
                            }
                            // nothing in the artifact index :( check for aliases
                            return@mapNotNull aliasIndex.findAliasedTo(dependencyId)
                        }
                    else emptySet<String>()
            searchIndex.find(query, setOf(artifactId) + dependencies)
        }

        val response = Response(f.take(limit).map {
            val componentLengths = IntArray(it.entry.name.componentsLower.size) { i -> it.entry.name.componentsLower[i].length }
            Result(it.key, it.entry.name.string, it.entry.value, componentLengths, it.match)
        }.asIterable())

        exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
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