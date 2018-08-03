package at.yawk.javabrowser.server.artifact

/**
 * @author yawkat
 */
data class ArtifactPath(
        val nodes: List<Node>
) {
    val id: String
        get() = nodes.mapNotNull { it.value }.joinToString("/")

    data class Node(
            val value: String?,
            val alternatives: List<String>
    )
}