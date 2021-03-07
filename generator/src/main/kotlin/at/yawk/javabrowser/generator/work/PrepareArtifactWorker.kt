package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.SourceSetConfig

interface PrepareArtifactWorker<A : ArtifactConfig> {
    fun getArtifactId(config: A): String

    /**
     * Prepare an artifact for compilation, and run [PrepareListener.compileSourceSet] for all of its source sets. [PrepareListener.compileSourceSet] must only return once compilation of that source set is complete – the source set becomes invalid once the [PrepareListener.compileSourceSet] call terminates.
     *
     * [PrepareListener.acceptMetadata] must be called exactly once, before any [PrepareListener.compileSourceSet] calls.
     */
    suspend fun prepareArtifact(
        artifactId: String,
        config: A,
        listener: PrepareListener
    )

    interface PrepareListener {
        fun acceptMetadata(metadata: Metadata)

        suspend fun compileSourceSet(config: SourceSetConfig)
    }

    data class Metadata(
        val dependencyArtifactIds: Collection<String>,
        val aliases: Collection<String> = emptyList(),
        val artifactMetadata: ArtifactMetadata
    )
}