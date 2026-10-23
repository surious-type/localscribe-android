package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptCorrectionsTest {
    private fun segment(id: String, start: Long, end: Long, text: String = "original") =
        TranscriptSegment(id, "chunk", start, end, text)

    @Test
    fun editedTextKeepsTimestampsAndLeavesOriginalUntouched() {
        val original = segment("one", 1_000, 2_000)
        val result =
            applyTranscriptCorrections(
                listOf(original),
                mapOf("one" to TranscriptCorrection("original", "corrected paragraph\nnext line")),
            )

        assertEquals("original", original.text)
        assertEquals("corrected paragraph\nnext line", result.single().text)
        assertEquals(1_000, result.single().absoluteStartMs)
        assertEquals(2_000, result.single().absoluteEndMs)
    }

    @Test
    fun staleCorrectionIsIgnoredWhenSourceChanged() {
        val original = segment("one", 0, 1_000, "new recognition")
        assertEquals(
            listOf(original),
            applyTranscriptCorrections(
                listOf(original),
                mapOf("one" to TranscriptCorrection("old recognition", "edited old recognition")),
            ),
        )
    }

    @Test
    fun activeSegmentUsesRealIntervalsIncludingOverlapsAndGaps() {
        val segments =
            listOf(
                segment("a", 100, 500),
                segment("b", 400, 900),
                segment("c", 1_000, 1_500),
            )
        assertNull(activeTranscriptSegmentIndex(segments, 99))
        assertEquals(0, activeTranscriptSegmentIndex(segments, 200))
        assertEquals(1, activeTranscriptSegmentIndex(segments, 450))
        assertNull(activeTranscriptSegmentIndex(segments, 950))
        assertEquals(2, activeTranscriptSegmentIndex(segments, 1_499))
        assertNull(activeTranscriptSegmentIndex(segments, 1_500))
    }

    @Test
    fun seekClampsBothEnds() {
        assertEquals(0, boundedSeek(4_000, -10_000, 60_000))
        assertEquals(15_000, boundedSeek(5_000, 10_000, 60_000))
        assertEquals(60_000, boundedSeek(59_000, 10_000, 60_000))
    }
}
