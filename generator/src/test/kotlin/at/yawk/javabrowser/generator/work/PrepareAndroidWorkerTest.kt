package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.ArtifactMetadata
import at.yawk.javabrowser.generator.ArtifactConfig
import at.yawk.javabrowser.generator.SourceSetConfig
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotNull
import org.testng.annotations.Test
import java.net.URL

class PrepareAndroidWorkerTest {
    @Test(groups = ["longRunningDownload"])
    fun test() {
        val config = ArtifactConfig.Android(
            repos = listOf(
                ArtifactConfig.GitRepo(
                    URL("https://android.googlesource.com/platform/frameworks/base"),
                    "android-9.0.0_r35"
                ),
                ArtifactConfig.GitRepo(
                    URL("https://android.googlesource.com/platform/system/vold"),
                    "android-9.0.0_r35"
                )
            ),
            buildTools = URL("https://dl-ssl.google.com/android/repository/build-tools_r28.0.3-linux.zip"),
            version = "android-9.0.0_r35",
            metadata = ArtifactMetadata()
        )
        val worker = PrepareAndroidWorker(TempDirProviderTest)
        val artifactId = worker.getArtifactId(config)
        assertEquals(artifactId, "android/android-9.0.0_r35")
        runBlocking {
            worker.prepareArtifact(artifactId, config,
                object : PrepareArtifactWorker.PrepareListener {
                    var metadata: PrepareArtifactWorker.Metadata? = null

                    override fun acceptMetadata(metadata: PrepareArtifactWorker.Metadata) {
                        this.metadata = metadata
                    }

                    override suspend fun compileSourceSet(config: SourceSetConfig) {
                        assertNotNull(metadata)
                    }
                }
            )
        }
    }
}