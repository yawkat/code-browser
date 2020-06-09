package at.yawk.javabrowser.generator

import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture

private val log = LoggerFactory.getLogger(BackPressureExecutor::class.java)

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
                log.error("Failure in executor task", e)
                shutdown = true
                while (true) {
                    queue.poll() ?: break
                }
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