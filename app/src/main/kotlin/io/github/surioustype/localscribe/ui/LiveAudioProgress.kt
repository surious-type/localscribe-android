package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.TranscriptSegment

/**
 * Native preview is transient: advancing the display does not advance durable checkpoints.
 * Never decrease below the saved point or exceed the total recording duration.
 */
fun displayedAudioProgressMs(
    committedAudioMs: Long,
    previewSegments: List<TranscriptSegment>,
    totalAudioMs: Long,
): Long =
    maxOf(
        committedAudioMs,
        previewSegments.maxOfOrNull { it.absoluteEndMs } ?: 0L,
    ).coerceIn(0L, totalAudioMs.coerceAtLeast(0L))
