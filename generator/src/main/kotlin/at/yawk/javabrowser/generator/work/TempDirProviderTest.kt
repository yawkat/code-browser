package at.yawk.javabrowser.generator.work

import com.google.common.io.MoreFiles
import java.nio.file.Files
import java.nio.file.Path

object TempDirProviderTest : TempDirProvider {
    private inline fun <R> impl(debugTag: String, f: (Path) -> R): R {
        val tmp = Files.createTempDirectory("code-browser-${debugTag.replace('/', '_')}")
        try {
            return f(tmp)
        } finally {
            @Suppress("UnstableApiUsage")
            MoreFiles.deleteRecursively(tmp)
        }
    }

    fun <R> withTempDirSync(debugTag: String, f: (Path) -> R) = impl(debugTag, f)

    override suspend fun <R> withTempDir(debugTag: String, f: suspend (Path) -> R) = impl(debugTag) { f(it) }
}