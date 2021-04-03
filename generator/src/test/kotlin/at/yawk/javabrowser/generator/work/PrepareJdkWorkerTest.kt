package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.ResumingUrlInputStream
import at.yawk.javabrowser.generator.SourceSetConfig
import at.yawk.javabrowser.generator.db.TestTransaction
import at.yawk.javabrowser.generator.db.Transaction
import at.yawk.javabrowser.generator.db.TransactionProvider
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.testng.Assert
import org.testng.annotations.Test
import java.net.URL
import java.nio.file.Files

class PrepareJdkWorkerTest {
    @Test(groups = ["longRunningDownload"])
    fun zstd() {
        val url =
            ResumingUrlInputStream(URL("https://ci.yawk.at/job/openjdk/job/openjdk9/lastSuccessfulBuild/artifact/jdk9.tar.zst"))
        val zstd = ZstdInputStreamNoFinalizer(url)
        val tar = TarArchiveInputStream(zstd.buffered())
        while (true) {
            tar.nextEntry ?: break
        }
    }

    @Test(groups = ["longRunningDownload"])
    fun `java 14`() {
        var hasByteBuffer = false
        val config = ArtifactConfig.Java(
            version = "14",
            archiveUrl = URL("https://ci.yawk.at/job/openjdk/job/openjdk14/lastSuccessfulBuild/artifact/jdk14.tar.zst"),
            jigsaw = true,
            metadata = ArtifactMetadata()
        )
        runBlocking {
            val worker = PrepareJdkWorker(TempDirProviderTest)
            worker.prepareArtifact(
                worker.getArtifactId(config),
                config,
                object : PrepareArtifactWorker.PrepareListener {
                    override fun acceptMetadata(metadata: PrepareArtifactWorker.Metadata) {
                    }

                    override suspend fun compileSourceSet(config: SourceSetConfig) {
                        hasByteBuffer = hasByteBuffer or Files.walk(config.sourceRoot)
                            .anyMatch { it.fileName.toString() == "ByteBuffer.java" }
                    }
                }
            )
        }
        Assert.assertTrue(hasByteBuffer)
    }

    @Test(groups = ["longRunningDownload"])
    fun `java 14 compilation integration test`() {
        val config = ArtifactConfig.Java(
            version = "14",
            archiveUrl = URL("https://ci.yawk.at/job/openjdk/job/openjdk14/lastSuccessfulBuild/artifact/jdk14.tar.zst"),
            jigsaw = true,
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
            ).forArtifact("java/14") { compileWorker ->
                val prepWorker = PrepareJdkWorker(TempDirProviderTest)
                prepWorker.prepareArtifact(
                    prepWorker.getArtifactId(config),
                    config,
                    compileWorker
                )
            }
        }
    }

    @Test(groups = ["longRunningDownload"])
    fun `java 16 compilation integration test`() {
        val config = ArtifactConfig.Java(
            version = "16",
            archiveUrl = URL("https://ci.yawk.at/job/openjdk/job/openjdk16/lastSuccessfulBuild/artifact/jdk16.tar.zst"),
            jigsaw = true,
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
            ).forArtifact("java/16") { compileWorker ->
                val prepWorker = PrepareJdkWorker(TempDirProviderTest)
                prepWorker.prepareArtifact(
                    prepWorker.getArtifactId(config),
                    config,
                    compileWorker
                )
            }
        }
    }

    @Test(groups = ["longRunningDownload"])
    fun `java 7 compilation integration test`() {
        val config = ArtifactConfig.Java(
            version = "7",
            archiveUrl = URL("https://ci.yawk.at/job/openjdk/job/openjdk7/lastSuccessfulBuild/artifact/jdk7.tar.zst"),
            jigsaw = false,
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
            ).forArtifact("java/7") { compileWorker ->
                val prepWorker = PrepareJdkWorker(TempDirProviderTest)
                prepWorker.prepareArtifact(
                    prepWorker.getArtifactId(config),
                    config,
                    compileWorker
                )
            }
        }
    }
}