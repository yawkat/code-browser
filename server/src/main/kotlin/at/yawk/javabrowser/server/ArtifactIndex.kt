package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import org.eclipse.collections.api.map.primitive.LongObjectMap
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import java.util.NavigableMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
@Singleton
class ArtifactIndex @Inject constructor(
        artifactUpdater: ArtifactUpdater,
        private val dbi: Jdbi
) {
    private var rootArtifact = fetch()

    init {
        artifactUpdater.addInvalidationListener(runAtStart = false) { rootArtifact = fetch() }
    }

    private fun fetch(): ArtifactNode {
        return dbi.inTransaction<ArtifactNode, Exception> { conn: Handle ->
            ArtifactNode.build(conn.select("select artifact_id, string_id from artifact").mapToMap().toList().map {
                ArtifactNode.Prototype(dbId = (it["artifact_id"] as Number).toLong(), stringId = it["string_id"] as String)
            })
        }
    }

    val allArtifactsByStringId: NavigableMap<String, ArtifactNode>
        get() = rootArtifact.allNodesByStringId

    val allArtifactsByDbId: LongObjectMap<ArtifactNode>
        get() = rootArtifact.allNodesByDbId

    val leafArtifacts: Collection<ArtifactNode>
        get() = allArtifactsByDbId.values()

    fun parse(rawPath: String): ParseResult {
        val path = rawPath.removePrefix("/").removeSuffix("/")
        val pathParts = if (path.isEmpty()) emptyList() else path.split('/')

        var node = rootArtifact
        for ((i, pathPart) in pathParts.withIndex()) {
            val child = node.children[pathPart]
            if (child != null) {
                node = child
            } else {
                val sourceFileParts = pathParts.subList(i, pathParts.size)
                val sourceFilePath = sourceFileParts.joinToString("/")
                return ParseResult(node, sourceFilePath)
            }
        }
        return ParseResult(node, null)
    }

    class ParseResult(
            val node: ArtifactNode,
            val remainingPath: String?
    )
}