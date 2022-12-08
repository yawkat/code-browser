package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.COMPILER_VERSION
import at.yawk.javabrowser.generator.GeneratorSchema
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FullUpdateStrategy::class.java)

class FullUpdateStrategy(dbi: Jdbi) : UpdateStrategy("wip", dbi) {
    override fun prepare() {
        dbi.inTransaction<Unit, Exception> { conn: Handle ->
            var hasWipSchema = hasSchema(conn)
            if (hasWipSchema) {
                val hasOutdated = conn.select("select 1 from wip.artifact where last_compile_version < ?", COMPILER_VERSION).mapToMap().any()
                if (hasOutdated) {
                    log.info("Found outdated artifacts in existing `wip` schema. Dropping `wip` schema to start anew.")
                    conn.createUpdate("drop schema wip cascade").execute()
                    hasWipSchema = false
                }
            }
            if (!hasWipSchema) {
                log.info("Creating `wip` schema")
                conn.createUpdate("create schema wip").execute()
                setConnectionSchema(conn)
                GeneratorSchema(conn).createSchema()
            } else {
                log.info("Running with existing `wip` schema")
            }
        }
    }

    override fun finish(allArtifacts: Collection<String>) {
        dbi.inTransaction<Unit, Exception> { conn ->
            setConnectionSchema(conn)

            log.info("Creating indices")
            GeneratorSchema(conn).createIndices()
            GeneratorSchema(conn).updateViews(concurrent = false)

            log.info("Replacing schema")
            conn.createUpdate("drop schema data cascade").execute()
            conn.createUpdate("alter schema wip rename to data").execute()

            for (artifact in allArtifacts) {
                notifyUpdate(conn, artifact)
            }
        }
    }

    override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
        dbi.inTransactionSuspend { conn ->
            setConnectionSchema(conn)
            val tx = DirectTransaction(conn)
            task(tx)
            tx.flush()
        }
    }
}