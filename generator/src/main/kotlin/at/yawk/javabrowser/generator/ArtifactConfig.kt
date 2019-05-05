package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.net.URL
import java.nio.file.Path

/**
 * @author yawkat
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes(value = [
    JsonSubTypes.Type(value = ArtifactConfig.OldJava::class, name = "old-java"),
    JsonSubTypes.Type(value = ArtifactConfig.Java::class, name = "java"),
    JsonSubTypes.Type(value = ArtifactConfig.Maven::class, name = "maven"),
    JsonSubTypes.Type(value = ArtifactConfig.Android::class, name = "android")
])
sealed class ArtifactConfig {
    abstract val metadata: ArtifactMetadata?

    data class OldJava(
            val version: String,
            val src: Path,
            override val metadata: ArtifactMetadata
    ) : ArtifactConfig()

    data class Java(
            val version: String,
            val baseDir: Path,
            override val metadata: ArtifactMetadata
    ) : ArtifactConfig()

    data class Maven(
            val groupId: String,
            val artifactId: String,
            val version: String,
            override val metadata: ArtifactMetadata? = null
    ) : ArtifactConfig()

    data class GitRepo(
            val url: URL,
            val tag: String
    )

    data class Android(
            val repos: List<GitRepo>,
            val version: String,
            override val metadata: ArtifactMetadata
    ) : ArtifactConfig()
}