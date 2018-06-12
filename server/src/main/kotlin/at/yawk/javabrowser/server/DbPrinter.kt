package at.yawk.javabrowser.server

import at.yawk.javabrowser.AnnotatedSourceFile
import at.yawk.javabrowser.BindingDecl
import at.yawk.javabrowser.BindingRef
import at.yawk.javabrowser.Printer
import com.fasterxml.jackson.databind.ObjectMapper
import org.skife.jdbi.v2.DBI
import org.skife.jdbi.v2.Handle

/**
 * @author yawkat
 */
class DbPrinter private constructor(
        private val objectMapper: ObjectMapper,
        private val artifactId: String,
        private val conn: Handle
) : Printer {
    companion object {
        fun withPrinter(objectMapper: ObjectMapper, dbi: DBI, artifactId: String, closure: (Printer) -> Unit) {
            dbi.inTransaction { conn: Handle, _ ->
                val dbPrinter = DbPrinter(objectMapper, artifactId, conn)
                dbPrinter.clearOld()
                closure(dbPrinter)
            }
        }
    }

    fun clearOld() {
        conn.update("delete from bindings where artifactId = ?", artifactId)
        conn.update("delete from sourceFiles where artifactId = ?", artifactId)
        conn.update("delete from artifacts where id = ?", artifactId)
        conn.insert("insert into artifacts (id, lastCompileVersion) values (?, ?)", artifactId, Compiler.VERSION)
    }

    private val refBatch = conn.prepareBatch("insert into binding_references (targetBinding, type, sourceArtifactId, sourceFile, sourceFileLine, sourceFileId) VALUES (?, ?, ?, ?, ?, ?)")
    private val declBatch = conn.prepareBatch("insert into bindings (artifactId, binding, sourceFile, isType) VALUES (?, ?, ?, ?)")

    override fun addSourceFile(path: String, sourceFile: AnnotatedSourceFile) {
        conn.insert(
                "insert into sourceFiles (artifactId, path, json) VALUES (?, ?, ?)",
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