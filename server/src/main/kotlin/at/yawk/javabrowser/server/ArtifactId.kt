package at.yawk.javabrowser.server

/**
 * @author yawkat
 */
data class ArtifactId(
        val artifactId: String,
        val components: List<Component>
) {
    companion object {
        fun split(artifactId: String): ArtifactId {
            val parents = ArrayList<ArtifactId.Component>()
            parents.add(Component("", ""))
            var groupStart = 0
            for ((index, c) in artifactId.withIndex()) {
                if (c == '/') {
                    parents.add(ArtifactId.Component(
                            artifactId.substring(0, index + 1),
                            artifactId.substring(groupStart, index + 1)
                    ))
                    groupStart = index + 1
                }
            }
            if (groupStart < artifactId.length) {
                parents.add(Component(artifactId, artifactId.substring(groupStart)))
            }
            return ArtifactId(artifactId, parents)
        }
    }

    data class Component(
            val fullPath: String,
            val simpleName: String
    )
}