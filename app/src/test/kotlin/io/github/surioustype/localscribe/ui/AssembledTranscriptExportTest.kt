package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.domain.ExportManager
import io.github.surioustype.localscribe.core.domain.TranscriptAssembler
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.ExportFormat
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import org.junit.Assert.assertEquals
import org.junit.Test

class AssembledTranscriptExportTest {
    @Test fun overlapIsShownAndExportedOnlyOnce() {
        val chunks = listOf(chunk("a", 0, 10_000), chunk("b", 7_000, 17_000))
        val raw =
            listOf(
                segment("a", "one", 1_000, 2_000),
                segment("a", "boundary", 8_000, 9_000),
                segment("b", "boundary", 8_000, 9_000),
                segment("b", "two", 10_000, 11_000),
            )
        val assembled = TranscriptAssembler().assemble(chunks, raw)
        val text =
            ExportManager()
                .export(
                    AudioSource("source", "content://source", "source.wav", 17_000, "audio/wav"),
                    assembled,
                    ExportFormat.TXT,
                ).content
                .decodeToString()
        assertEquals(listOf("one", "boundary", "two"), assembled.map { it.text })
        assertEquals("one\nboundary\ntwo\n", text)
    }

    private fun chunk(id: String, start: Long, end: Long) =
        TranscriptionChunk(
            id,
            "job",
            "model",
            start,
            end,
            ChunkStatus.COMPLETED,
            1,
            0,
        )

    private fun segment(chunkId: String, text: String, start: Long, end: Long) =
        TranscriptSegment(
            "$chunkId-$text",
            chunkId,
            start,
            end,
            text,
        )
}
