package at.yawk.javabrowser.generator.work

import com.google.common.io.MoreFiles
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path

internal inline fun <R> tempDir0(base: Path?, name: String, f: (Path) -> R): R {
    val sanitizedName = name.replace("[^a-zA-Z0-9._]".toRegex(), "_")
    val tmp =
        if (base == null) Files.createTempDirectory(sanitizedName)
        else Files.createTempDirectory(base, sanitizedName)
    try {
        return f(tmp)
    } finally {
        @Suppress("UnstableApiUsage")
        MoreFiles.deleteRecursively(tmp)
    }
}

interface TempDirProvider {
    suspend fun <R> withTempDir(debugTag: String, f: suspend (Path) -> R): R

    class FromDirectory(private val base: Path) : TempDirProvider {
        override suspend fun <R> withTempDir(debugTag: String, f: suspend (Path) -> R) =
            tempDir0(base, debugTag) { f(it) }
    }

    class Limited(
        private val delegate: TempDirProvider,
        maxOpen: Int = 16
    ) : TempDirProvider {
        private val semaphore = Semaphore(maxOpen)

        override suspend fun <R> withTempDir(debugTag: String, f: suspend (Path) -> R): R {
            semaphore.withPermit {
                return delegate.withTempDir(debugTag, f)
            }
        }
    }
}