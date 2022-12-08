package at.yawk.javabrowser.server.typesearch

import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.server.AliasIndex
import at.yawk.javabrowser.server.ArtifactIndex
import at.yawk.javabrowser.server.ArtifactUpdater
import at.yawk.javabrowser.server.Config
import at.yawk.javabrowser.server.DependencyDao
import at.yawk.javabrowser.server.HttpException
import at.yawk.javabrowser.server.LongRunningDbi
import at.yawk.javabrowser.server.VersionComparator
import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.google.common.net.MediaType
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
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
        @param:LongRunningDbi private val dbi: Jdbi,
        private val objectMapper: ObjectMapper,
        artifactUpdater: ArtifactUpdater,
        private val aliasIndex: AliasIndex,
        private val artifactIndex: ArtifactIndex,
        config: Config
) : HttpHandler {
    companion object {
        const val PATTERN = "/api/search/{realm}/{query}"
    }

    private data class Category(val realm: Realm, val artifactId: Long, val artifactStringId: String)

    private val searchIndex = SearchIndex<Category, String>(
            chunkSize = config.typeIndexChunkSize,
            storageDir = config.typeIndexDirectory
    )

    init {
        artifactUpdater.addArtifactUpdateListener { stringId ->
            dbi.inTransaction<Unit, Exception> { conn: Handle ->
                val id = conn.select("select artifact_id from artifact where string_id = ?", stringId).mapToMap()
                        .single()["artifact_id"] as Number
                update(conn, id.toLong(), stringId)
            }
        }
    }

    fun firstUpdate() {
        dbi.inTransaction<Unit, Exception> { conn: Handle ->
            for ((artifactId, artifactStringId) in conn.createQuery("select artifact_id, string_id from artifact").map { r, _, _ -> r.getLong(1) to r.getString(2) }) {
                update(conn, artifactId, artifactStringId)
            }
        }
    }

    private fun update(conn: Handle, artifactId: Long, artifactStringId: String) {
        log.info("Triggering search index update for {}", artifactStringId)
        for (realm in Realm.values()) {
            val itr = conn.createQuery("select binding.binding, source_file.path from binding natural join source_file where realm = ? and include_in_type_search and artifact_id = ?")
                    .bind(0, realm.id)
                    .bind(1, artifactId)
                    .map { r, _, _ ->
                        SearchIndex.Input(
                                string = r.getString(1),
                                value = r.getString(2))
                    }
                    .iterator()
            searchIndex.replace(Category(realm, artifactId, artifactStringId), itr, when (realm) {
                Realm.SOURCE -> BindingTokenizer.Java
                Realm.BYTECODE -> BindingTokenizer.Bytecode
            })
        }
    }

    override fun handleRequest(exchange: HttpServerExchange) {
        val query = exchange.queryParameters["query"]?.peekFirst()
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Query not given")
        val realmName = exchange.queryParameters["realm"]?.peekFirst()
                ?: throw HttpException(StatusCodes.NOT_FOUND, "Realm not given")

        val realm = Realm.parse(realmName) ?: throw HttpException(StatusCodes.NOT_FOUND, "Realm not found")

        val artifactStringId = exchange.queryParameters["artifactId"]?.peekFirst()
        val limit = exchange.queryParameters["limit"]?.peekFirst()?.toInt() ?: 100
        val includeDependencies = exchange.queryParameters["includeDependencies"]?.peekFirst()?.toBoolean() ?: true

        val f = if (artifactStringId == null) {
            val seq = searchIndex.find(query, searchIndex.getCategories().filterTo(HashSet()) { it.realm == realm })
            // avoid duplicating search results - prefer newer artifacts.
            sequence<SearchIndex.SearchResult<Category, String>> {
                var prev: SearchIndex.SearchResult<Category, String>? = null
                for (item in seq) {
                    if (prev != null) {
                        if (item.entry.name == prev.entry.name) {
                            val keyHere = item.key.artifactStringId
                            val keyPrev = prev.key.artifactStringId
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
            val artifact = artifactIndex.allArtifactsByStringId[artifactStringId]
                    ?: throw HttpException(StatusCodes.NOT_FOUND, "No such artifact")
            if (artifact.dbId == null) throw HttpException(StatusCodes.NOT_FOUND, "Not a leaf artifact")
            val dependencies =
                    if (includeDependencies)
                        dbi.inTransaction<List<String>, Exception> { conn: Handle ->
                            conn.attach(DependencyDao::class.java).getDependencies(artifact.dbId)
                        }.mapNotNull(::findDependencyNode)
                    else emptySet<ArtifactNode>()
            searchIndex.find(query, (listOf(artifact) + dependencies).mapTo(HashSet()) { Category(realm, it.dbId!!, it.stringId) })
        }

        val response = Response(f.take(limit).map {
            val componentLengths = IntArray(it.entry.name.componentsLower.size) { i -> it.entry.name.componentsLower[i].length }
            Result(it.key.artifactStringId, it.entry.name.string, it.entry.value, componentLengths, it.match)
        }.asIterable())

        @Suppress("UnstableApiUsage")
        exchange.responseHeaders.put(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
        objectMapper.writeValue(exchange.outputStream, response)
    }

    private fun findDependencyNode(dependencyId: String): ArtifactNode? {
        val ceiling = artifactIndex.allArtifactsByStringId.ceilingEntry(dependencyId)
        if (ceiling != null) {
            if (ceiling.value.children.isEmpty()) {
                // this exact version or a newer version is available. use that.
                return ceiling.value
            }
            val floor = artifactIndex.allArtifactsByStringId.ceilingEntry(dependencyId)
            if (floor != null
                    && floor.value.parent?.stringId == ceiling.key
                    && floor.value.children.isEmpty()) {
                // an older version is available.
                return floor.value
            }
        }
        // nothing in the artifact index :( check for aliases
        return aliasIndex.findAliasedTo(dependencyId)?.let { artifactIndex.allArtifactsByDbId[it] }
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