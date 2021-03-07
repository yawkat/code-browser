package at.yawk.javabrowser.generator.db

interface TransactionProvider {
    suspend fun claimArtifactId(): Long

    suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit)
}