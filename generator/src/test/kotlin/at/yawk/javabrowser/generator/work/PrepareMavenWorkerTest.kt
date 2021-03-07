package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.MavenDependencyResolver
import at.yawk.javabrowser.generator.SourceSetConfig
import kotlinx.coroutines.runBlocking
import org.testng.Assert
import org.testng.annotations.Test
import java.nio.file.Files
import java.util.concurrent.atomic.LongAdder

class PrepareMavenWorkerTest {
    @Test
    fun metadata() {
        Assert.assertEquals(
            PrepareMavenWorker.resolveMavenMetadata(
                ArtifactConfig.Maven(
                    "com.google.guava",
                    "guava",
                    "25.1-jre"
                )
            ),
            ArtifactMetadata(
                licenses = listOf(
                    ArtifactMetadata.License(
                        name = "The Apache Software License, Version 2.0",
                        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    )
                ),
                url = "https://github.com/google/guava/guava",
                description = "Guava is a suite of core and expanded libraries that include\n" +
                        "    utility classes, google's collections, io classes, and much\n" +
                        "    much more.",
                issueTracker = ArtifactMetadata.IssueTracker(
                    type = "GitHub Issues",
                    url = "https://github.com/google/guava/issues"
                ),
                developers = listOf(
                    ArtifactMetadata.Developer(
                        name = "Kevin Bourrillion",
                        email = "kevinb@google.com",
                        organization = ArtifactMetadata.Organization(
                            name = "Google", url = "http://www.google.com"
                        )
                    )
                )
            )
        )
        // this one has a parent pom
        Assert.assertEquals(
            PrepareMavenWorker.resolveMavenMetadata(
                ArtifactConfig.Maven(
                    "io.undertow",
                    "undertow-core",
                    "2.0.9.Final"
                )
            ),
            ArtifactMetadata(
                licenses = listOf(
                    ArtifactMetadata.License(
                        name = "Apache License Version 2.0",
                        url = "http://repository.jboss.org/licenses/apache-2.0.txt"
                    )
                ),
                url = "http://www.jboss.org/undertow-parent/undertow-core",
                description = "Undertow",
                issueTracker = ArtifactMetadata.IssueTracker(
                    type = "JIRA",
                    url = "https://issues.jboss.org/"
                ),
                organization = ArtifactMetadata.Organization(
                    name = "JBoss by Red Hat",
                    url = "http://www.jboss.org"
                ),
                developers = listOf(
                    ArtifactMetadata.Developer(
                        name = "JBoss.org Community",
                        organization = ArtifactMetadata.Organization(
                            name = "JBoss.org", url = "http://www.jboss.org"
                        )
                    )
                )
            )
        )

        PrepareMavenWorker.resolveMavenMetadata(
            ArtifactConfig.Maven(
                "org.openjfx",
                "javafx-controls",
                "11"
            )
        )
    }

    @Test
    fun `spring-instrument`() {
        val sourceFileCount = LongAdder()
        val worker = PrepareMavenWorker(TempDirProviderTest, MavenDependencyResolver())
        val config = ArtifactConfig.Maven("org.springframework", "spring-instrument", "5.1.5.RELEASE")
        runBlocking {
            worker.prepareArtifact(
                worker.getArtifactId(config),
                config,
                object : PrepareArtifactWorker.PrepareListener {
                    override fun acceptMetadata(metadata: PrepareArtifactWorker.Metadata) {
                    }

                    override suspend fun compileSourceSet(config: SourceSetConfig) {
                        sourceFileCount.add(Files.walk(config.sourceRoot)
                            .filter { Files.isRegularFile(it) }
                            .filter { it.toString().endsWith(".java") }
                            .count())
                    }
                }
            )
        }
        Assert.assertEquals(sourceFileCount.sum(), 1)
    }

    @Test
    fun guava() {
        val sourceFileCount = LongAdder()
        val worker = PrepareMavenWorker(TempDirProviderTest, MavenDependencyResolver())
        val config = ArtifactConfig.Maven("com.google.guava", "guava", "25.1-jre")
        runBlocking {
            worker.prepareArtifact(
                worker.getArtifactId(config),
                config,
                object : PrepareArtifactWorker.PrepareListener {
                    override fun acceptMetadata(metadata: PrepareArtifactWorker.Metadata) {
                    }

                    override suspend fun compileSourceSet(config: SourceSetConfig) {
                        sourceFileCount.add(Files.walk(config.sourceRoot)
                            .filter { Files.isRegularFile(it) }
                            .filter { it.toString().endsWith(".java") }
                            .count())
                    }
                }
            )
        }
        Assert.assertTrue(sourceFileCount.sum() > 100)
    }
}