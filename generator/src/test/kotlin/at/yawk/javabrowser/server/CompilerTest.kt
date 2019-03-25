package at.yawk.javabrowser.server

import at.yawk.javabrowser.ArtifactMetadata
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

    @Test
    fun `maven metadata`() {
        val dbi = createDb()
        val compiler = Compiler(dbi, ObjectMapper().findAndRegisterModules())

        Assert.assertEquals(
                compiler.resolveMavenMetadata(
                        ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre")),
                ArtifactMetadata(
                        licenses = listOf(ArtifactMetadata.License(name = "The Apache Software License, Version 2.0",
                                url = "http://www.apache.org/licenses/LICENSE-2.0.txt")),
                        url = "https://github.com/google/guava/guava",
                        description = "Guava is a suite of core and expanded libraries that include\n" +
                                "    utility classes, google's collections, io classes, and much\n" +
                                "    much more.",
                        issueTracker = ArtifactMetadata.IssueTracker(type = "GitHub Issues",
                                url = "https://github.com/google/guava/issues"),
                        developers = listOf(
                                ArtifactMetadata.Developer(name = "Kevin Bourrillion",
                                        email = "kevinb@google.com",
                                        organization = at.yawk.javabrowser.ArtifactMetadata.Organization(
                                                name = "Google", url = "http://www.google.com"))
                        ))
        )
        // this one has a parent pom
        Assert.assertEquals(
                compiler.resolveMavenMetadata(
                        ArtifactConfig.Maven("io.undertow", "undertow-core", "2.0.9.Final")),
                ArtifactMetadata(
                        licenses = listOf(ArtifactMetadata.License(name = "Apache License Version 2.0",
                                url = "http://repository.jboss.org/licenses/apache-2.0.txt")),
                        url = "http://www.jboss.org/undertow-parent/undertow-core",
                        description = "Undertow",
                        issueTracker = ArtifactMetadata.IssueTracker(type = "JIRA",
                                url = "https://issues.jboss.org/"),
                        organization = ArtifactMetadata.Organization(name = "JBoss by Red Hat",
                                url = "http://www.jboss.org"),
                        developers = listOf(
                                ArtifactMetadata.Developer(name = "JBoss.org Community",
                                        organization = at.yawk.javabrowser.ArtifactMetadata.Organization(
                                                name = "JBoss.org", url = "http://www.jboss.org"))
                        ))
        )

        compiler.resolveMavenMetadata(
                ArtifactConfig.Maven("org.openjfx", "javafx-controls", "11"))
    }
}