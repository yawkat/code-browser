package at.yawk.javabrowser.server

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.server.artifact.ArtifactNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtifactMetadataCache @Inject constructor(
        private val objectMapper: ObjectMapper,
        private val dbi: Jdbi,
        artifactUpdater: ArtifactUpdater
) {
    private val cache = CacheBuilder.newBuilder()
            .build(object : CacheLoader<Long, ArtifactMetadata>() {
                override fun load(key: Long): ArtifactMetadata {
                    return dbi.inTransaction<ArtifactMetadata, Exception> { conn: Handle ->
                        val bytes = conn.select("select metadata from artifact where artifact_id = ?", key).mapToMap()
                                .single()["metadata"] as ByteArray
                        objectMapper.readValue(bytes, ArtifactMetadata::class.java)
                    }
                }
            })

    init {
        artifactUpdater.addArtifactUpdateListener { cache.invalidate(it) }
    }

    fun getArtifactMetadata(node: ArtifactNode) = cache.get(node.dbId!!)!!
}