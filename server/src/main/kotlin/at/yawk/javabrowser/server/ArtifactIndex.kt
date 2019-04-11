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
        artifactUpdater.addArtifactUpdateListener { rootArtifact = fetch() }
    }

    private fun fetch(): ArtifactNode {
        return dbi.inTransaction { conn: Handle, _: TransactionStatus ->
            ArtifactNode.build(conn.select("select id from artifacts").map { it["id"] as String })
        }
    }
}