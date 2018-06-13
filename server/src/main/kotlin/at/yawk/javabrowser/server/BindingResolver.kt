package at.yawk.javabrowser.server

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URI
import java.net.URLEncoder

/**
 * @author yawkat
 */
class BindingResolver(private val dbi: DBI) {
    companion object {
        fun bindingHash(binding: String) = "#${URLEncoder.encode(binding, "UTF-8")}"

        fun location(artifactId: String, sourceFilePath: String, hash: String) =
                URI.create("/$artifactId/$sourceFilePath$hash")!!
    }

    private val cache: LoadingCache<String, List<BindingLocation>> = CacheBuilder.newBuilder()
            .maximumSize(100000)
            .build(CacheLoader.from { binding ->
                dbi.inTransaction { conn: Handle, _ ->
                    val candidates = conn.select(
                            "select artifactId, sourceFile from bindings where binding = ?", binding)

                    candidates.map { BindingLocation(it["artifactId"] as String, it["sourceFile"] as String, binding!!) }
                }
            })

    fun invalidate() {
        cache.invalidateAll()
    }

    fun resolveBinding(fromArtifact: String, binding: String): List<URI> {
        val candidates = cache[binding]
        for (candidate in candidates) {
            // todo: prefer dependencies
            if (candidate.artifactId == fromArtifact) {
                return listOf(candidate.uri)
            }
        }
        return candidates.map { it.uri }
    }

    private data class BindingLocation(
            val artifactId: String,
            val sourceFile: String,
            val binding: String
    ) {
        val uri = location(artifactId, sourceFile, bindingHash(binding))
    }
}