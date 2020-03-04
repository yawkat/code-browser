package at.yawk.javabrowser.generator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.testng.Assert
import org.testng.annotations.Test
import java.util.concurrent.Executors

class MultiSemaphoreTest {
    @Test
    fun test() {
        val sem = MultiSemaphore(4)

        var stage = 0
        fun expectStage(s: Int) {
            println(s)
            Assert.assertEquals(stage, s)
            stage = s + 1
        }

        suspend fun cr1() {
            expectStage(0)

            sem.acquire(1)
            expectStage(1)

            sem.acquire(2)
            expectStage(2)

            sem.acquire(2)
            expectStage(4)
            sem.release(1)

            sem.acquire(2)
            expectStage(7)
            sem.release(1)
        }

        suspend fun cr2() {
            expectStage(3)
            sem.release(1)

            sem.acquire(1)
            expectStage(5)
            sem.release(1)

            expectStage(6)
            sem.release(1)

            sem.acquire(1)
            expectStage(8)
        }

        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher)

        val a1 = scope.async { cr1() }
        val a2 = scope.async { cr2() }

        runBlocking {
            a2.await()
            a1.await()
        }

        expectStage(9)
    }
}