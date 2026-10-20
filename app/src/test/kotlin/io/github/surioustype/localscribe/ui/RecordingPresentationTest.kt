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

    @Test
    fun completedVersionsAreNewestFirstAndExcludeIncompleteReruns() {
        val initial = job("initial", "recording", JobStatus.COMPLETED, created = 10, completed = 50)
        val improved =
            job(
                "improved",
                "recording",
                JobStatus.COMPLETED,
                created = 20,
                completed = 90,
            )
        val running = job("running", "recording", JobStatus.RUNNING, created = 100)
        val cancelled = job("cancelled", "recording", JobStatus.CANCELLED, created = 120)
        val other = job("other", "another", JobStatus.COMPLETED, created = 150, completed = 200)
        val attempts = listOf(running, initial, other, cancelled, improved)

        assertEquals(
            listOf("improved", "initial"),
            completedTranscriptionVersions(attempts, "recording").map { it.id },
        )
        assertEquals("improved", latestCompletedTranscriptionJob(attempts, "recording")?.id)
    }

    @Test
    fun earlierTranscriptSurvivesFailedOrPausedRerun() {
        val previous =
            job("previous", "recording", JobStatus.COMPLETED, created = 3, completed = 25)
        for (status in listOf(JobStatus.FAILED, JobStatus.PAUSED, JobStatus.CANCELLED)) {
            val attempted = job("again", "recording", status, created = 40)
            assertEquals(
                listOf("previous"),
                completedTranscriptionVersions(listOf(attempted, previous), "recording")
                    .map { it.id },
            )
        }
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
