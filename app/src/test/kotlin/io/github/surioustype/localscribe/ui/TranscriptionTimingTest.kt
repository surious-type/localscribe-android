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
    ) =
        TranscriptionChunk(
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
        val result =
            transcriptionTiming(
                listOf(chunk("a", 0, 90_000, ChunkStatus.PROCESSING, 2_000)),
                nowEpochMs = 8_000,
            )
        assertEquals(6_000L, result.elapsedMs)
        assertNull(result.remainingMs)
        assertEquals(0L, result.completedAudioMs)
    }

    @Test
    fun `estimates remaining time from completed and active chunks`() {
        val result =
            transcriptionTiming(
                listOf(
                    chunk("a", 0, 90_000, ChunkStatus.COMPLETED, 1_000, 11_000),
                    chunk("b", 87_000, 180_000, ChunkStatus.PROCESSING, 12_000),
                ),
                nowEpochMs = 17_000,
            )
        assertEquals(15_000L, result.elapsedMs)
        assertEquals(90_000L, result.completedAudioMs)
        assertEquals(180_000L, result.totalAudioMs)
        assertEquals(5_000L, result.remainingMs)
    }

    @Test
    fun `eta decreases while current chunk processes rather than increasing`() {
        val chunks =
            listOf(
                chunk("a", 0, 20_000, ChunkStatus.COMPLETED, 1_000, 11_000),
                chunk("b", 18_000, 38_000, ChunkStatus.PROCESSING, 12_000),
            )
        val earlier = transcriptionTiming(chunks, nowEpochMs = 13_000)
        val later = transcriptionTiming(chunks, nowEpochMs = 17_000)
        assertEquals(20_000L, earlier.completedAudioMs)
        assertEquals(38_000L, earlier.totalAudioMs)
        assertEquals(8_000L, earlier.remainingMs)
        assertEquals(4_000L, later.remainingMs)
        assertEquals(4_000L, earlier.remainingMs!! - later.remainingMs!!)
    }

    @Test
    fun `overdue prediction becomes unknown rather than claiming completion`() {
        val chunks =
            listOf(
                chunk("a", 0, 20_000, ChunkStatus.COMPLETED, 1_000, 11_000),
                chunk("b", 18_000, 38_000, ChunkStatus.PROCESSING, 12_000),
            )
        val result = transcriptionTiming(chunks, nowEpochMs = 60_000)
        assertNull(result.remainingMs)
        assertEquals(20_000L, result.completedAudioMs)
    }

    @Test
    fun `overlap does not bias completed audio duration`() {
        val result = transcriptionTiming(
            listOf(
                chunk("a", 0, 20_000, ChunkStatus.COMPLETED, 1_000, 11_000),
                chunk("b", 18_000, 38_000, ChunkStatus.COMPLETED, 12_000, 22_000),
                chunk("c", 36_000, 56_000, ChunkStatus.PROCESSING, 23_000),
            ),
            nowEpochMs = 24_000,
        )
        assertEquals(38_000L, result.completedAudioMs)
        assertEquals(56_000L, result.totalAudioMs)
        assertEquals(8_473L, result.remainingMs)
    }

    @Test
    fun `completed transcription has zero remaining time`() {
        val result = transcriptionTiming(
            listOf(chunk("a", 0, 10_000, ChunkStatus.COMPLETED, 1_000, 6_000)),
            nowEpochMs = 100_000,
        )
        assertEquals(0L, result.remainingMs)
        assertEquals(5_000L, result.elapsedMs)
    }

    @Test
    fun `paused time is not counted`() {
        val result =
            transcriptionTiming(
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
