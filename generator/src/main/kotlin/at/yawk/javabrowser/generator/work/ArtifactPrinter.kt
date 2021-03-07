package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.CompressedFactory
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.Tokenizer
import at.yawk.javabrowser.TsVector
import at.yawk.javabrowser.generator.COMPILER_VERSION
import at.yawk.javabrowser.generator.GeneratorSourceFile
import at.yawk.javabrowser.generator.LineNumberTable
import at.yawk.javabrowser.generator.db.Transaction
import at.yawk.javabrowser.generator.source.Printer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.SingletonSupport
import com.google.common.annotations.VisibleForTesting
import com.google.common.hash.Hashing
import org.eclipse.collections.impl.factory.primitive.IntLists
import org.eclipse.collections.impl.factory.primitive.LongSets
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ArtifactPrinter::class.java)

/**
 * This class transforms the individual source file structures passed to [Printer], to database requests for
 * [Transaction].
 */
class ArtifactPrinter private constructor(
    private val artifactId: Long,
    private val logTag: String,
    private val tx: Transaction
) : Printer {
    companion object {
        @VisibleForTesting
        internal val jsonMapper =
            ObjectMapper().registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))

        @VisibleForTesting
        internal val cborMapper =
            ObjectMapper(CompressedFactory()).registerModule(KotlinModule(singletonSupport = SingletonSupport.CANONICALIZE))

        @Suppress("UnstableApiUsage")
        private val BINDING_HASHER = Hashing.sipHash24()

        @Suppress("UnstableApiUsage")
        private val SOURCE_FILE_HASHER = Hashing.sipHash24()

        suspend fun with(
            tx: Transaction,
            id: Long,
            stringId: String,
            metadata: PrepareArtifactWorker.Metadata,
            task: suspend (Printer) -> Unit
        ) {
            @Suppress("BlockingMethodInNonBlockingContext")
            tx.insertArtifact(id, stringId, COMPILER_VERSION, jsonMapper.writeValueAsBytes(metadata.artifactMetadata))
            metadata.aliases.forEach { tx.insertAlias(id, it) }
            metadata.dependencyArtifactIds.forEach { tx.insertDependency(id, it) }
            val printer = ArtifactPrinter(id, stringId, tx)
            task(printer)
            printer.commit()
        }
    }

    private var hasFiles = false
    private var nextSourceFileId: Long = 0

    private val allPackages = HashSet<String>()
    private val explicitPackages = LongSets.mutable.empty()

    @Suppress("UnstableApiUsage")
    override fun hashBinding(binding: String): BindingId {
        if (binding.isEmpty()) return BindingId.TOP_LEVEL_PACKAGE
        return BindingId(BINDING_HASHER.hashString(binding, Charsets.UTF_8).asLong())
    }

    override suspend fun addSourceFile(
        path: String,
        sourceFile: GeneratorSourceFile,
        tokens: List<Tokenizer.Token>,
        realm: Realm
    ) {
        hasFiles = true

        val sourceFileId = nextSourceFileId++
        val textBytes = sourceFile.text.toByteArray(Charsets.UTF_8)

        @Suppress("BlockingMethodInNonBlockingContext")
        val annotationBytes = cborMapper.writeValueAsBytes(sourceFile.entries)
        /*
         * There is a decision to make here. The source file hash *could* include context information such as
         * binding references. This would allow the hash to reflect subtle changes e.g. in which overload is
         * called. Unfortunately, such changes would not show up in the textual diff, and even if they did,
         * they would be confusing. So, we don't include this information in the hash.
         */
        @Suppress("UnstableApiUsage")
        val hash = SOURCE_FILE_HASHER.newHasher()
            // can't hash artifactId because that would kill the diff.
            // can't hash the file path because that would kill diffs between java versions with/without jigsaw.
            .putInt(textBytes.size).putBytes(textBytes)
            .hash().asLong()
        tx.insertSourceFile(realm, artifactId, sourceFileId, hash, path, textBytes, annotationBytes)

        if (realm == Realm.SOURCE) {
            storeTokens(Transaction.FullTextSearchSet.NORMAL, realm, artifactId, sourceFileId, tokens)
            storeTokens(Transaction.FullTextSearchSet.NO_SYMBOLS,
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
                    val line = lineNumberTable.lineAt(entry.start)
                    tx.insertRef(
                        realm,
                        annotation.binding,
                        annotation.type,
                        artifactId,
                        sourceFileId,
                        line,
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
    }

    suspend fun commit() {
        if (!hasFiles) {
            throw RuntimeException("No source files on $logTag")
        }

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

        log.info("$logTag has finished compilation")
    }

    private suspend fun addDecl(realm: Realm, annotation: BindingDecl, sourceFileId: Long?) {
        val includeInTypeSearch = annotation.description is BindingDecl.Description.Type &&
                annotation.modifiers and (BindingDecl.MODIFIER_ANONYMOUS or BindingDecl.MODIFIER_LOCAL) == 0

        @Suppress("BlockingMethodInNonBlockingContext")
        val descBytes = cborMapper.writeValueAsBytes(annotation.description)
        tx.insertDecl(
            realm,
            artifactId,
            annotation.id,
            annotation.binding,
            sourceFileId,
            includeInTypeSearch,
            descBytes,
            annotation.modifiers,
            annotation.parent
        )
    }

    private suspend fun storeTokens(
        set: Transaction.FullTextSearchSet,
        realm: Realm,
        artifactId: Long,
        sourceFile: Long,
        tokens: List<Tokenizer.Token>
    ) {
        var lexemes = TsVector()
        val start = IntLists.mutable.empty()
        val length = IntLists.mutable.empty()

        suspend fun flush() {
            if (start.isEmpty) return

            tx.insertLexemes(
                set,
                realm,
                artifactId,
                sourceFile,
                lexemes,
                start.toArray(),
                length.toArray()
            )

            lexemes = TsVector() // can't modify submitted tsvector
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
}