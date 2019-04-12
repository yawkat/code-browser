package at.yawk.javabrowser

import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(DbMigration::class.java)

object DbMigration {
    fun initDb(conn: Handle) {
        log.info("Initializing DB")
        conn.createScript("at/yawk/javabrowser/InitDb.sql").execute()
    }

    fun dropIndicesForUpdate(conn: Handle) {
        log.info("Dropping indices for DB update")
        conn.createScript("at/yawk/javabrowser/DropIndices.sql").execute()
    }

    fun recreateIndices(conn: Handle) {
        initDb(conn)
    }
}