package at.yawk.javabrowser.generator.db

import at.yawk.javabrowser.generator.CoroutineCloseable
import at.yawk.javabrowser.generator.use
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.withContext

/**
 * [TransactionProvider] implementation that performs DB operations on the [workerScope]. Ops go through a queue of
 * maximum size [actorQueueCapacity] for backpressure.
 *
 * This can be used to limit concurrent db *operations*, though there still may be more concurrent *transactions*.
 */
class ActorAsyncTransactionProvider(
    private val delegate: TransactionProvider,
    private val workerScope: CoroutineScope,
    private val actorQueueCapacity: Int = 16
) : TransactionProvider {
    override suspend fun claimArtifactId() = withContext(workerScope.coroutineContext) {
        delegate.claimArtifactId()
    }

    override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
        delegate.withArtifactTransaction(artifactId) { tx ->
            ForArtifact(tx, artifactId).use {
                task(it.transaction)
            }
        }
    }

    private inner class ForArtifact(
        tx: Transaction,
        artifactStringId: String
    ) : CoroutineCloseable {
        val completion = CompletableDeferred<Unit>()

        @Suppress("EXPERIMENTAL_API_USAGE")
        private val transactionChannel = workerScope.actor<suspend (Transaction) -> Unit>(
            CoroutineName("transactionChannel: $artifactStringId"),
            onCompletion = {
                if (it == null) completion.complete(Unit)
                else completion.completeExceptionally(it)
            },
            capacity = actorQueueCapacity
        ) {
            for (task in this) {
                task(tx)
            }
        }

        val transaction = AsyncTransaction(transactionChannel)

        override suspend fun close() {
            transactionChannel.close()
            completion.await()
        }
    }
}