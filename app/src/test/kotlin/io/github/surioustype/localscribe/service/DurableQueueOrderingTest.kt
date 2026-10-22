package io.github.surioustype.localscribe.service

import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableQueueOrderingTest {
    private fun job(
        id: String,
        created: Long,
        status: JobStatus = JobStatus.PENDING,
    ) = TranscriptionJob(
        id = id,
        sourceId = "source",
        config = TranscriptionConfig("tiny", InferenceConfig(threadCount = 2)),
        modelHash = "hash",
        status = status,
        createdAtEpochMs = created,
        updatedAtEpochMs = created,
    )

    @Test
    fun `orders durable pending jobs by priority then creation and stable ID`() {
        val jobs = listOf(job("normal", 1), job("low", 0), job("high", 50), job("high2", 50))
        val priority =
            mapOf(
                "high" to QueuePriority.HIGH,
                "high2" to QueuePriority.HIGH,
                "low" to QueuePriority.LOW,
            )

        assertEquals(
            listOf("high", "high2", "normal", "low"),
            pendingTranscriptionQueue(jobs, priority).map { it.id },
        )
    }

    @Test
    fun `does not restart manually paused or terminal attempts after process restart`() {
        val jobs =
            listOf(
                job("pending", 10),
                job("paused", 1, JobStatus.PAUSED),
                job("running", 2, JobStatus.RUNNING),
                job("failed", 3, JobStatus.FAILED),
                job("cancelled", 4, JobStatus.CANCELLED),
                job("complete", 5, JobStatus.COMPLETED),
            )

        assertEquals(
            listOf("pending"),
            pendingTranscriptionQueue(jobs, emptyMap()).map { it.id },
        )
    }
}
