package at.yawk.javabrowser.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.skife.jdbi.v2.Handle
import org.testng.Assert
import org.testng.annotations.Test

/**
 * @author yawkat
 */
class CompilerTest {
    @Test
    fun maven() {
        val dbi = createDb()
        val compiler = Compiler(dbi, ObjectMapper().findAndRegisterModules())
        compiler.compileMaven("com.google.guava/guava/25.1-jre",
                ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre"))

        dbi.inTransaction { conn: Handle, _ ->
            Assert.assertEquals(
                    conn.select("select * from artifacts"),
                    listOf(mapOf(
                            "id" to "com.google.guava/guava/25.1-jre",
                            "lastcompileversion" to Compiler.VERSION
                    ))
            )

            Assert.assertTrue(
                    (conn.select("select count(*) from sourceFiles").single().values.single() as Number).toInt() > 100
            )

            println((conn.select("select * from sourceFiles limit 1").single()["json"] as ByteArray)
                    .toString(Charsets.UTF_8))
        }
    }
}