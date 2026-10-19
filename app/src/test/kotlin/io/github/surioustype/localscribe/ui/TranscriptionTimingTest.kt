package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionTimingTest {
    private fun chunk(
        id: String,
        start: Long,
        end: Long,
        status: ChunkStatus,
        started: Long? = null,
        completed: Long? = null,
    ) = TranscriptionChunk(
        id = id,
        jobId = "job",
        modelId = "tiny",
        startMs = start,
        endMs = end,
        status = status,
        attempt = 1,
        createdAtEpochMs = 1_000L,
        startedAtEpochMs = started,
        completedAtEpochMs = completed,
    )

    @Test
    fun `unknown eta before first completed chunk`() {
        val result = transcriptionTiming(
            listOf(chunk("a", 0, 90_000, ChunkStatus.PROCESSING, 2_000)),
            nowEpochMs = 8_000,
        )
        assertEquals(6_000L, result.elapsedMs)
        assertNull(result.remainingMs)
        assertEquals(0L, result.completedAudioMs)
    }

    @Test
    fun `estimates remaining time from completed and active chunks`() {
        val result = transcriptionTiming(
            listOf(
                chunk("a", 0, 90_000, ChunkStatus.COMPLETED, 1_000, 11_000),
                chunk("b", 87_000, 180_000, ChunkStatus.PROCESSING, 12_000),
            ),
            nowEpochMs = 17_000,
        )
        assertEquals(15_000L, result.elapsedMs)
        assertEquals(90_000L, result.completedAudioMs)
        assertEquals(180_000L, result.totalAudioMs)
        assertEquals(15_000L, result.remainingMs)
    }

    @Test
    fun `paused time is not counted`() {
        val result = transcriptionTiming(
            listOf(chunk("a", 0, 90_000, ChunkStatus.PAUSED, 1_000)),
            nowEpochMs = 100_000,
        )
        assertEquals(0L, result.elapsedMs)
        assertNull(result.remainingMs)
    }

    @Test
    fun `empty job has no fabricated eta`() {
        val result = transcriptionTiming(emptyList(), nowEpochMs = 20_000)
        assertNull(result.remainingMs)
        assertEquals(0L, result.totalAudioMs)
    }
}
