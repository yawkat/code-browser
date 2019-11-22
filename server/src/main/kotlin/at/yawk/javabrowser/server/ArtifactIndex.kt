package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.TransactionStatus
import java.util.NavigableMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @author yawkat
 */
@Singleton
class ArtifactIndex @Inject constructor(
        artifactUpdater: ArtifactUpdater,
        private val dbi: DBI
) {
    private var rootArtifact = fetch()

    init {
        artifactUpdater.addInvalidationListener(runAtStart = false) { rootArtifact = fetch() }
    }

    private fun fetch(): ArtifactNode {
        return dbi.inTransaction { conn: Handle, _: TransactionStatus ->
            ArtifactNode.build(conn.select("select id from artifacts").map { it["id"] as String })
        }
    }

    val allArtifacts: NavigableMap<String, ArtifactNode>
        get() = rootArtifact.allNodes

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