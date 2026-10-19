package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingOrderTest {
    @Test
    fun `completion does not move job or hide transcript`() {
        val older = job("old", JobStatus.COMPLETED, 10)
        val active = job("new", JobStatus.RUNNING, 20)
        val initial = visibleProcessingJobs(listOf(older, active))
        val completed = visibleProcessingJobs(listOf(older, active.copy(status = JobStatus.COMPLETED)))

        assertEquals(listOf("new", "old"), initial.map { it.id })
        assertEquals(initial.map { it.id }, completed.map { it.id })
    }

    @Test
    fun `cancelled tasks are hidden without changing order`() {
        val jobs = listOf(
            job("old", JobStatus.COMPLETED, 10),
            job("cancelled", JobStatus.CANCELLED, 30),
            job("new", JobStatus.PENDING, 20),
        )
        assertEquals(listOf("new", "old"), visibleProcessingJobs(jobs).map { it.id })
    }

    private fun job(id: String, status: JobStatus, createdAt: Long) =
        TranscriptionJob(
            id = id,
            sourceId = "audio",
            config = TranscriptionConfig("tiny", InferenceConfig(threadCount = 2)),
            modelHash = "hash",
            status = status,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
        )
}
