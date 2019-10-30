package at.yawk.javabrowser

import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.NoOpStatementRewriter
import org.skife.jdbi.v2.Script
import org.slf4j.LoggerFactory

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(DbMigration::class.java)

object DbMigration {
    fun initDataSchema(conn: Handle) {
        log.info("Initializing DB")
        val statement = conn.createStatement(
                DbMigration::class.java.getResourceAsStream("/at/yawk/javabrowser/InitDataSchema.sql")
                        .readBytes().toString(Charsets.UTF_8)
        )
        statement.setStatementRewriter(NoOpStatementRewriter())
        statement.execute()
    }

    fun initInteractiveSchema(conn: Handle) {
        log.info("Initializing DB")
        conn.createScript("at/yawk/javabrowser/InitInteractiveSchema.sql").execute()
    }

    fun createIndices(conn: Handle) {
        log.info("Creating indices")
        conn.createScript("at/yawk/javabrowser/CreateIndices.sql").execute()
    }
}