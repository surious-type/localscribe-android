package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingPresentationTest {
    @Test
    fun latestCompletedTranscriptWinsEvenIfJobsAreUnsorted() {
        val newer = job("newer", "recording", JobStatus.COMPLETED, created = 5, completed = 100)
        val older = job("older", "recording", JobStatus.COMPLETED, created = 1, completed = 20)
        val pending = job("pending", "recording", JobStatus.RUNNING, created = 10)
        val unrelated = job("other", "another", JobStatus.COMPLETED, created = 20, completed = 200)

        assertEquals(
            "newer",
            latestCompletedTranscriptionJob(
                listOf(pending, unrelated, older, newer),
                "recording",
            )?.id,
        )
    }

    @Test
    fun recordingWithoutFinishedAttemptOpensSetup() {
        assertNull(
            latestCompletedTranscriptionJob(
                listOf(
                    job("running", "recording", JobStatus.RUNNING, created = 2),
                    job("cancelled", "recording", JobStatus.CANCELLED, created = 1),
                    job("completed-other", "other", JobStatus.COMPLETED, created = 3),
                ),
                "recording",
            ),
        )
    }

    @Test
    fun fallbackToUpdatedTimeForLegacyCompletedJobs() {
        val older = job("older", "recording", JobStatus.COMPLETED, created = 1, updated = 10)
        val newer = job("newer", "recording", JobStatus.COMPLETED, created = 2, updated = 20)
        assertEquals(
            "newer",
            latestCompletedTranscriptionJob(listOf(newer, older), "recording")?.id,
        )
    }

    private fun job(
        id: String,
        sourceId: String,
        status: JobStatus,
        created: Long,
        updated: Long = created,
        completed: Long? = null,
    ) =
        TranscriptionJob(
            id = id,
            sourceId = sourceId,
            config = TranscriptionConfig("tiny", InferenceConfig(4)),
            modelHash = "hash",
            status = status,
            createdAtEpochMs = created,
            updatedAtEpochMs = updated,
            completedAtEpochMs = completed,
        )
}
