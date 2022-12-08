package at.yawk.javabrowser

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.sqlobject.SqlObjectPlugin

/**
 * @author yawkat
 */
data class DbConfig(
        val url: String,
        val user: String,
        val password: String
) {
    enum class Mode {
        FRONTEND,
        GENERATOR,
    }

    fun start(mode: Mode, closure: (HikariConfig) -> Unit = {}): Jdbi {
        val hikariConfig = HikariConfig()
        hikariConfig.jdbcUrl = url
        hikariConfig.username = user
        hikariConfig.password = System.getProperty("at.yawk.javabrowser.db-password", password)
        if (mode == Mode.FRONTEND) {
            hikariConfig.connectionInitSql = "set search_path to interactive, data"
        } else {
            hikariConfig.connectionInitSql = "set search_path to data"
        }
        closure(hikariConfig)
        val dataSource = HikariDataSource(hikariConfig)

        return Jdbi.create(dataSource).installPlugin(SqlObjectPlugin())
    }
}

fun Handle.loadScript(path: String) = createScript(DbConfig::class.java.getResourceAsStream(path).use { it!!.bufferedReader().readText() })