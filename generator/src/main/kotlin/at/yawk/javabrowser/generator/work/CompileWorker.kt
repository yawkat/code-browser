package at.yawk.javabrowser.generator.work

import at.yawk.javabrowser.generator.CoroutineCloseable
import at.yawk.javabrowser.generator.MultiSemaphore
import at.yawk.javabrowser.generator.SourceSetConfig
import at.yawk.javabrowser.generator.db.TransactionProvider
import at.yawk.javabrowser.generator.source.Printer
import at.yawk.javabrowser.generator.source.SourceFileParser
import at.yawk.javabrowser.generator.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.actor
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class CompileWorker(
    private val transactionProvider: TransactionProvider,
    private val acceptScope: CoroutineScope,
    private val concurrentSourceFileCapacity: Int = 1024
) {
    private val sourceFileSemaphore = MultiSemaphore(concurrentSourceFileCapacity)

    private suspend fun runCompileJob(printer: Printer, job: CompileJob) {
        try {
            val parser = SourceFileParser(printer, job.sourceSet)
            parser.withSourceFilePermits = { n, f ->
                sourceFileSemaphore.withPermits(min(concurrentSourceFileCapacity, n)) { f() }
            }
            parser.acceptContext = acceptScope.coroutineContext
            parser.compile()
            job.future.complete(Unit)
        } catch (e: Exception) {
            job.future.completeExceptionally(e)
        }
    }

    suspend fun forArtifact(artifactStringId: String, task: suspend (PrepareArtifactWorker.PrepareListener) -> Unit) {
        ForArtifact(artifactStringId, CoroutineScope(coroutineContext)).use {
            task(it)
        }
    }

    private inner class ForArtifact(
        private val artifactStringId: String,
        compileScope: CoroutineScope
    ) : CoroutineCloseable, PrepareArtifactWorker.PrepareListener {
        private val completion = CompletableDeferred<Unit>()
        private lateinit var metadata: PrepareArtifactWorker.Metadata

        @Suppress("EXPERIMENTAL_API_USAGE")
        private val compileChannel = compileScope.actor<CompileJob>(
            CoroutineName("compileChannel: $artifactStringId"),
            onCompletion = {
                if (it == null) completion.complete(Unit)
                else completion.completeExceptionally(it)
            }) {
            val itr = iterator()
            if (!itr.hasNext()) return@actor
            val first = itr.next()
            val id = transactionProvider.claimArtifactId()
            transactionProvider.withArtifactTransaction(artifactStringId) { tx ->
                ArtifactPrinter.with(tx, id, artifactStringId, metadata) { printer ->
                    runCompileJob(printer, first)
                    while (itr.hasNext()) {
                        runCompileJob(printer, itr.next())
                    }
                }
            }
        }

        override fun acceptMetadata(metadata: PrepareArtifactWorker.Metadata) {
            this.metadata = metadata
        }

        override suspend fun compileSourceSet(config: SourceSetConfig) {
            val job = CompileJob(config)
            compileChannel.send(job)
            job.future.await()
        }

        override suspend fun close() {
            compileChannel.close()
            completion.await()
        }
    }

    private class CompileJob(val sourceSet: SourceSetConfig) {
        val future = CompletableDeferred<Unit>()
    }
}