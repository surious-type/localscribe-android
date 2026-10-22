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
 * Only actual native Whisper callbacks become previews; previews are never written to Room.
 * Native callbacks can arrive on worker threads while pause/cancel clears the current chunk.
 */
class LiveTranscriptStore {
    private val lock = Any()
    private val state = MutableStateFlow<Map<String, List<TranscriptSegment>>>(emptyMap())
    private val activeChunkIds = mutableMapOf<String, String>()
    private val ordinals = mutableMapOf<String, Int>()

    fun observe(jobId: String): Flow<List<TranscriptSegment>> =
        state.map { it[jobId].orEmpty() }.distinctUntilChanged()

    fun beginChunk(jobId: String, chunkId: String) {
        synchronized(lock) {
            activeChunkIds[jobId] = chunkId
            ordinals[jobId] = 0
            state.update { it - jobId }
        }
    }

    fun clear(jobId: String) {
        synchronized(lock) {
            activeChunkIds.remove(jobId)
            ordinals.remove(jobId)
            state.update { it - jobId }
        }
    }

    /** Ignore callbacks from old chunks and duplicate segment notifications. */
    fun publish(
        jobId: String,
        chunk: TranscriptionChunk,
        segment: EngineSegment,
    ) {
        if (segment.text.isBlank()) return
        val duration = (chunk.endMs - chunk.startMs).coerceAtLeast(0L)
        val start = segment.startMs.coerceIn(0L, duration)
        val end = segment.endMs.coerceIn(0L, duration)
        if (end <= start) return
        synchronized(lock) {
            if (activeChunkIds[jobId] != chunk.id) return
            val existing = state.value[jobId].orEmpty()
            val text = segment.text.trim()
            if (
                existing.lastOrNull()?.let {
                    it.absoluteStartMs == chunk.startMs + start &&
                        it.absoluteEndMs == chunk.startMs + end &&
                        it.text == text
                } == true
            ) {
                return
            }
            val ordinal = ordinals[jobId] ?: return
            ordinals[jobId] = ordinal + 1
            val next =
                TranscriptSegment(
                    id = "preview-${chunk.id}:$ordinal",
                    chunkId = chunk.id,
                    absoluteStartMs = chunk.startMs + start,
                    absoluteEndMs = chunk.startMs + end,
                    text = text,
                )
            state.update { current ->
                current + (jobId to (existing + next).takeLast(MAX_PREVIEW_SEGMENTS))
            }
        }
    }

    private companion object {
        const val MAX_PREVIEW_SEGMENTS = 64
    }
}
