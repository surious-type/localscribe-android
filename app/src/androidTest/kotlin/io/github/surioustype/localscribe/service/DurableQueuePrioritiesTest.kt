package io.github.surioustype.localscribe.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DurableQueuePrioritiesTest {
    @Test
    fun priorityIsPrivateAndDurableAcrossStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstId = "queued-" + UUID.randomUUID()
        val secondId = "queued-" + UUID.randomUUID()
        val first = DurableQueuePriorities(context)

        first.set(firstId, QueuePriority.HIGH)
        first.set(secondId, QueuePriority.LOW)

        val reopened = DurableQueuePriorities(context)
        assertEquals(QueuePriority.HIGH, reopened.priorities.value[firstId])
        assertEquals(QueuePriority.LOW, reopened.priorities.value[secondId])

        reopened.set(firstId, QueuePriority.NORMAL)
        assertEquals(null, DurableQueuePriorities(context).priorities.value[firstId])
        assertEquals(QueuePriority.LOW, DurableQueuePriorities(context).priorities.value[secondId])
    }
}
