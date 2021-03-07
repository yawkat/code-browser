package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.COMPILER_VERSION
import org.intellij.lang.annotations.Language
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.skife.jdbi.v2.TransactionStatus
import org.skife.jdbi.v2.exceptions.TransactionFailedException
import java.util.concurrent.atomic.AtomicBoolean

internal suspend fun <R> DBI.inTransactionSuspend(task: suspend (Handle, TransactionStatus) -> R): R {
    return open().use { handle ->
        val failed = AtomicBoolean(false)
        val status = TransactionStatus { failed.set(true) }
        val r: R
        try {
            handle.begin()
            r = task(handle, status)
            if (!failed.get()) {
                handle.commit()
            }
        } catch (e: Exception) {
            try {
                handle.rollback()
            } catch (rol: Exception) {
                e.addSuppressed(rol)
            }
            throw e
        }
        if (failed.get()) {
            handle.rollback()
            throw TransactionFailedException("Set to rollback only")
        }
        r
    }
}

abstract class UpdateStrategy(
    @Language(value = "sql", prefix = "set search_path = ") private val workSchema: String,
    protected val dbi: DBI
) : TransactionProvider {
    fun hasSchema() = dbi.inTransaction { conn, _ -> hasSchema(conn) }

    override suspend fun claimArtifactId(): Long = dbi.inTransaction { conn, _ ->
        setConnectionSchema(conn)
        (conn.select("select nextval('artifact_id_sequence')").single()["nextval"] as Number).toLong()
    }

    protected fun hasSchema(conn: Handle) =
        conn.select(
            "select 1 from information_schema.tables where table_schema = ? and table_name = 'artifact'",
            workSchema
        ).any()

    protected fun setConnectionSchema(conn: Handle) {
        conn.update("set search_path = $workSchema")
    }

    protected fun notifyUpdate(conn: Handle, artifactStringId: String) {
        conn.update("select pg_notify('artifact', ?)", artifactStringId)
    }

    fun listUpToDate(): List<String> {
        return dbi.inTransaction { conn: Handle, _ ->
            setConnectionSchema(conn)
            conn.select("select string_id from artifact where last_compile_version >= ?", COMPILER_VERSION)
                .map { it["string_id"] as String }
        }
    }

    abstract fun prepare()

    abstract fun finish(allArtifacts: Collection<String>)
}