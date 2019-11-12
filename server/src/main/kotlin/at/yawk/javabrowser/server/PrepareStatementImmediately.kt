package at.yawk.javabrowser.server

import org.postgresql.PGStatement
import org.skife.jdbi.v2.StatementContext
import org.skife.jdbi.v2.tweak.StatementCustomizer
import java.sql.PreparedStatement

/**
 * @author yawkat
 */
object PrepareStatementImmediately : StatementCustomizer {
    override fun beforeExecution(stmt: PreparedStatement, ctx: StatementContext) {
        stmt.unwrap(PGStatement::class.java).prepareThreshold = -1
    }

    override fun cleanup(ctx: StatementContext) {
    }

    override fun afterExecution(stmt: PreparedStatement, ctx: StatementContext) {
    }
}