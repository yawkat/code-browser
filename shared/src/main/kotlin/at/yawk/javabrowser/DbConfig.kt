package at.yawk.javabrowser

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle

/**
 * @author yawkat
 */
data class DbConfig(
        val url: String,
        val user: String,
        val password: String
) {
    fun start(): DBI {
        val hikariConfig = HikariConfig()
        hikariConfig.jdbcUrl = url
        hikariConfig.username = user
        hikariConfig.password = System.getProperty("at.yawk.javabrowser.db-password", password)
        val dataSource = HikariDataSource(hikariConfig)

        val dbi = DBI(dataSource)

        dbi.inTransaction { conn: Handle, _ -> DbMigration.initDb(conn) }

        return dbi
    }
}