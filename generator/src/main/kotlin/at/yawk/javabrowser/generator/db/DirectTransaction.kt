package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.BindingId
import at.yawk.javabrowser.BindingRefType
import at.yawk.javabrowser.Realm
import at.yawk.javabrowser.TsVector
import org.jdbi.v3.core.Handle

class DirectTransaction(
        private val conn: Handle
) : Transaction {
    private val refBatch = conn.prepareBatch("insert into binding_reference (realm, target, type, source_artifact_id, source_file_id, source_file_line, source_file_ref_id) VALUES (?, ?, ?, ?, ?, ?, ?)") // TODO
    private val declBatch = conn.prepareBatch("insert into binding (realm, artifact_id, binding_id, binding, source_file_id, include_in_type_search, description, modifiers, parent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")

    override suspend fun insertArtifact(id: Long, stringId: String, compilerVersion: Int, metaBytes: ByteArray) {
        conn.createUpdate("insert into artifact (artifact_id, string_id, last_compile_version, metadata) values (?, ?, ?, ?)")
            .bind(0, id)
            .bind(1, stringId)
            .bind(2, compilerVersion)
            .bind(3, metaBytes)
            .execute()
    }

    override suspend fun insertDependency(from: Long, to: String) {
        conn.createUpdate("insert into dependency (from_artifact, to_artifact) values (?, ?)")
            .bind(0, from)
            .bind(1, to)
            .execute()
    }

    override suspend fun insertAlias(from: Long, alias: String) {
        conn.createUpdate("insert into artifact_alias (artifact_id, alias) values (?, ?)")
            .bind(0, from)
            .bind(1, alias)
            .execute()
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
        conn.createUpdate(
                "insert into source_file (realm, artifact_id, source_file_id, hash, path, text, annotations) values (?, ?, ?, ?, ?, ?, ?)")
            .bind(0, realm.id)
            .bind(1, artifactId)
            .bind(2, sourceFileId)
            .bind(3, hash)
            .bind(4, path)
            .bind(5, textBytes)
            .bind(6, annotationBytes)
            .execute()
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
        refBatch.add(
                realm.id,
                binding.hash,
                type.id,
                artifactId,
                sourceFileId,
                line,
                idInSourceFile
        )
        if (refBatch.size() >= 100) refBatch.execute()
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
        declBatch.add(
                realm.id,
                artifactId,
                bindingId.hash,
                binding,
                sourceFileId,
                includeInTypeSearch,
                descBytes,
                modifiers,
                parent?.hash
        )
        if (declBatch.size() >= 100) declBatch.execute()
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
        conn.createUpdate(
                "insert into ${set.table} (realm, artifact_id, source_file_id, lexemes, starts, lengths) values (?, ?, ?, ?::tsvector, ?, ?)")
            .bind(0, realm.id)
            .bind(1, artifactId)
            .bind(2, sourceFileId)
            .bind(3, lexemes.toSql())
            .bind(4, starts)
            .bind(5, lengths)
            .execute()
    }

    fun deleteArtifact(artifactStringId: String) {
        conn.createUpdate("delete from artifact where string_id = ?")
            .bind(0, artifactStringId)
            .execute()
    }

    fun flush() {
        refBatch.execute()
        declBatch.execute()
    }
}