package at.yawk.javabrowser.server.artifact

import at.yawk.javabrowser.server.VersionComparator
import java.util.NavigableMap
import java.util.TreeMap

/**
 * @author yawkat
 */
class ArtifactNode private constructor(
        val idInParent: String?,
        val parent: ArtifactNode?,
        childrenNames: List<List<String>>
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

    /**
     * A "flattened" view of this node's children. If this node is A and:
     *
     * ```
     *     A
     *    / \
     *   B   C
     *  / \   \
     * D   E   F
     * ```
     *
     * then A's flattenedChildren are `[B, F]`.
     *
     * This is used to display a compact representation of this node's children while still not having the user click
     * through multiple levels when there is only one choice anyway.
     */
    val flattenedChildren: List<ArtifactNode> =
            children.values
                    .map { it.flatten() }
                    .sortedWith(compareBy(VersionComparator) { it.id })

    val allNodes: NavigableMap<String, ArtifactNode>

    init {
        allNodes = TreeMap(VersionComparator)
        allNodes[this.id] = this
        for (child in children.values) {
            allNodes.putAll(child.allNodes)
        }
    }

    /**
     * The leaf nodes of this artifact tree. If this node has no children, this list contains exactly only this node.
     */
    val leaves: List<ArtifactNode> =
            if (children.isEmpty()) listOf(this)
            else children.values.flatMap { it.leaves }
}