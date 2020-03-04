package at.yawk.javabrowser.generator

import kotlinx.coroutines.sync.Mutex
import kotlin.math.min

interface ParserConcurrencyControl {
    suspend fun <R> fetchMavenDeps(f: suspend () -> R): R

    suspend fun runParser(sourceFileCount: Int, f: suspend () -> Unit)

    object NoLimit : ParserConcurrencyControl {
        override suspend fun runParser(sourceFileCount: Int, f: suspend () -> Unit) {
            f()
        }

        override suspend fun <R> fetchMavenDeps(f: suspend () -> R): R = f()
    }

    class Impl(private val maxConcurrentSourceFiles: Int) : ParserConcurrencyControl {
        private val sourceFileCount = MultiSemaphore(maxConcurrentSourceFiles)
        private val mavenLock = Mutex()

        override suspend fun runParser(
                sourceFileCount: Int,
                f: suspend () -> Unit
        ) {
            val reducedSourceFileCount = min(sourceFileCount, maxConcurrentSourceFiles)
            this.sourceFileCount.withPermits(reducedSourceFileCount) { f() }
        }

        override suspend fun <R> fetchMavenDeps(f: suspend () -> R): R {
            mavenLock.lock()
            try {
                return f()
            } finally {
                mavenLock.unlock()
            }
        }
    }
}