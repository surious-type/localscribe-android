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
 * Predict from fully committed chunks only. Time spent on a still-running chunk
 * advances the countdown, but never makes the estimated total slower each second.
 * Until a first chunk is saved, ETA is genuinely unknown.
 */
fun transcriptionTiming(
    chunks: List<TranscriptionChunk>,
    nowEpochMs: Long,
): TranscriptionTiming {
    val total = chunks.maxOfOrNull { it.endMs }?.coerceAtLeast(0L) ?: 0L
    val committed = chunks.filter { it.status == ChunkStatus.COMPLETED }.sortedBy { it.startMs }
    val completed = committed.maxOfOrNull { it.endMs }?.coerceIn(0L, total) ?: 0L
    val elapsed =
        chunks.sumOf { chunk ->
            val started = chunk.startedAtEpochMs ?: return@sumOf 0L
            when (chunk.status) {
                ChunkStatus.COMPLETED ->
                    ((chunk.completedAtEpochMs ?: started) - started).coerceAtLeast(0L)
                ChunkStatus.PROCESSING -> (nowEpochMs - started).coerceAtLeast(0L)
                else -> 0L
            }
        }
    if (total == 0L || completed >= total) {
        return TranscriptionTiming(elapsed, if (total == 0L) null else 0L, completed, total)
    }

    // Overlap must not count as additional audio when measuring throughput.
    var previousEnd = 0L
    val allMeasurements = committed.mapNotNull { chunk ->
        val uniqueMs = (chunk.endMs - maxOf(previousEnd, chunk.startMs)).coerceAtLeast(0L)
        previousEnd = maxOf(previousEnd, chunk.endMs)
        val started = chunk.startedAtEpochMs
        val ended = chunk.completedAtEpochMs
        if (uniqueMs == 0L || started == null || ended == null || ended <= started) {
            null
        } else {
            uniqueMs to (ended - started)
        }
    }
    val measurements = allMeasurements.takeLast(3)
    if (measurements.isEmpty()) {
        return TranscriptionTiming(elapsed, null, completed, total)
    }

    // A small moving window reacts to thermal throttling only after real evidence arrives.
    val measuredAudio = measurements.sumOf { it.first }
    val measuredCompute = measurements.sumOf { it.second }
    val estimateMs =
        (measuredCompute.toDouble() * (total - completed) / measuredAudio)
            .coerceIn(0.0, Long.MAX_VALUE.toDouble())
            .toLong()
    val activeElapsed =
        chunks
            .filter { it.status == ChunkStatus.PROCESSING }
            .sumOf { (nowEpochMs - (it.startedAtEpochMs ?: nowEpochMs)).coerceAtLeast(0L) }
    // When a chunk runs slower than the samples, stop claiming a false '0 seconds left'.
    val remaining = if (activeElapsed > estimateMs) null else estimateMs - activeElapsed
    return TranscriptionTiming(elapsed, remaining, completed, total)
}
