package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.GeneratorSchema
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

class InPlaceUpdateStrategy(dbi: Jdbi) : UpdateStrategy("data", dbi) {
    override fun prepare() {
    }

    override fun finish(allArtifacts: Collection<String>) {
        return dbi.inTransaction<Unit, Exception> { conn: Handle ->
            setConnectionSchema(conn)
            GeneratorSchema(conn).updateViews(concurrent = true)
        }
    }

    override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
        dbi.inTransactionSuspend { conn ->
            setConnectionSchema(conn)
            val tx = DirectTransaction(conn)
            tx.deleteArtifact(artifactId)
            task(tx)
            tx.flush()
            notifyUpdate(conn, artifactId)
        }
    }
}