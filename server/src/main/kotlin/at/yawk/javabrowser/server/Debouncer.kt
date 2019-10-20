package at.yawk.javabrowser.server

import java.util.concurrent.locks.ReentrantLock

/**
 * This class facilitates "debouncing" an operation.
 *
 * Class users can "request" that a task be run at an indeterminate time in the future, but importantly *after* the
 * request is made (so that data changes that caused the request are up-to-date). Should many users request this
 * task to be performed while a previous run is still in progress, the task will only be executed once more (hence
 * "debounced").
 *
 * @author yawkat
 */
class Debouncer {
    val runLock = ReentrantLock()
    @Volatile
    var requested = false

    inline fun requestRun(task: () -> Unit) {
        requested = true
        while (runLock.tryLock()) {
            try {
                if (requested) {
                    requested = false
                    task()
                }
            } finally {
                runLock.unlock()
            }
        }
    }
}