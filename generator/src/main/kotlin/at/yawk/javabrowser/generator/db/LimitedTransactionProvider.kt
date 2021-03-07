package at.yawk.javabrowser.generator.db

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class LimitedTransactionProvider(
    private val delegate: TransactionProvider,
    concurrentTransactions: Int = 3
) : TransactionProvider {
    private val semaphore = Semaphore(concurrentTransactions)

    override suspend fun claimArtifactId() = semaphore.withPermit {
        delegate.claimArtifactId()
    }

    override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) =
        semaphore.withPermit {
            delegate.withArtifactTransaction(artifactId, task)
        }
}