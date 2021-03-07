package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.TsVector
import org.intellij.lang.annotations.Language

interface Transaction {
    suspend fun insertArtifact(id: Long, stringId: String, compilerVersion: Int, metaBytes: ByteArray): Unit

    suspend fun insertDependency(from: Long, to: String)

    suspend fun insertAlias(from: Long, alias: String)

    suspend fun insertSourceFile(
        realm: Realm,
        artifactId: Long,
        sourceFileId: Long,
        hash: Long,
        path: String,
        textBytes: ByteArray,
        annotationBytes: ByteArray
    )

    suspend fun insertRef(
        realm: Realm,
        binding: BindingId,
        type: BindingRefType,
        artifactId: Long,
        sourceFileId: Long,
        line: Int,
        idInSourceFile: Int
    )

    suspend fun insertDecl(
        realm: Realm,
        artifactId: Long,
        bindingId: BindingId,
        binding: String,
        sourceFileId: Long?,
        includeInTypeSearch: Boolean,
        descBytes: ByteArray,
        modifiers: Int,
        parent: BindingId?
    )

    suspend fun insertLexemes(
        set: FullTextSearchSet,
        realm: Realm,
        artifactId: Long,
        sourceFileId: Long,
        lexemes: TsVector,
        starts: IntArray,
        lengths: IntArray
    )

    enum class FullTextSearchSet(@Language(value = "sql", prefix = "select 1 from ") val table: String) {
        NORMAL("source_file_lexemes"),
        NO_SYMBOLS("source_file_lexemes_no_symbols"),
    }
}