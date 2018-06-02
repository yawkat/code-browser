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
    JsonSubTypes.Type(value = Artifact.OldJava::class, name = "old-java"),
    JsonSubTypes.Type(value = Artifact.Java::class, name = "java"),
    JsonSubTypes.Type(value = Artifact.Maven::class, name = "maven")
])
sealed class Artifact {


    data class OldJava(
            val version: String,
            val src: Path
    ) : Artifact()

    data class Java(
            val version: String,
            val src: Path
    ) : Artifact()

    data class Maven(
            val groupId: String,
            val artifactId: String,
            val versions: List<String>
    ) : Artifact()
}