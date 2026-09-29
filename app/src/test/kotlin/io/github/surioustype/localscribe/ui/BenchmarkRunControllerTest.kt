package io.github.surioustype.localscribe.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkRunControllerTest {
    @Test
    fun `stop cancels active benchmark and clears busy state`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val controller = BenchmarkRunController(this)

            assertTrue(controller.start { release.await() })
            runCurrent()
            assertTrue(controller.isBusy)

            controller.onStop()
            runCurrent()

            assertFalse(controller.isBusy)
        }

    @Test
    fun `completion and failure both clear busy state`() =
        runTest {
            val controller = BenchmarkRunController(this)

            assertTrue(controller.start {})
            runCurrent()
            assertFalse(controller.isBusy)

            assertTrue(controller.start { error("native failure") })
            runCurrent()
            assertFalse(controller.isBusy)
        }

    @Test
    fun `stop retains ownership until cancelled benchmark finishes then allows restart`() =
        runTest {
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val controller = BenchmarkRunController(this)

            assertTrue(
                controller.start {
                    try {
                        awaitCancellation()
                    } finally {
                        cleanupStarted.complete(Unit)
                        withContext(NonCancellable) { releaseCleanup.await() }
                    }
                },
            )
            runCurrent()

            controller.onStop()
            runCurrent()

            assertTrue(cleanupStarted.isCompleted)
            assertTrue(controller.isBusy)
            assertFalse(controller.start {})

            releaseCleanup.complete(Unit)
            runCurrent()

            assertFalse(controller.isBusy)
            assertTrue(controller.start {})
            runCurrent()
        }

    @Test
    fun `immediate completion and failure release ownership`() {
        val controller =
            BenchmarkRunController(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        assertTrue(controller.start {})
        assertFalse(controller.isBusy)

        assertTrue(controller.start { error("native failure") })
        assertFalse(controller.isBusy)
    }
}
