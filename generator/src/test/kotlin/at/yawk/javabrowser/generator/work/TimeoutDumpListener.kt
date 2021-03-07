package at.yawk.javabrowser.generator.work

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.test.UncompletedCoroutinesError
import org.testng.ITestResult
import org.testng.TestListenerAdapter
import org.testng.internal.thread.ThreadTimeoutException

@ExperimentalCoroutinesApi
class TimeoutDumpListener : TestListenerAdapter() {
    init {
        DebugProbes.install()
    }

    override fun onTestFailure(result: ITestResult) {
        if (result.throwable is ThreadTimeoutException ||
            result.throwable is UncompletedCoroutinesError
        ) {
            DebugProbes.dumpCoroutines(System.err)
        }
    }
}