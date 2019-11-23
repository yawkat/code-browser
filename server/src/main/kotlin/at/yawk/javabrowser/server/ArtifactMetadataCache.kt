package at.yawk.javabrowser.server

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtifactMetadataCache @Inject private constructor(
        private val objectMapper: ObjectMapper,
        private val dbi: DBI,
        artifactUpdater: ArtifactUpdater
) {
    private val cache = CacheBuilder.newBuilder()
            .build(object : CacheLoader<String, ArtifactMetadata>() {
                override fun load(key: String): ArtifactMetadata {
                    return dbi.inTransaction { conn: Handle, _ ->
                        val bytes = conn.select("select metadata from artifacts where id = ?", key)
                                .single()["metadata"] as ByteArray
                        objectMapper.readValue(bytes, ArtifactMetadata::class.java)
                    }
                }
            })

    init {
        artifactUpdater.addArtifactUpdateListener { cache.invalidate(it) }
    }

    fun getArtifactMetadata(node: ArtifactNode) = cache.get(node.id)!!
}