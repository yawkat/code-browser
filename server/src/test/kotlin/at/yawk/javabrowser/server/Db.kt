package at.yawk.javabrowser.server

import org.flywaydb.core.Flyway
import org.skife.jdbi.v2.DBI
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * @author yawkat
 */
fun createDb(): DBI {
    val uuid = UUID.randomUUID()
    val ds = object : DataSource {
        @Suppress("UsePropertyAccessSyntax", "unused")
        val keepOpen = getConnection()

        override fun getConnection(): Connection {
            return DriverManager.getConnection("jdbc:h2:mem:$uuid")
        }

        override fun getConnection(username: String?, password: String?) = throw UnsupportedOperationException()

        override fun setLogWriter(out: PrintWriter?) {
        }

        override fun getParentLogger() = throw UnsupportedOperationException()

        override fun setLoginTimeout(seconds: Int) {
        }

        override fun isWrapperFor(iface: Class<*>?): Boolean {
            return false
        }

        override fun getLogWriter() = throw UnsupportedOperationException()

        override fun <T : Any?> unwrap(iface: Class<T>?) = throw UnsupportedOperationException()

        override fun getLoginTimeout() = throw UnsupportedOperationException()
    }

    val flyway = Flyway()
    flyway.dataSource = ds
    flyway.migrate()

    return DBI(ds)
}