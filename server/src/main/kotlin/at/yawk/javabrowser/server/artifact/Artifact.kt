package at.yawk.javabrowser.server.artifact

import at.yawk.javabrowser.server.ArtifactConfig

/**
 * @author yawkat
 */
class Artifact(
        val id: String,
        val config: ArtifactConfig,
        val group: ArtifactGroup
) {
    val version = Version(id.removePrefix(group.prefix))
}