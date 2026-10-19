package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.TranscriptionConfig

/**
 * Deterministic cost comparison, NOT a claim about ASR quality or device speed.
 * The same source can be benchmarked at each size to measure those separately.
 */
data class ChunkWindowComparison(
    val windowSeconds: Int,
    val chunkCount: Int,
    val processedAudioMs: Long,
    val extraOverlapMs: Long,
)

object ChunkWindowProfiles {
    val optionsSeconds = listOf(10, 15, 20, 30)
    const val DEFAULT_SECONDS = 20
    const val OVERLAP_MS = 2_000L

    fun compare(sourceDurationMs: Long): List<ChunkWindowComparison> =
        optionsSeconds.map { seconds ->
            val config =
                TranscriptionConfig(
                    modelId = "comparison",
                    inference = InferenceConfig(threadCount = 1),
                    chunkDurationMs = seconds * 1_000L,
                    overlapMs = OVERLAP_MS,
                )
            val chunks =
                ChunkPlanner().plan(
                    "comparison",
                    "comparison",
                    sourceDurationMs,
                    config,
                    0L,
                )
            val processed = chunks.sumOf { it.endMs - it.startMs }
            ChunkWindowComparison(
                windowSeconds = seconds,
                chunkCount = chunks.size,
                processedAudioMs = processed,
                extraOverlapMs = (processed - sourceDurationMs).coerceAtLeast(0L),
            )
        }
}
