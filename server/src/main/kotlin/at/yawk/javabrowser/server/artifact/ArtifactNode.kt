package at.yawk.javabrowser.server.artifact

import at.yawk.javabrowser.server.VersionComparator

/**
 * @author yawkat
 */
class ArtifactNode private constructor(
        val idInParent: String?,
        val parent: ArtifactNode?,
        val childrenNames: List<List<String>>
) {
    companion object {
        fun build(artifacts: List<String>): ArtifactNode {
            return ArtifactNode(null, null, artifacts.map { it.split('/') })
        }
    }

    val idList: List<String> =
            if (idInParent == null) emptyList()
            else parent!!.idList + idInParent
    val id: String = idList.joinToString("/")

    val children = childrenNames
            .filter { it.isNotEmpty() }
            .groupBy { it.first() }
            .mapValues { (k, v) -> ArtifactNode(k, this, v.map { it.subList(1, it.size) }) }

    /**
     * If this node has only one child, return that node.
     */
    private fun flatten(): ArtifactNode = children.values.singleOrNull()?.flatten() ?: this

    val flattenedChildren: List<ArtifactNode> =
            children.values
                    .map { it.flatten() }
                    .sortedWith(compareBy(VersionComparator) { it.id })
}