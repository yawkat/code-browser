package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.TsVector

open class TestTransaction : Transaction {
    open suspend fun onAnyTask() {
    }

    override suspend fun insertArtifact(id: Long, stringId: String, compilerVersion: Int, metaBytes: ByteArray) {
        onAnyTask()
    }

    override suspend fun insertDependency(from: Long, to: String) {
        onAnyTask()
    }

    override suspend fun insertAlias(from: Long, alias: String) {
        onAnyTask()
    }

    override suspend fun insertSourceFile(
        realm: Realm,
        artifactId: Long,
        sourceFileId: Long,
        hash: Long,
        path: String,
        textBytes: ByteArray,
        annotationBytes: ByteArray
    ) {
        onAnyTask()
    }

    override suspend fun insertRef(
        realm: Realm,
        binding: BindingId,
        type: BindingRefType,
        artifactId: Long,
        sourceFileId: Long,
        line: Int,
        idInSourceFile: Int
    ) {
        onAnyTask()
    }

    override suspend fun insertDecl(
        realm: Realm,
        artifactId: Long,
        bindingId: BindingId,
        binding: String,
        sourceFileId: Long?,
        includeInTypeSearch: Boolean,
        descBytes: ByteArray,
        modifiers: Int,
        parent: BindingId?
    ) {
        onAnyTask()
    }

    override suspend fun insertLexemes(
        set: Transaction.FullTextSearchSet,
        realm: Realm,
        artifactId: Long,
        sourceFileId: Long,
        lexemes: TsVector,
        starts: IntArray,
        lengths: IntArray
    ) {
        onAnyTask()
    }
}