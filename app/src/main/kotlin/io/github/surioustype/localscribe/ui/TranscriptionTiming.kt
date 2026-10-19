package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.TranscriptionChunk

data class TranscriptionTiming(
    val elapsedMs: Long,
    val remainingMs: Long?,
    val completedAudioMs: Long,
    val totalAudioMs: Long,
)

/**
 * Uses actual chunk execution times, not the job's last-modified timestamp.
 * Paused time is not included; ETA stays unknown until a full chunk is committed.
 */
fun transcriptionTiming(
    chunks: List<TranscriptionChunk>,
    nowEpochMs: Long,
): TranscriptionTiming {
    val total = chunks.maxOfOrNull { it.endMs }?.coerceAtLeast(0L) ?: 0L
    val completed =
        chunks
            .filter { it.status == ChunkStatus.COMPLETED }
            .maxOfOrNull { it.endMs }
            ?.coerceIn(0L, total) ?: 0L
    val elapsed =
        chunks.sumOf { chunk ->
            val start = chunk.startedAtEpochMs ?: return@sumOf 0L
            when (chunk.status) {
                ChunkStatus.COMPLETED ->
                    (chunk.completedAtEpochMs ?: start).minus(start).coerceAtLeast(0L)
                ChunkStatus.PROCESSING -> (nowEpochMs - start).coerceAtLeast(0L)
                else -> 0L
            }
        }
    val estimate =
        if (completed > 0L && completed < total && elapsed > 0L) {
            (elapsed.toDouble() * (total - completed) / completed)
                .coerceAtMost(Long.MAX_VALUE.toDouble())
                .toLong()
        } else {
            null
        }
    return TranscriptionTiming(elapsed, estimate, completed, total)
}
