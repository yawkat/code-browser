package at.yawk.javabrowser.server

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
            .refreshAfterWrite(5, TimeUnit.MINUTES)
            .build(CacheLoader.asyncReloading(CacheLoader.from { binding ->
                resolveBinding0(binding!!)
            }, ThreadPoolExecutor(
                    0, 4, 1, TimeUnit.MINUTES,
                    ArrayBlockingQueue(10000),
                    ThreadFactory { Thread(it, "Binding resolver update worker").also { it.isDaemon = true } },
                    RejectedExecutionHandler { r, _ ->
                        (r as FutureTask<*>).cancel(false)
                    }
            )))

    private fun resolveBinding0(binding: String): List<BindingLocation> {
        return dbi.inTransaction { conn: Handle, _ ->
            val candidates = conn.select("select artifactId, sourceFile from bindings where binding = ?", binding)
            candidates.map {
                BindingLocation(it["artifactId"] as String, it["sourceFile"] as String, binding)
            }
        }
    }

    fun resolveBinding(fromArtifacts: Set<String>, binding: String): List<URI> {
        val candidates = cache[binding]
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