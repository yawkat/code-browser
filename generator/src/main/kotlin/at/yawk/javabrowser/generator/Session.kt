package at.yawk.javabrowser.generator

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.CompressedFactory
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsVector
import at.yawk.javabrowser.generator.artifact.COMPILER_VERSION
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport
import com.google.common.hash.Hashing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.LongSets
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

@Suppress("UnstableApiUsage")
private val BINDING_HASHER = Hashing.sipHash24()

class Session(private val dbi: DBI) {
    private var tasks = ArrayList<Task>()

    fun withPrinter(artifactId: String,
                    metadata: ArtifactMetadata,
                    closure: suspend (PrinterWithDependencies) -> Unit) {
        tasks.add(Task(artifactId, metadata, closure))
    }

    fun execute(totalArtifacts: Int) {
        val ourTasks = ArrayList(tasks)
        tasks.clear()
        dbi.withHandle { conn: Handle ->
            val updating = ourTasks.map { it.artifactId }
            val full = updating.size > totalArtifacts * 0.6

            log.info("Updating {} artifacts in mode {}", updating.size, if (full) "full" else "partial")

            SessionConnection(conn, ourTasks, full = full).run()
        }
    }

    private class SessionConnection(
            val conn: Handle,
            val tasks: List<Task>,
            val full: Boolean
    ) {
        private val coroutineScope = CoroutineScope(ForkJoinPool.commonPool().asCoroutineDispatcher())

        private val concurrencyControl = ParserConcurrencyControl.Impl(maxConcurrentSourceFiles = 1024)

        private val refBatch = conn.prepareBatch("insert into binding_reference (realm, target, type, source_artifact_id, source_file_id, source_file_line, source_file_ref_id) VALUES (?, ?, ?, ?, ?, ?, ?)") // TODO
        private val declBatch = conn.prepareBatch("insert into binding (realm, artifact_id, binding_id, binding, source_file_id, include_in_type_search, description, modifiers, parent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")

        private val dbWriter = BackPressureExecutor(16)

        private val cborMapper = ObjectMapper(CompressedFactory()).registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))
        private val jsonMapper = ObjectMapper().registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))

        fun run() {
            conn.begin()

            val artifactIds = tasks.map { it.artifactId }

            if (full) {
                log.info("Creating WIP schema")
                conn.update("create schema wip")
                conn.update("set search_path to wip")
                // create tables in new schema
                GeneratorSchema(conn).createSchema()
            } else {
                log.info("Cleaning up in preparation for {}", artifactIds) // recreate indices
                for (artifactId in artifactIds) {
                    conn.update("delete from artifact where string_id = ?", artifactId)
                }
            }

            val futures = ArrayList<Deferred<Unit>>()
            for (task in tasks) {
                val artifactId = (conn.createQuery("insert into artifact (string_id, last_compile_version, metadata) values (?, ?, ?) returning artifact_id")
                        .bind(0, task.artifactId)
                        .bind(1, COMPILER_VERSION)
                        .bind(2, jsonMapper.writeValueAsBytes(task.metadata))
                        .single()["artifact_id"] as Number).toLong()

                val printerImpl = PrinterImpl(artifactId, task.artifactId)
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

            if (full) {
                log.info("Creating indices")
                GeneratorSchema(conn).createIndices()
                GeneratorSchema(conn).updateViews(concurrent = false)

                log.info("Replacing schema")
                conn.update("drop schema data cascade")
                conn.update("alter schema wip rename to data")
                // reset connection search_path in case the connection is reused
                conn.update("set search_path to data")
            }

            conn.commit()

            if (!full) {
                conn.begin()
                GeneratorSchema(conn).updateViews(concurrent = true)
                conn.commit()
            }
        }

        private inner class PrinterImpl(val artifactId: Long, val artifactStringId: String) : PrinterWithDependencies {
            var hasFiles = false
            var nextSourceFileId: Long = 0

            private val allPackages = HashSet<String>()
            private val explicitPackages = LongSets.mutable.empty()

            override val concurrencyControl: ParserConcurrencyControl
                get() = this@SessionConnection.concurrencyControl

            @Suppress("UnstableApiUsage")
            override fun hashBinding(binding: String): BindingId {
                if (binding.isEmpty()) return BindingId.TOP_LEVEL_PACKAGE
                return BindingId(BINDING_HASHER.hashString(binding, Charsets.UTF_8).asLong())
            }

            override fun addDependency(dependency: String) {
                dbWriter.submit {
                    conn.insert("insert into dependency (from_artifact, to_artifact) values (?, ?)",
                            artifactId,
                            dependency)
                }
            }

            override fun addAlias(alias: String) {
                dbWriter.submit {
                    conn.insert("insert into artifact_alias (artifact_id, alias) values (?, ?)",
                            artifactId,
                            alias)
                }
            }

            fun finish() {
                if (!hasFiles) {
                    throw RuntimeException("No source files on $artifactStringId")
                }

                dbWriter.submit {
                    for (pkg in allPackages) {
                        val id = hashBinding(pkg)
                        if (explicitPackages.contains(id.hash)) continue
                        val lastDot = pkg.lastIndexOf('.')
                        val parent = when {
                            pkg.isEmpty() -> null
                            lastDot == -1 -> BindingId.TOP_LEVEL_PACKAGE
                            else -> hashBinding(pkg.substring(0, lastDot))
                        }
                        addDecl(
                                Realm.SOURCE,
                                BindingDecl(
                                        id = id,
                                        binding = pkg,
                                        parent = parent,
                                        superBindings = emptyList(),
                                        modifiers = 0,
                                        description = BindingDecl.Description.Package
                                ),
                                sourceFileId = null
                        )
                    }

                    conn.update("select pg_notify('artifact', ?)", artifactStringId)
                }
                log.info("$artifactStringId is ready")
            }

            private fun storeTokens(table: String,
                                    realm: Realm,
                                    artifactId: Long,
                                    sourceFile: Long,
                                    tokens: List<Tokenizer.Token>) {
                val lexemes = TsVector()
                val start = IntLists.mutable.empty()
                val length = IntLists.mutable.empty()

                fun flush() {
                    if (start.isEmpty) return

                    conn.update(
                            "insert into $table (realm, artifact_id, source_file_id, lexemes, starts, lengths) values (?, ?, ?, ?::tsvector, ?, ?)",
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
                val sourceFileId = nextSourceFileId++
                conn.insert(
                        "insert into source_file (realm, artifact_id, source_file_id, path, text, annotations) values (?, ?, ?, ?, ?, ?)",
                        realm.id,
                        artifactId,
                        sourceFileId,
                        path,
                        sourceFile.text.toByteArray(Charsets.UTF_8),
                        cborMapper.writeValueAsBytes(sourceFile.entries)
                )

                if (realm == Realm.SOURCE) {
                    storeTokens("source_file_lexemes", realm, artifactId, sourceFileId, tokens)
                    storeTokens("source_file_lexemes_no_symbols",
                            realm,
                            artifactId,
                            sourceFileId,
                            tokens.filter { !it.symbol })
                } else {
                    require(tokens.isEmpty()) // TODO
                }

                if (sourceFile.pkg != null) {
                    val packageParts = sourceFile.pkg.split('.')
                    for (endExclusive in 0..packageParts.size) {
                        allPackages.add(packageParts.subList(0, endExclusive).joinToString("."))
                    }
                }

                val lineNumberTable = LineNumberTable(sourceFile.text)

                for (entry in sourceFile.entries) {
                    val annotation = entry.annotation
                    if (annotation is BindingRef) {
                        if (!annotation.duplicate) {
                            refBatch.add(
                                    realm.id,
                                    annotation.binding.hash,
                                    annotation.type.id,
                                    artifactId,
                                    sourceFileId,
                                    lineNumberTable.lineAt(entry.start),
                                    annotation.id
                            )
                        }
                    } else if (annotation is BindingDecl) {
                        addDecl(realm, annotation, sourceFileId)

                        if (annotation.description is BindingDecl.Description.Package) {
                            explicitPackages.add(annotation.id.hash)
                        }
                    }
                }
                refBatch.execute()
                declBatch.execute()
            }

            private fun addDecl(realm: Realm, annotation: BindingDecl, sourceFileId: Long?) {
                val includeInTypeSearch = annotation.description is BindingDecl.Description.Type &&
                        annotation.modifiers and (BindingDecl.MODIFIER_ANONYMOUS or BindingDecl.MODIFIER_LOCAL) == 0
                declBatch.add(
                        realm.id,
                        artifactId,
                        annotation.id.hash,
                        annotation.binding,
                        sourceFileId,
                        includeInTypeSearch,
                        cborMapper.writeValueAsBytes(annotation.description),
                        annotation.modifiers,
                        annotation.parent?.hash
                )
            }
        }
    }

    private class Task(
            val artifactId: String,
            val metadata: ArtifactMetadata,
            val closure: suspend (PrinterWithDependencies) -> Unit
    )
}