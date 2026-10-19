package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAudioProgressTest {
    @Test
    fun `preview advances visible audio without changing checkpoint`() {
        val committed = 20_000L
        val preview = listOf(
            TranscriptSegment("preview", "chunk-2", 21_000L, 29_000L, "sample"),
        )
        val visible = displayedAudioProgressMs(committed, preview, 38_000L)
        assertEquals(29_000L, visible)
        assertEquals(20_000L, committed)
    }

    @Test
    fun `preview is clamped and cannot move visible progress backward`() {
        val preview = listOf(
            TranscriptSegment("preview", "chunk-2", 1_000L, 5_000L, "text"),
        )
        val oversizedPreview = preview.map { it.copy(absoluteEndMs = 99_000L) }
        val retained = displayedAudioProgressMs(20_000L, preview, 38_000L)
        val clamped = displayedAudioProgressMs(20_000L, oversizedPreview, 38_000L)
        assertEquals(20_000L, retained)
        assertEquals(38_000L, clamped)
    }

    @Test
    fun `silence or no new segments keeps the durable progress`() {
        val empty = emptyList<TranscriptSegment>()
        assertEquals(0L, displayedAudioProgressMs(0L, empty, 38_000L))
        assertEquals(20_000L, displayedAudioProgressMs(20_000L, empty, 38_000L))
    }
}
