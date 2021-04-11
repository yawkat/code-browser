package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.MavenDependencyResolver
import at.yawk.javabrowser.generator.db.TestTransaction
import at.yawk.javabrowser.generator.db.Transaction
import at.yawk.javabrowser.generator.db.TransactionProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import org.testng.Assert
import org.testng.annotations.Test
import java.net.URL

class PrepareGraalWorkerTest {
    @Test
    fun `parse suite`() {
        val suite = PrepareGraalWorker.parseSuite("""
suite = {
    "name": "test",
    "projects": {
        "at.yawk": {
            "subDir": "src", 
            "sourceDirs": ["src"], 
            "dependencies": ["sdk:GRAAL_SDK"]
        }
    }
}
""")
        Assert.assertEquals(
            suite,
            PrepareGraalWorker.Suite(
                name = "test",
                projects = mapOf(
                    "at.yawk" to PrepareGraalWorker.Suite.Project(
                        "src",
                        listOf("src"),
                        listOf("sdk:GRAAL_SDK")
                    )
                )
            )
        )
    }

    @Test(groups = ["longRunningDownload"])
    fun `download test`() {
        val config = ArtifactConfig.Graal(
            repos = listOf(
                URL("https://github.com/graalvm/mx/archive/91fbc57445fc24bf2d20b57a5a0a7caf82a8b3aa.zip"),
                URL("https://github.com/oracle/graal/archive/vm-21.0.0.2.zip"),
                URL("https://github.com/oracle/graaljs/archive/vm-21.0.0.2.zip"),
                URL("https://github.com/oracle/graalpython/archive/vm-21.0.0.2.zip"),
                URL("https://github.com/oracle/truffleruby/archive/vm-21.0.0.2.zip"),
            ),
            version = "21.0.0.2",
            jdk = URL("https://github.com/graalvm/labs-openjdk-11/releases/download/jvmci-19.3-b25/labsjdk-ce-11.0.11+8-jvmci-19.3-b25-linux-amd64.tar.gz"),
            metadata = ArtifactMetadata()
        )
        runBlocking {
            CompileWorker(
                transactionProvider = object : TransactionProvider {
                    override suspend fun claimArtifactId() = 0L

                    override suspend fun withArtifactTransaction(
                        artifactId: String,
                        task: suspend (Transaction) -> Unit
                    ) {
                        task(TestTransaction())
                    }
                },
                acceptScope = GlobalScope
            ).forArtifact("graal/21.0.0.2") { compileWorker ->
                val prepWorker = PrepareGraalWorker(TempDirProviderTest, MavenDependencyResolver())
                prepWorker.prepareArtifact(
                    prepWorker.getArtifactId(config),
                    config,
                    compileWorker
                )
            }
        }
    }
}