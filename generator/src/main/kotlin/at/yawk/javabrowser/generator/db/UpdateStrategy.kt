package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.COMPILER_VERSION
import org.intellij.lang.annotations.Language
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import java.util.concurrent.atomic.AtomicBoolean

internal suspend fun <R> Jdbi.inTransactionSuspend(task: suspend (Handle) -> R): R {
    return open().use { handle ->
        val failed = AtomicBoolean(false)
        val r: R
        try {
            handle.begin()
            r = task(handle)
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
            throw Exception("Set to rollback only")
        }
        r
    }
}

abstract class UpdateStrategy(
    @Language(value = "sql", prefix = "set search_path = ") private val workSchema: String,
    protected val dbi: Jdbi
) : TransactionProvider {
    fun hasSchema() = dbi.inTransaction<Boolean, Exception> { conn -> hasSchema(conn) }

    override suspend fun claimArtifactId(): Long = dbi.inTransaction<Long, Exception> { conn ->
        setConnectionSchema(conn)
        (conn.select("select nextval('artifact_id_sequence')").mapToMap().single()["nextval"] as Number).toLong()
    }

    protected fun hasSchema(conn: Handle) =
        conn.select(
            "select 1 from information_schema.tables where table_schema = ? and table_name = 'artifact'",
            workSchema
        ).mapToMap().any()

    protected fun setConnectionSchema(conn: Handle) {
        conn.createUpdate("set search_path = $workSchema").execute()
    }

    protected fun notifyUpdate(conn: Handle, artifactStringId: String) {
        conn.createUpdate("select pg_notify('artifact', ?)").bind(0, artifactStringId).execute()
    }

    fun listUpToDate(): List<String> {
        return dbi.inTransaction<List<String>, Exception> { conn: Handle ->
            setConnectionSchema(conn)
            conn.select("select string_id from artifact where last_compile_version >= ?", COMPILER_VERSION)
                .mapToMap().toList()
                .map { it["string_id"] as String }
        }
    }

    abstract fun prepare()

    abstract fun finish(allArtifacts: Collection<String>)
}