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
        assertEquals(29_000L, displayedAudioProgressMs(committed, preview, 38_000L))
        assertEquals(20_000L, committed)
    }

    @Test
    fun `preview is clamped and cannot move visible progress backward`() {
        val preview = listOf(
            TranscriptSegment("preview", "chunk-2", 1_000L, 5_000L, "text"),
        )
        assertEquals(20_000L, displayedAudioProgressMs(20_000L, preview, 38_000L))
        assertEquals(38_000L, displayedAudioProgressMs(20_000L, preview.map {
            it.copy(absoluteEndMs = 99_000L)
        }, 38_000L))
    }

    @Test
    fun `silence or no new segments keeps the durable progress`() {
        assertEquals(0L, displayedAudioProgressMs(0L, emptyList(), 38_000L))
        assertEquals(20_000L, displayedAudioProgressMs(20_000L, emptyList(), 38_000L))
    }
}
