package at.yawk.javabrowser.server.artifact

import at.yawk.javabrowser.server.VersionComparator
import org.eclipse.collections.api.map.primitive.LongObjectMap
import org.eclipse.collections.impl.factory.primitive.LongObjectMaps
import java.util.NavigableMap
import java.util.TreeMap

/**
 * @author yawkat
 */
class ArtifactNode private constructor(
        val idInParent: String?,
        val parent: ArtifactNode?,
        childrenNames: List<Prototype>
) {
    companion object {
        fun build(artifacts: List<Prototype>): ArtifactNode {
            return ArtifactNode(null, null, artifacts)
        }
    }

    val stringIdList: List<String> =
            if (idInParent == null) emptyList()
            else parent!!.stringIdList + idInParent
    val stringId: String = stringIdList.joinToString("/")

    val dbId = childrenNames.singleOrNull { it.components.isEmpty() }?.dbId

    val children = childrenNames
            .filter { it.components.isNotEmpty() }
            .groupBy { it.components.first() }
            .mapValues { (k, v) -> ArtifactNode(k, this, v.map { Prototype(it.dbId, it.components.drop(1)) }) }

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
                    .sortedWith(compareBy(VersionComparator) { it.stringId })

    val allNodesByStringId: NavigableMap<String, ArtifactNode>
    val allNodesByDbId: LongObjectMap<ArtifactNode>

    init {
        allNodesByStringId = TreeMap(VersionComparator)
        allNodesByStringId[this.stringId] = this
        for (child in children.values) {
            allNodesByStringId.putAll(child.allNodesByStringId)
        }

        allNodesByDbId = LongObjectMaps.mutable.empty<ArtifactNode>().also {
            if (dbId != null) it.put(dbId, this)
            for (child in children.values) {
                it.putAll(child.allNodesByDbId)
            }
        }
    }

    class Prototype constructor(
            val dbId: Long,
            val components: List<String>
    ) {
        constructor(dbId: Long, stringId: String) : this(dbId = dbId, components = stringId.split('/'))
    }

    override fun toString(): String {
        return "ArtifactNode($dbId: $stringId)"
    }
}