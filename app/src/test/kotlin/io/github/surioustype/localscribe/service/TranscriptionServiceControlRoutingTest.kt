package io.github.surioustype.localscribe.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionServiceControlRoutingTest {
    @Test
    fun `queued start keeps controls on active job until queued execution starts`() =
        runTest {
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            val routing = TranscriptionServiceControlRouting()
            val queue =
                SerialExecutionQueue(
                    scope = this,
                    execute = { jobId ->
                        if (jobId == "first") firstRelease.await() else secondRelease.await()
                    },
                    onStarted = routing::executionStarted,
                    onIdle = { routing.executionIdle() },
                )

            queue.request("first", 1)
            runCurrent()
            assertEquals("first", routing.notificationJobIdFor("first"))

            queue.request("second", 2)
            assertEquals("first", routing.notificationJobIdFor("second"))

            firstRelease.complete(Unit)
            runCurrent()
            assertEquals("second", routing.notificationJobIdFor("third"))

            secondRelease.complete(Unit)
            runCurrent()
        }
}
