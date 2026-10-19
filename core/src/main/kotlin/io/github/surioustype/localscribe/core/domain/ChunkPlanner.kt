package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionConfig

class ChunkPlanner {
    fun plan(
        jobId: String,
        modelId: String,
        sourceDurationMs: Long,
        config: TranscriptionConfig,
        createdAtEpochMs: Long,
    ): List<TranscriptionChunk> {
        require(sourceDurationMs >= 0) { "Source duration must not be negative" }
        require(config.chunkDurationMs > 0) { "Chunk duration must be positive" }
        require(config.overlapMs >= 0) { "Overlap must not be negative" }
        require(
            config.overlapMs < config.chunkDurationMs,
        ) { "Overlap must be shorter than a chunk" }
        if (sourceDurationMs == 0L) return emptyList()

        val chunks = mutableListOf<TranscriptionChunk>()
        var startMs = 0L
        while (startMs < sourceDurationMs) {
            val remainingMs = sourceDurationMs - startMs
            val endMs =
                if (remainingMs <= config.chunkDurationMs) {
                    sourceDurationMs
                } else {
                    val regularEnd = startMs + config.chunkDurationMs
                    val nextStart = regularEnd - config.overlapMs
                    // Avoid a terminal window containing almost only repeated overlap.
                    // At most 2.5 seconds are appended to the preceding window.
                    if (sourceDurationMs - regularEnd > 0L &&
                        sourceDurationMs - regularEnd <= 2_500L
                    ) {
                        sourceDurationMs
                    } else if (sourceDurationMs - nextStart <= config.overlapMs) {
                        sourceDurationMs
                    } else {
                        regularEnd
                    }
                }
            chunks +=
                TranscriptionChunk(
                    id = "$jobId:${chunks.size}",
                    jobId = jobId,
                    modelId = modelId,
                    startMs = startMs,
                    endMs = endMs,
                    status = ChunkStatus.PENDING,
                    attempt = 0,
                    createdAtEpochMs = createdAtEpochMs,
                )
            if (endMs == sourceDurationMs) break
            startMs = endMs - config.overlapMs
        }
        return chunks
    }
}
