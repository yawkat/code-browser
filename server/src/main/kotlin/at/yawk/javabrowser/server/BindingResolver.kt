package at.yawk.javabrowser.server

import at.yawk.javabrowser.Realm
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
@Singleton
class BindingResolver @Inject constructor(
        artifactUpdater: ArtifactUpdater,
        private val dbi: DBI
) {
    companion object {
        fun bindingHash(binding: String) = "#${URLEncoder.encode(binding, "UTF-8")}"

        fun location(artifactId: String, sourceFilePath: String, hash: String) =
                URI.create("/$artifactId/$sourceFilePath$hash")!!
    }

    private val caches = Realm.values().associate {
        it to CacheBuilder.newBuilder()
                .softValues()
                .build<String, List<BindingLocation>>(CacheLoader.from { binding -> resolveBinding0(it, binding!!) })
    }

    init {
        // too hard to just invalidate relevant bindings
        artifactUpdater.addInvalidationListener(runAtStart = false) { caches.values.forEach { it.invalidateAll() } }
    }

    private fun resolveBinding0(realm: Realm, binding: String): List<BindingLocation> {
        return dbi.inTransaction { conn: Handle, _ ->
            val candidates = conn.select("select artifactId, sourceFile from bindings where realm = ? and binding = ?", realm.id, binding)
            candidates.map {
                BindingLocation(it["artifactId"] as String, it["sourceFile"] as String, binding)
            }
        }
    }

    fun resolveBinding(realm: Realm, fromArtifacts: Set<String>, binding: String): List<URI> {
        val candidates = caches.getValue(realm)[binding]
        for (candidate in candidates) {
            if (candidate.artifact in fromArtifacts) {
                return listOf(candidate.uri)
            }
        }
        return candidates.map { it.uri }
    }

    private data class BindingLocation(
            val artifact: String,
            val sourceFile: String,
            val binding: String
    ) {
        val uri = location(artifact, sourceFile, bindingHash(binding))
    }
}