package at.yawk.javabrowser.generator

import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MultiSemaphore(private var permits: Int = 0) {
    private val mutex = Mutex()
    private val waiting = ArrayList<Pair<Continuation<Unit>, Int>>()

    suspend inline fun <R> withPermits(n: Int, f: () -> R): R {
        acquire(n)
        var tr: Throwable? = null
        try {
            return f()
        } catch (t: Throwable) {
            tr = t
            throw t
        } finally {
            try {
                release(n)
            } catch (t: Throwable) {
                if (tr != null) {
                    tr.addSuppressed(t)
                } else {
                    throw t
                }
            }
        }
    }

    suspend fun acquire(n: Int) {
        mutex.lock()
        if (permits >= n) {
            permits -= n
            mutex.unlock()
        } else {
            // wait for permits to become available
            return suspendCoroutine { continuation ->
                waiting.add(continuation to n)
                mutex.unlock()
            }
        }
    }

    suspend fun release(n: Int) {
        mutex.lock()
        permits += n
        val toContinue = ArrayList<Continuation<Unit>>()
        val iterator = waiting.iterator()
        while (iterator.hasNext()) {
            val (continuation, required) = iterator.next()
            if (permits >= required) {
                permits -= required
                toContinue.add(continuation)
                iterator.remove()
            }
        }
        mutex.unlock()

        for (continuation in toContinue) {
            continuation.resume(Unit)
        }
    }
}