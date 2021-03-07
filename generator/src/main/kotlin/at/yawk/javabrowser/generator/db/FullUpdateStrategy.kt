package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.COMPILER_VERSION
import at.yawk.javabrowser.generator.GeneratorSchema
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FullUpdateStrategy::class.java)

class FullUpdateStrategy(dbi: DBI) : UpdateStrategy("wip", dbi) {
    override fun prepare() {
        dbi.inTransaction { conn: Handle, _ ->
            var hasWipSchema = hasSchema(conn)
            if (hasWipSchema) {
                val hasOutdated = conn.select("select 1 from wip.artifact where last_compile_version < ?", COMPILER_VERSION).any()
                if (hasOutdated) {
                    log.info("Found outdated artifacts in existing `wip` schema. Dropping `wip` schema to start anew.")
                    conn.update("drop schema wip cascade")
                    hasWipSchema = false
                }
            }
            if (!hasWipSchema) {
                log.info("Creating `wip` schema")
                conn.update("create schema wip")
                setConnectionSchema(conn)
                GeneratorSchema(conn).createSchema()
            } else {
                log.info("Running with existing `wip` schema")
            }
        }
    }

    override fun finish(allArtifacts: Collection<String>) {
        dbi.inTransaction { conn, _ ->
            setConnectionSchema(conn)

            log.info("Creating indices")
            GeneratorSchema(conn).createIndices()
            GeneratorSchema(conn).updateViews(concurrent = false)

            log.info("Replacing schema")
            conn.update("drop schema data cascade")
            conn.update("alter schema wip rename to data")

            for (artifact in allArtifacts) {
                notifyUpdate(conn, artifact)
            }
        }
    }

    override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
        dbi.inTransactionSuspend { conn, _ ->
            setConnectionSchema(conn)
            val tx = DirectTransaction(conn)
            task(tx)
            tx.flush()
        }
    }
}