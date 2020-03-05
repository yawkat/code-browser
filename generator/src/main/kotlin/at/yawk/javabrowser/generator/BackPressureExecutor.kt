package at.yawk.javabrowser.generator

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture

class BackPressureExecutor(backlog: Int) {
    companion object {
        private val POISON_PILL: () -> Unit = { throw AssertionError() }
    }

    @Volatile
    private var shutdown = false
    private val queue = ArrayBlockingQueue<() -> Unit>(backlog)
    private val endFuture = CompletableFuture<Unit>()

    fun shutdownAndWait() {
        // the shutdown check isn't completely race-free but it only exists to catch bugs anyway
        this.shutdown = true
        queue.put(POISON_PILL)
        endFuture.get()
    }

    fun work() {
        while (true) {
            val next = queue.take()
            if (next === POISON_PILL) break
            try {
                next()
            } catch (e: Throwable) {
                endFuture.completeExceptionally(e)
                return
            }
        }
        endFuture.complete(Unit)
    }

    fun submit(r: () -> Unit) {
        if (shutdown) throw IllegalStateException("Already shut down")
        queue.put(r)
    }
}