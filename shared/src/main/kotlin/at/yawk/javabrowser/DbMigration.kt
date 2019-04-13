package at.yawk.javabrowser

import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(DbMigration::class.java)

object DbMigration {
    fun initDataSchema(conn: Handle) {
        log.info("Initializing DB")
        conn.createScript("at/yawk/javabrowser/InitDataSchema.sql").execute()
    }

    fun initInteractiveSchema(conn: Handle) {
        log.info("Initializing DB")
        conn.createScript("at/yawk/javabrowser/InitInteractiveSchema.sql").execute()
    }

    fun dropIndicesForUpdate(conn: Handle) {
        log.info("Dropping indices for DB update")
        conn.createScript("at/yawk/javabrowser/DropIndices.sql").execute()
    }

    fun createIndices(conn: Handle) {
        log.info("Creating indices")
        conn.createScript("at/yawk/javabrowser/CreateIndices.sql").execute()
    }
}