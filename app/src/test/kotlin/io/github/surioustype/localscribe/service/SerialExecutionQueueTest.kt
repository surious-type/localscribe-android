package io.github.surioustype.localscribe.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerialExecutionQueueTest {
    @Test
    fun `resume received while the previous execution unwinds runs next`() =
        runTest {
            val firstRelease = CompletableDeferred<Unit>()
            val secondRelease = CompletableDeferred<Unit>()
            val executions = mutableListOf<String>()
            val starts = mutableListOf<String>()
            val idleStartIds = mutableListOf<Int>()
            val queue =
                SerialExecutionQueue(
                    scope = this,
                    execute = { jobId ->
                        executions += jobId
                        if (executions.size == 1) firstRelease.await() else secondRelease.await()
                    },
                    onStarted = starts::add,
                    onIdle = idleStartIds::add,
                )

            queue.request("job", 1)
            runCurrent()
            queue.request("job", 2)
            firstRelease.complete(Unit)
            runCurrent()

            assertEquals(listOf("job", "job"), executions)
            assertEquals(listOf("job", "job"), starts)
            assertTrue(idleStartIds.isEmpty())

            secondRelease.complete(Unit)
            runCurrent()
            assertEquals(listOf(2), idleStartIds)
        }

    @Test
    fun `start for another job is retained while the active execution unwinds`() =
        runTest {
            val firstRelease = CompletableDeferred<Unit>()
            val executions = mutableListOf<String>()
            val idleStartIds = mutableListOf<Int>()
            val queue =
                SerialExecutionQueue(
                    scope = this,
                    execute = { jobId ->
                        executions += jobId
                        if (jobId == "first") firstRelease.await()
                    },
                    onStarted = {},
                    onIdle = idleStartIds::add,
                )

            queue.request("first", 4)
            runCurrent()
            queue.request("second", 5)
            firstRelease.complete(Unit)
            runCurrent()

            assertEquals(listOf("first", "second"), executions)
            assertEquals(listOf(5), idleStartIds)
        }

    @Test
    fun `cancel prevents queued work and idle callbacks`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val executions = mutableListOf<String>()
            val idleStartIds = mutableListOf<Int>()
            val queue =
                SerialExecutionQueue(
                    scope = this,
                    execute = { jobId ->
                        executions += jobId
                        release.await()
                    },
                    onStarted = {},
                    onIdle = idleStartIds::add,
                )

            queue.request("first", 1)
            runCurrent()
            queue.request("second", 2)
            queue.cancel()
            runCurrent()

            assertEquals(listOf("first"), executions)
            assertTrue(idleStartIds.isEmpty())
        }
}
