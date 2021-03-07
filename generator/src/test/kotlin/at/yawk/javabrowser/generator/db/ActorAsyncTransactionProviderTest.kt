package at.yawk.javabrowser.generator.db

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScope
import org.testng.Assert
import org.testng.annotations.Test

private const val PRINT = false

@ExperimentalCoroutinesApi
class ActorAsyncTransactionProviderTest {
    private fun TestCoroutineScope.step(name: String) {
        if (PRINT) println("Step $name")
        advanceUntilIdle()
        pauseDispatcher()
        if (PRINT) println("/Step $name")
    }

    @Test
    fun test() {
        val workerScope = TestCoroutineScope()
        workerScope.pauseDispatcher()
        var actualTx: TestTx? = null
        val provider = ActorAsyncTransactionProvider(
            workerScope = workerScope,
            actorQueueCapacity = 2,
            delegate = object : TransactionProvider {
                override suspend fun claimArtifactId() = throw AssertionError()

                override suspend fun withArtifactTransaction(artifactId: String, task: suspend (Transaction) -> Unit) {
                    actualTx = TestTx()
                    task(actualTx!!)
                    actualTx!!.closed = true
                }
            }
        )
        Assert.assertNull(actualTx)
        val submitterScope = TestCoroutineScope()
        submitterScope.pauseDispatcher()
        var submittedTasks = 0
        val run = submitterScope.async {
            provider.withArtifactTransaction("foo") {
                for (i in 0 until 6) {
                    if (PRINT) println("submit $submittedTasks")
                    submittedTasks++
                    it.insertAlias(0, "bar")
                }
            }
        }

        Assert.assertEquals(submittedTasks, 0)

        submitterScope.step("submitter")
        // should now be suspended in the submit.
        Assert.assertNotNull(actualTx)
        Assert.assertEquals(submittedTasks, 3)

        workerScope.step("worker")
        // done the three calls.
        Assert.assertEquals(actualTx!!.callCount, 3)
        Assert.assertEquals(submittedTasks, 3)

        submitterScope.step("submitter")
        // should now be suspended in the final submit.
        Assert.assertEquals(submittedTasks, 6)
        Assert.assertEquals(actualTx!!.callCount, 3)

        workerScope.step("worker")
        // all tasks executed, but the submitter should still be suspending, waiting for the worker to complete
        Assert.assertFalse(run.isCompleted)
        Assert.assertEquals(submittedTasks, 6)
        Assert.assertEquals(actualTx!!.callCount, 6)

        submitterScope.step("submitter")
        // done!
        Assert.assertTrue(actualTx!!.closed)
        Assert.assertTrue(run.isCompleted)
    }

    private class TestTx : TestTransaction() {
        var closed = false
        var callCount = 0

        override suspend fun onAnyTask() {
            if (PRINT) println("perform")
            require(!closed)
            callCount++
        }
    }
}