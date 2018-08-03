package at.yawk.javabrowser

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.skife.jdbi.v2.DBI

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
        hikariConfig.password = password
        val dataSource = HikariDataSource(hikariConfig)

        val flyway = Flyway()
        flyway.dataSource = dataSource
        flyway.migrate()

        return DBI(dataSource)
    }
}