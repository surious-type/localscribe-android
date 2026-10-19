package io.github.surioustype.localscribe.execution

import io.github.surioustype.localscribe.core.model.EngineSegment
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Ephemeral live preview. These segments are never persisted or exported.
 * On interruption or process death the UI continues from committed Room segments.
 */
class LiveTranscriptStore {
    private val state = MutableStateFlow<Map<String, List<TranscriptSegment>>>(emptyMap())

    fun observe(jobId: String): Flow<List<TranscriptSegment>> =
        state.map { it[jobId].orEmpty() }.distinctUntilChanged()

    fun clear(jobId: String) {
        state.update { it - jobId }
    }

    /** Called from a whisper.cpp native worker thread; MutableStateFlow.update is atomic. */
    fun publish(
        jobId: String,
        chunk: TranscriptionChunk,
        segment: EngineSegment,
    ) {
        if (segment.text.isBlank()) return
        val duration = chunk.endMs - chunk.startMs
        val start = segment.startMs.coerceIn(0L, duration)
        val end = segment.endMs.coerceIn(0L, duration)
        if (end <= start) return
        state.update { current ->
            val existing = current[jobId].orEmpty()
            val nextSegment =
                TranscriptSegment(
                    id = "preview-${chunk.id}:${existing.size}",
                    chunkId = chunk.id,
                    absoluteStartMs = chunk.startMs + start,
                    absoluteEndMs = chunk.startMs + end,
                    text = segment.text.trim(),
                )
            val nextPreview = (existing + nextSegment).takeLast(MAX_PREVIEW_SEGMENTS)
            current + (jobId to nextPreview)
        }
    }

    private companion object {
        const val MAX_PREVIEW_SEGMENTS = 64
    }
}
