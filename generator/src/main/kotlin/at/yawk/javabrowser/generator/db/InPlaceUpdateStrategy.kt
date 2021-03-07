package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.GeneratorSchema
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle

class InPlaceUpdateStrategy(dbi: DBI) : UpdateStrategy("data", dbi) {
    override fun prepare() {
    }

    override fun finish(allArtifacts: Collection<String>) {
        return dbi.inTransaction { conn: Handle, _ ->
            setConnectionSchema(conn)
            GeneratorSchema(conn).updateViews(concurrent = true)
        }
    }

    override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
        dbi.inTransactionSuspend { conn, _ ->
            setConnectionSchema(conn)
            val tx = DirectTransaction(conn)
            tx.deleteArtifact(artifactId)
            task(tx)
            tx.flush()
            notifyUpdate(conn, artifactId)
        }
    }
}