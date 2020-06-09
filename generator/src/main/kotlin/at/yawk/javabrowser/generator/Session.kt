package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.DbMigration
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsVector
import at.yawk.javabrowser.generator.artifact.COMPILER_VERSION
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory
import java.util.concurrent.ForkJoinPool

/**
 * @author yawkat
 */
private val log = LoggerFactory.getLogger(Session::class.java)

private inline fun finallyWithSuppressed(
        tryBlock: () -> Unit,
        finallyBlock: () -> Unit
) {
    var exc: Throwable? = null
    try {
        tryBlock()
    } catch (t: Throwable) {
        exc = t
        throw t
    } finally {
        if (exc == null) {
            finallyBlock()
        } else {
            try {
                finallyBlock()
            } catch (t: Throwable) {
                exc.addSuppressed(t)
            }
        }
    }
}

class Session(
        private val dbi: DBI,
        private val objectMapper: ObjectMapper = ObjectMapper()
) {
    private var tasks = ArrayList<Task>()

    fun withPrinter(artifactId: String,
                    metadata: ArtifactMetadata,
                    closure: suspend (PrinterWithDependencies) -> Unit) {
        tasks.add(Task(artifactId, metadata, closure))
    }

    fun execute() {
        val ourTasks = ArrayList(tasks)
        tasks.clear()
        dbi.inTransaction { conn: Handle, _ ->
            val present = conn.select("select id from artifacts").map { it["id"] as String }
            val updating = ourTasks.map { it.artifactId }
            val mode = when {
                updating.size > present.size * 0.6 -> UpdateMode.FULL
                else -> UpdateMode.MINOR
            }

            log.info("Updating {} artifacts in mode {}", updating.size, mode)

            SessionConnection(objectMapper, conn, ourTasks, mode).run()
        }
        // do this outside the tx so some data is already available
        dbi.useHandle { otherTx ->
            log.info("Updating reference count view")
            otherTx.update("refresh materialized view concurrently binding_references_count_view")
            log.info("Updating package view")
            otherTx.update("refresh materialized view concurrently packages_view")
            log.info("Updating type count view")
            otherTx.update("refresh materialized view concurrently type_count_by_depth_view")
        }
    }

    enum class UpdateMode {
        /**
         * Create a new schema and start from the ground up.
         */
        FULL,
        MINOR,
    }

    private class SessionConnection(
            val objectMapper: ObjectMapper,
            val conn: Handle,
            val tasks: List<Task>,
            val mode: UpdateMode
    ) {
        private val coroutineScope = CoroutineScope(ForkJoinPool.commonPool().asCoroutineDispatcher())

        //private val coroutineScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
        private val concurrencyControl = ParserConcurrencyControl.Impl(maxConcurrentSourceFiles = 1024)

        private val refBatch = conn.prepareBatch("insert into binding_references (realm, targetBinding, type, sourceArtifactId, sourceFile, sourceFileLine, sourceFileId) VALUES (?, ?, ?, ?, ?, ?, ?)")
        private val declBatch = conn.prepareBatch("insert into bindings (realm, artifactId, binding, sourceFile, isType, description, modifiers, parent) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")

        private val dbWriter = BackPressureExecutor(16)

        fun run() {

            fun delete(inParam: String, args: Array<String>) {
                conn.update("delete from bindings where artifactId $inParam", *args)
                conn.update("delete from binding_references where sourceArtifactId $inParam", *args)
                conn.update("delete from sourceFiles where artifactId $inParam", *args)
                conn.update("delete from dependencies where fromArtifactId $inParam", *args)
                conn.update("delete from artifactAliases where artifactId $inParam", *args)
                conn.update("delete from artifacts where id $inParam", *args)
            }

            val artifactIds = tasks.map { it.artifactId }
            log.info("Cleaning up in preparation for {}", artifactIds)// recreate indices

            // check exception
            when (mode) {
                UpdateMode.MINOR -> {
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

            val futures = ArrayList<Deferred<Unit>>()
            for (task in tasks) {
                conn.insert("insert into artifacts (id, lastCompileVersion, metadata) values (?, ?, ?)",
                        task.artifactId, COMPILER_VERSION, objectMapper.writeValueAsBytes(task.metadata))

                val printerImpl = PrinterImpl(task.artifactId)
                futures.add(coroutineScope.async {
                    finallyWithSuppressed(
                            tryBlock = { task.closure(printerImpl) },
                            finallyBlock = { printerImpl.finish() }
                    )
                })
            }

            Thread({
                dbWriter.work()
                log.info("DB writer done")
            }, "DB writer thread").start()
            runBlocking {
                futures.awaitAll()
            }
            dbWriter.shutdownAndWait()

            if (mode != UpdateMode.MINOR) {
                DbMigration.createIndices(conn)

                if (mode == UpdateMode.FULL) {
                    log.info("Replacing schema")
                    conn.update("drop schema data cascade")
                    conn.update("alter schema wip rename to data")
                    // reset connection search_path in case the connection is reused
                    conn.update("set search_path to data")
                }
            }

            log.info("Connection done")
        }

        private inner class PrinterImpl(val artifactId: String) : PrinterWithDependencies {
            var hasFiles = false

            override val concurrencyControl: ParserConcurrencyControl
                get() = this@SessionConnection.concurrencyControl

            override fun addDependency(dependency: String) {
                dbWriter.submit {
                    conn.insert("insert into dependencies (fromArtifactId, toArtifactId) values (?, ?)",
                            artifactId,
                            dependency)
                }
            }

            override fun addAlias(alias: String) {
                dbWriter.submit {
                    conn.insert("insert into artifactAliases (artifactId, alias) values (?, ?)",
                            artifactId,
                            alias)
                }
            }

            fun finish() {
                if (!hasFiles) {
                    throw RuntimeException("No source files on $artifactId")
                }

                dbWriter.submit {
                    conn.update("select pg_notify('artifacts', ?)", artifactId)
                }
                log.info("$artifactId is ready")
            }

            private fun storeTokens(table: String,
                                    realm: Realm,
                                    artifactId: String,
                                    sourceFile: String,
                                    tokens: List<Tokenizer.Token>) {
                val lexemes = TsVector()
                val start = IntLists.mutable.empty()
                val length = IntLists.mutable.empty()

                fun flush() {
                    if (start.isEmpty) return

                    conn.update(
                            "insert into $table (realm, artifactId, sourceFile, lexemes, starts, lengths) values (?, ?, ?, ?::tsvector, ?, ?)",
                            realm.id,
                            artifactId,
                            sourceFile,
                            lexemes.toSql(),
                            start.toArray(),
                            length.toArray()
                    )

                    lexemes.clear()
                    start.clear()
                    length.clear()
                }

                var i = 0
                for (token in tokens) {
                    if (!lexemes.add(token.text, i++)) {
                        flush()

                        i = 0
                        lexemes.add(token.text, i++)
                    }
                    start.add(token.start)
                    length.add(token.length)
                }
                flush()
            }

            override fun addSourceFile(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>,
                                       realm: Realm) {
                hasFiles = true
                dbWriter.submit { addSourceFile0(path, sourceFile, tokens, realm) }
            }

            private fun addSourceFile0(path: String, sourceFile: GeneratorSourceFile, tokens: List<Tokenizer.Token>,
                                       realm: Realm) {
                conn.insert(
                        "insert into sourceFiles (realm, artifactId, path, text, annotations) values (?, ?, ?, ?, ?)",
                        realm.id,
                        artifactId,
                        path,
                        sourceFile.text.toByteArray(Charsets.UTF_8),
                        objectMapper.writeValueAsBytes(sourceFile.entries)
                )

                if (realm == Realm.SOURCE) {
                    storeTokens("sourceFileLexemes", realm, artifactId, path, tokens)
                    storeTokens("sourceFileLexemesNoSymbols", realm, artifactId, path, tokens.filter { !it.symbol })
                } else {
                    require(tokens.isEmpty()) // TODO
                }

                val lineNumberTable = LineNumberTable(sourceFile.text)

                for (entry in sourceFile.entries) {
                    val annotation = entry.annotation
                    if (annotation is BindingRef) {
                        refBatch.add(
                                realm.id,
                                annotation.binding,
                                annotation.type.id,
                                artifactId,
                                path,
                                lineNumberTable.lineAt(entry.start),
                                annotation.id
                        )
                    } else if (annotation is BindingDecl) {
                        declBatch.add(
                                realm.id,
                                artifactId,
                                annotation.binding,
                                path,
                                annotation.description is BindingDecl.Description.Type &&
                                        // exclude local and anonymous types from isType so they don't appear in the
                                        // search
                                        annotation.modifiers and (BindingDecl.MODIFIER_ANONYMOUS or
                                        BindingDecl.MODIFIER_LOCAL) == 0,
                                objectMapper.writeValueAsBytes(annotation.description),
                                annotation.modifiers,
                                annotation.parent
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
            val closure: suspend (PrinterWithDependencies) -> Unit
    )
}