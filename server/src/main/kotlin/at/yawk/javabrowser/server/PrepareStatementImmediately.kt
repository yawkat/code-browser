package at.yawk.javabrowser.server

import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.core.statement.StatementCustomizer
import org.postgresql.PGStatement
import java.sql.PreparedStatement

/**
 * @author yawkat
 */
object PrepareStatementImmediately : StatementCustomizer {
    override fun beforeExecution(stmt: PreparedStatement, ctx: StatementContext) {
        stmt.unwrap(PGStatement::class.java).prepareThreshold = -1
    }

    override fun afterExecution(stmt: PreparedStatement, ctx: StatementContext) {
    }
}