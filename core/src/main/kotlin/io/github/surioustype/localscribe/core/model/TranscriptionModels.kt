package io.github.surioustype.localscribe.core.model

const val WHISPER_SAMPLE_RATE_HZ: Int = 16_000

enum class JobStatus {
    PENDING,
    RUNNING,
    PAUSED,
    CANCELLED,
    FAILED,
    COMPLETED,
}

enum class ChunkStatus {
    PENDING,
    PROCESSING,
    PAUSED,
    CANCELLED,
    FAILED,
    COMPLETED,
}

data class InferenceConfig(
    val threadCount: Int,
    val language: String? = null,
    val translateToEnglish: Boolean = false,
    val temperature: Float = 0f,
    val vad: VadConfig? = null,
)

data class VadConfig(
    val modelId: String,
    val modelHash: String,
    val threshold: Float = 0.5f,
    val minimumSpeechDurationMs: Long = 250,
    val minimumSilenceDurationMs: Long = 100,
)

data class TranscriptionConfig(
    val modelId: String,
    val inference: InferenceConfig,
    val chunkDurationMs: Long = 90_000,
    val overlapMs: Long = 3_000,
    val contextMaxCharacters: Int = 1_000,
)

data class TranscriptionJob(
    val id: String,
    val sourceId: String,
    val config: TranscriptionConfig,
    val modelHash: String,
    val status: JobStatus,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val failure: DomainFailure? = null,
)

data class TranscriptionChunk(
    val id: String,
    val jobId: String,
    val modelId: String,
    val startMs: Long,
    val endMs: Long,
    val status: ChunkStatus,
    val attempt: Int,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val failure: DomainFailure? = null,
)

/** A raw engine segment with absolute timestamps in the source audio timeline. */
data class TranscriptSegment(
    val id: String,
    val chunkId: String,
    val absoluteStartMs: Long,
    val absoluteEndMs: Long,
    val text: String,
)

/** A segment returned by the engine, relative to the supplied PCM window. */
data class EngineSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TranscriptionResult(
    val segments: List<EngineSegment>,
    val detectedLanguage: String? = null,
)

data class RecoverySummary(
    val recoveredChunkCount: Int,
    val pausedJobCount: Int,
)
