package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPresentationTest {
    private fun source(access: SourceAccessStatus = SourceAccessStatus.AVAILABLE) =
        AudioSourceRecord(
            source = AudioSource("audio", "content://audio/1", "Лекция", 60_000L, "audio/wav"),
            accessStatus = access,
            hasPersistedPermission = false,
        )

    private fun job(status: JobStatus, created: Long = 1L) =
        TranscriptionJob(
            id = "job-" + status.name + created,
            sourceId = "audio",
            config = TranscriptionConfig("small", InferenceConfig(4, "ru")),
            modelHash = "model-sha",
            status = status,
            createdAtEpochMs = created,
            updatedAtEpochMs = created,
        )

    @Test
    fun runningOlderAttemptIsNotMislabelledAsFinished() {
        val state =
            libraryRecordPresentation(
                source(),
                listOf(job(JobStatus.RUNNING, 1L), job(JobStatus.COMPLETED, 2L)),
            )
        assertEquals(LibraryRecordState.RUNNING, state.state)
        assertEquals("Распознаётся", state.status)
    }

    @Test
    fun oldCompletedTranscriptRemainsReadyAfterNewFailedAttempt() {
        val state =
            libraryRecordPresentation(
                source(),
                listOf(job(JobStatus.COMPLETED, 1L), job(JobStatus.FAILED, 2L)),
            )
        assertEquals(LibraryRecordState.READY, state.state)
    }

    @Test
    fun missingAudioShowsActionableWarningEvenWhenTranscriptIsReady() {
        val state =
            libraryRecordPresentation(
                source(SourceAccessStatus.MISSING),
                listOf(job(JobStatus.COMPLETED)),
            )
        assertEquals(LibraryRecordState.SOURCE_UNAVAILABLE, state.state)
        assertTrue(state.detail.contains("текст сохранён"))
    }

    @Test
    fun pausedAndQueuedHaveDistinctStatusLabels() {
        assertEquals(
            LibraryRecordState.PAUSED,
            libraryRecordPresentation(source(), listOf(job(JobStatus.PAUSED))).state,
        )
        assertEquals(
            LibraryRecordState.QUEUED,
            libraryRecordPresentation(source(), listOf(job(JobStatus.PENDING))).state,
        )
    }

    @Test
    fun cancelledAndNewSourcesHaveDifferentActions() {
        assertEquals(
            LibraryRecordState.CANCELLED,
            libraryRecordPresentation(source(), listOf(job(JobStatus.CANCELLED))).state,
        )
        assertEquals(
            LibraryRecordState.NO_TRANSCRIPT,
            libraryRecordPresentation(source(), emptyList()).state,
        )
    }
}
