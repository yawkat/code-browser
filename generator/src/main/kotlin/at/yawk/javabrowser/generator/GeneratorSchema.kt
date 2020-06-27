package at.yawk.javabrowser.generator

import org.skife.jdbi.v2.Handle
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(GeneratorSchema::class.java)

class GeneratorSchema(private val conn: Handle) {
    private fun executeAndLogStatement(stmt: String) {
        log.info("{}", stmt)
        conn.createStatement(stmt).execute()
    }

    fun createSchema() {
        // this is fast, don't log
        conn.createScript("at/yawk/javabrowser/generator/DataSchema.sql").execute()
    }

    fun createIndices() {
        val script = conn.createScript("at/yawk/javabrowser/generator/DataIndex.sql")
        for (statement in script.statements) {
            executeAndLogStatement(statement)
        }
    }

    fun updateViews(concurrent: Boolean) {
        for (view in listOf("binding_reference_count_view", "binding_descendant_count_view")) {
            executeAndLogStatement("refresh materialized view " + if (concurrent) "concurrently " else "" + view)
        }
    }
}