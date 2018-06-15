package at.yawk.javabrowser.server.artifact

/**
 * @author yawkat
 */
class ArtifactGroup(
        val prefix: String,
        val parent: ArtifactGroup?
) {
    val childGroups = mutableListOf<ArtifactGroup>()
    /**
     * Ordered by descending version
     */
    val childArtifacts = mutableListOf<Artifact>()
}