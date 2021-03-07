package at.yawk.javabrowser.generator

interface CoroutineCloseable {
    suspend fun close()
}

suspend inline fun <C : CoroutineCloseable, R> C.use(task: (C) -> R): R {
    var exc: Throwable? = null
    try {
        return task(this)
    } catch (t: Throwable) {
        exc = t
        throw t
    } finally {
        if (exc == null) {
            close()
        } else {
            try {
                close()
            } catch (nested: Throwable) {
                exc.addSuppressed(nested)
            }
        }
    }
}