package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.TsVector
import kotlinx.coroutines.channels.SendChannel

class AsyncTransaction(
        private val target: SendChannel<suspend (Transaction) -> Unit>
) : Transaction {
    private suspend fun async(task: suspend (Transaction) -> Unit) {
        target.send(task)
    }

    override suspend fun insertArtifact(id: Long, stringId: String, compilerVersion: Int, metaBytes: ByteArray) = async {
        it.insertArtifact(id, stringId, compilerVersion, metaBytes)
    }

    override suspend fun insertDependency(from: Long, to: String) = async {
        it.insertDependency(from, to)
    }

    override suspend fun insertAlias(from: Long, alias: String) = async {
        it.insertAlias(from, alias)
    }

    override suspend fun insertSourceFile(realm: Realm, artifactId: Long, sourceFileId: Long, hash: Long, path: String, textBytes: ByteArray, annotationBytes: ByteArray) = async {
        it.insertSourceFile(realm, artifactId, sourceFileId, hash, path, textBytes, annotationBytes)
    }

    override suspend fun insertRef(realm: Realm, binding: BindingId, type: BindingRefType, artifactId: Long, sourceFileId: Long, line: Int, idInSourceFile: Int) = async {
        it.insertRef(realm, binding, type, artifactId, sourceFileId, line, idInSourceFile)
    }

    override suspend fun insertDecl(realm: Realm, artifactId: Long, bindingId: BindingId, binding: String, sourceFileId: Long?, includeInTypeSearch: Boolean, descBytes: ByteArray, modifiers: Int, parent: BindingId?) = async {
        it.insertDecl(realm, artifactId, bindingId, binding, sourceFileId, includeInTypeSearch, descBytes, modifiers, parent)
    }

    override suspend fun insertLexemes(set: Transaction.FullTextSearchSet, realm: Realm, artifactId: Long, sourceFileId: Long, lexemes: TsVector, starts: IntArray, lengths: IntArray) = async {
        it.insertLexemes(set, realm, artifactId, sourceFileId, lexemes, starts, lengths)
    }
}