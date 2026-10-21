package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.TranscriptSegment

/** An immutable edit to the displayed transcript; never overwrites Whisper's raw segments. */
data class TranscriptCorrection(
    val originalText: String,
    val editedText: String,
)

/**
 * Edits are scoped to one job and stable source segment IDs.
 * Stale edits are not applied if a resumed/restarted job supplies different source text.
 */
fun applyTranscriptCorrections(
    segments: List<TranscriptSegment>,
    corrections: Map<String, TranscriptCorrection>,
): List<TranscriptSegment> =
    segments.map { segment ->
        val correction = corrections[segment.id]
        if (correction != null && correction.originalText == segment.text) {
            segment.copy(text = correction.editedText)
        } else {
            segment
        }
    }

/** Finds the segment actually covering playback time, including in overlapping intervals. */
fun activeTranscriptSegmentIndex(segments: List<TranscriptSegment>, positionMs: Long): Int? =
    segments.indices
        .filter { index ->
            val segment = segments[index]
            segment.absoluteStartMs <= positionMs && positionMs < segment.absoluteEndMs
        }.maxByOrNull { segments[it].absoluteStartMs }

/** UI controls must not jump outside a source's real duration. */
fun boundedSeek(positionMs: Long, differenceMs: Long, durationMs: Long): Long =
    if (differenceMs < 0) {
        (positionMs + differenceMs).coerceAtLeast(0L)
    } else {
        (positionMs.coerceAtMost(Long.MAX_VALUE - differenceMs) + differenceMs)
            .coerceAtMost(durationMs.coerceAtLeast(0L))
    }
