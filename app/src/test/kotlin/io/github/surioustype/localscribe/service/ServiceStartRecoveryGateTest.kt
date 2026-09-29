package io.github.surioustype.localscribe.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceStartRecoveryGateTest {
    @Test
    fun `cold service start waits for recovery before it queues execution`() =
        runTest {
            val events = mutableListOf<String>()
            val gate =
                ServiceStartRecoveryGate(
                    recover = { events += "recover" },
                    queue = { jobId, startId -> events += "queue:$jobId:$startId" },
                    onFailure = { events += "failure:$it" },
                )

            gate.start("interrupted-job", 7)

            assertEquals(listOf("recover", "queue:interrupted-job:7"), events)
        }

    @Test
    fun `recovery failure does not queue execution`() =
        runTest {
            val events = mutableListOf<String>()
            val gate =
                ServiceStartRecoveryGate(
                    recover = { error("database unavailable") },
                    queue = { _, _ -> events += "queue" },
                    onFailure = { events += "failure" },
                )

            gate.start("job", 3)

            assertEquals(listOf("failure"), events)
        }
}
