package at.yawk.javabrowser.generator

import at.yawk.javabrowser.loadScript
import org.jdbi.v3.core.Handle
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GeneratorSchema::class.java)

class GeneratorSchema(private val conn: Handle) {
    private fun executeAndLogStatement(stmt: String) {
        log.info("{}", stmt)
        conn.createUpdate(stmt).execute()
    }

    fun createSchema() {
        // this is fast, don't log
        conn.loadScript("/at/yawk/javabrowser/generator/DataSchema.sql").execute()
    }

    fun createIndices() {
        val script = conn.loadScript("/at/yawk/javabrowser/generator/DataIndex.sql")
        for (statement in script.statements) {
            executeAndLogStatement(statement)
        }
    }

    fun updateViews(concurrent: Boolean) {
        for (view in listOf("binding_reference_count_view")) {
            executeAndLogStatement("refresh materialized view " + (if (concurrent) "concurrently " else "") + view)
        }
    }
}