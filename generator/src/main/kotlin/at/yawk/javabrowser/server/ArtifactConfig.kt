package at.yawk.javabrowser.server

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
    JsonSubTypes.Type(value = ArtifactConfig.Maven::class, name = "maven")
])
sealed class ArtifactConfig {


    data class OldJava(
            val version: String,
            val src: Path
    ) : ArtifactConfig()

    data class Java(
            val version: String,
            val baseDir: Path
    ) : ArtifactConfig()

    data class Maven(
            val groupId: String,
            val artifactId: String,
            val version: String
    ) : ArtifactConfig()
}