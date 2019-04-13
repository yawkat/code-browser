package at.yawk.javabrowser.generator

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.DbMigration
import com.fasterxml.jackson.databind.ObjectMapper
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(Session::class.java)

class Session(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper = ObjectMapper()
) {
    private var tasks = ArrayList<Task>()

    fun withPrinter(artifactId: String,
                    metadata: ArtifactMetadata,
                    closure: (PrinterWithDependencies) -> Unit) {
        tasks.add(Task(artifactId, metadata, closure))
    }

    fun execute() {
        val ourTasks = ArrayList(tasks)
        tasks.clear()
        dbi.inTransaction { conn: Handle, _ ->
            val present = conn.select("select id from artifacts").map { it["id"] as String }
            val updating = ourTasks.map { it.artifactId }
            val mode = when {
                present.all { it in updating } -> UpdateMode.FULL
                updating.size > present.size * 0.6 -> UpdateMode.MAJOR
                else -> UpdateMode.MINOR
            }

            log.info("Updating {} artifacts in mode {}", updating.size, mode)

            val executor = Executors.newCachedThreadPool()
            try {
                SessionConnection(objectMapper, conn, ourTasks, mode, executor).run()
            } finally {
                executor.shutdownNow()
            }
        }
    }

    enum class UpdateMode {
        /**
         * Create a new schema and start from the ground up.
         */
        FULL,
        /**
         * Create a new schema and start from the ground up.
         */
        MAJOR,
        MINOR,
    }

    private class SessionConnection(
            val objectMapper: ObjectMapper,
            val conn: Handle,
            val tasks: List<Task>,
            val mode: UpdateMode,
            val executor: ExecutorService
    ) {
        private val refBatch = conn.prepareBatch("insert into ${mapTable("binding_references")} (targetBinding, type, sourceArtifactId, sourceFile, sourceFileLine, sourceFileId) VALUES (?, ?, ?, ?, ?, ?)")
        private val declBatch = conn.prepareBatch("insert into ${mapTable("bindings")} (artifactId, binding, sourceFile, isType) VALUES (?, ?, ?, ?)")

        private fun mapTable(tableName: String): String {
            /*if (mode == UpdateMode.FULL &&
                    (tableName == "binding_references" || tableName == "bindings" || tableName == "sourceFiles")) {
                return tableName + "_wip"
            } else {
             */
            return tableName
            //}
        }

        fun run() {
            if (mode == UpdateMode.MAJOR) {
                DbMigration.dropIndicesForUpdate(conn)
            }

            fun delete(inParam: String, args: Array<String>) {
                //if (mode != UpdateMode.FULL) {
                conn.update("delete from bindings where artifactId $inParam", *args)
                conn.update("delete from binding_references where sourceArtifactId $inParam", *args)
                conn.update("delete from sourceFiles where artifactId $inParam", *args)
                //}
                conn.update("delete from dependencies where fromArtifactId $inParam", *args)
                conn.update("delete from artifacts where id $inParam", *args)
            }

            val artifactIds = tasks.map { it.artifactId }
            log.info("Cleaning up in preparation for {}", artifactIds)// recreate indices

            // check exception
            when (mode) {
                UpdateMode.MAJOR -> {
                    val inParam = List(artifactIds.size) { "?" }.joinToString(separator = ",",
                            prefix = "in (",
                            postfix = ")")
                    delete(inParam, artifactIds.toTypedArray())
                }
                UpdateMode.MINOR -> {
                    if (mode == UpdateMode.FULL) {
                        conn.update("create table ${mapTable("bindings")} (like bindings including all)")
                        conn.update("create table ${mapTable("binding_references")} (like binding_references including all)")
                        conn.update("create table ${mapTable("sourceFiles")} (like sourceFiles including all)")
                    }
                    for (artifactId in artifactIds) {
                        delete("= ?", arrayOf(artifactId))
                    }
                }
                UpdateMode.FULL -> {
                    conn.update("create schema wip")
                    conn.update("set search_path to wip")
                    // create tables in new schema
                    DbMigration.initDataSchema(conn)
                }
            }

            for (task in tasks) {
                log.info("Running ${task.artifactId}")
                conn.insert("insert into artifacts (id, lastCompileVersion, metadata) values (?, ?, ?)",
                        task.artifactId, Compiler.VERSION, objectMapper.writeValueAsBytes(task.metadata))

                val printerImpl = PrinterImpl(task.artifactId)
                val concurrentPrinter = ConcurrentPrinter(printerImpl)
                val future = executor.submit {
                    try {
                        task.closure(concurrentPrinter)
                    } finally {
                        concurrentPrinter.finish()
                    }
                }
                concurrentPrinter.work(printerImpl)
                future.get() // check exception

                if (!printerImpl.hasFiles) {
                    throw RuntimeException("No source files on ${task.artifactId}")
                }

                conn.update("select pg_notify('artifacts', ?)", task.artifactId)
                log.info("${task.artifactId} is ready")
            }

            if (mode != UpdateMode.MINOR) {
                DbMigration.createIndices(conn)
                log.info("Updating reference count table")
                conn.update("refresh materialized view binding_references_count_view")

                if (mode == UpdateMode.FULL) {
                    conn.update("drop schema data cascade")
                    conn.update("alter schema wip rename to data")
                }
            }
        }

        private inner class PrinterImpl(val artifactId: String) : PrinterWithDependencies {
            var hasFiles = false

            override fun addDependency(dependency: String) {
                conn.insert("insert into dependencies (fromArtifactId, toArtifactId) values (?, ?)",
                        artifactId,
                        dependency)
            }

            override fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
                hasFiles = true
                conn.insert(
                        "insert into ${mapTable("sourceFiles")} (artifactId, path, json) VALUES (?, ?, ?)",
                        artifactId,
                        path,
                        objectMapper.writeValueAsBytes(sourceFile))

                val lineNumberTable = LineNumberTable(sourceFile.text)

                for (entry in sourceFile.entries) {
                    val annotation = entry.annotation
                    if (annotation is BindingRef) {
                        refBatch.add(
                                annotation.binding,
                                annotation.type.id,
                                artifactId,
                                path,
                                lineNumberTable.lineAt(entry.start),
                                annotation.id
                        )
                    } else if (annotation is BindingDecl) {
                        declBatch.add(
                                artifactId,
                                annotation.binding,
                                path,
                                !annotation.binding.contains('#') && !annotation.binding.contains('(')
                        )
                    }
                }
                refBatch.execute()
                declBatch.execute()
            }
        }
    }

    private class Task(
            val artifactId: String,
            val metadata: ArtifactMetadata,
            val closure: (PrinterWithDependencies) -> Unit
    )
}