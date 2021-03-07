package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.SourceSetConfig
import at.yawk.javabrowser.generator.db.TestTransaction
import at.yawk.javabrowser.generator.db.Transaction
import at.yawk.javabrowser.generator.db.TransactionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.testng.Assert
import org.testng.annotations.Test
import java.nio.file.Files
import java.util.concurrent.Executors

@ExperimentalCoroutinesApi
class CompileWorkerTest {
    @Test
    fun test() {
        val acceptScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

        var anyAccept = false

        val compileWorker = CompileWorker(
            acceptScope = acceptScope,
            transactionProvider = object : TransactionProvider {
                override suspend fun claimArtifactId() = 0L

                override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
                    task(object : TestTransaction() {
                        override suspend fun onAnyTask() {
                            anyAccept = true
                        }
                    })
                }
            }
        )
        runBlocking {
            compileWorker.forArtifact("test") { forArtifact ->
                forArtifact.acceptMetadata(
                    PrepareArtifactWorker.Metadata(
                        dependencyArtifactIds = emptyList(),
                        aliases = emptyList(),
                        artifactMetadata = ArtifactMetadata()
                    )
                )
                TempDirProviderTest.withTempDir("CompileWorkerTest") {
                    Files.write(it.resolve("Test.java"), "class Test {}".toByteArray())
                    forArtifact.compileSourceSet(
                        SourceSetConfig(
                            debugTag = "test",
                            dependencies = emptyList(),
                            sourceRoot = it,
                            outputClassesTo = null,
                            quirkIsJavaBase = false,
                            includeRunningVmBootclasspath = true,
                            pathPrefix = ""
                        )
                    )
                }
            }
        }
        Assert.assertTrue(anyAccept)
    }

    private class TestException : Exception()

    @Test(expectedExceptions = [TestException::class])
    fun exception() {
        val acceptScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

        val compileWorker = CompileWorker(
            acceptScope = acceptScope,
            transactionProvider = object : TransactionProvider {
                override suspend fun claimArtifactId() = 0L

                override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
                    task(object : TestTransaction() {
                        override suspend fun onAnyTask() {
                            throw TestException()
                        }
                    })
                }
            }
        )
        runBlocking {
            compileWorker.forArtifact("test") { forArtifact ->
                forArtifact.acceptMetadata(
                    PrepareArtifactWorker.Metadata(
                        dependencyArtifactIds = emptyList(),
                        aliases = emptyList(),
                        artifactMetadata = ArtifactMetadata()
                    )
                )
                TempDirProviderTest.withTempDir("CompileWorkerTest") {
                    Files.write(it.resolve("Test.java"), "class Test {}".toByteArray())
                    forArtifact.compileSourceSet(
                        SourceSetConfig(
                            debugTag = "test",
                            dependencies = emptyList(),
                            sourceRoot = it,
                            outputClassesTo = null,
                            quirkIsJavaBase = false,
                            includeRunningVmBootclasspath = true,
                            pathPrefix = ""
                        )
                    )
                }
            }
        }
    }
}