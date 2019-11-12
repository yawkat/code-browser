package at.yawk.javabrowser.server

import at.yawk.javabrowser.server.artifact.ArtifactNode
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.TransactionStatus

/**
 * @author yawkat
 */
class ArtifactIndex(
        artifactUpdater: ArtifactUpdater,
        private val dbi: DBI
) {
    var rootArtifact = fetch()
        private set

    init {
        artifactUpdater.addInvalidationListener(runAtStart = false) { rootArtifact = fetch() }
    }

    private fun fetch(): ArtifactNode {
        return dbi.inTransaction { conn: Handle, _: TransactionStatus ->
            ArtifactNode.build(conn.select("select id from artifacts").map { it["id"] as String })
        }
    }

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