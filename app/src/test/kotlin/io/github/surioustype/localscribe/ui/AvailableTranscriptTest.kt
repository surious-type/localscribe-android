package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailableTranscriptTest {
    private val finished =
        TranscriptionChunk(
            id = "part-1",
            jobId = "paused-job",
            modelId = "base",
            startMs = 0L,
            endMs = 30_000L,
            status = ChunkStatus.COMPLETED,
            attempt = 1,
            createdAtEpochMs = 0L,
        )
    private val saved =
        TranscriptSegment(
            id = "saved",
            chunkId = finished.id,
            absoluteStartMs = 5_000L,
            absoluteEndMs = 8_000L,
            text = "Saved speech",
        )

    @Test
    fun `opening paused transcript before chunks arrive never throws`() {
        assertTrue(assembleAvailableTranscript(emptyList(), listOf(saved)).isEmpty())
        assertEquals(listOf(saved), assembleAvailableTranscript(listOf(finished), listOf(saved)))
    }

    @Test
    fun `unmatched row does not hide existing completed checkpoints`() {
        val missingChunkSegment = saved.copy(id = "later", chunkId = "part-2")
        assertEquals(
            listOf(saved),
            assembleAvailableTranscript(listOf(finished), listOf(saved, missingChunkSegment)),
        )
        // The original Room rows remain unchanged; an arriving chunk makes its text visible.
        assertEquals("later", missingChunkSegment.id)
    }
}
