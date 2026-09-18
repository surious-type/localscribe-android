package io.github.surioustype.localscribe.core.ports

import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.RecoverySummary
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.core.model.TranscriptionResult
import kotlinx.coroutines.flow.Flow

fun interface CancellationSignal {
    fun isCancellationRequested(): Boolean
}

interface TranscriptionEngine {
    /** Loads one verified model. A loaded engine owns exactly one native context. */
    suspend fun loadModel(model: InstalledModel, vadModel: InstalledModel? = null)

    /**
     * Transcribes one mono 16 kHz PCM window. Implementations must observe coroutine cancellation
     * and [cancellationSignal] from native progress callbacks.
     */
    suspend fun transcribe(
        pcm: FloatArray,
        config: InferenceConfig,
        prompt: String?,
        cancellationSignal: CancellationSignal,
    ): TranscriptionResult

    /** Releases the loaded native context. Calling this with no loaded model is safe. */
    suspend fun unloadModel()
}

interface TranscriptionRepository {
    fun observeJobs(): Flow<List<TranscriptionJob>>

    fun observeJob(jobId: String): Flow<TranscriptionJob?>

    fun observeChunks(jobId: String): Flow<List<TranscriptionChunk>>

    fun observeSegments(jobId: String): Flow<List<TranscriptSegment>>

    suspend fun getJob(jobId: String): TranscriptionJob?

    /** Persists the job and complete chunk plan atomically. */
    suspend fun createJob(job: TranscriptionJob, chunks: List<TranscriptionChunk>)

    /** Atomically claims the next pending chunk and increments its attempt count. */
    suspend fun claimNextChunk(jobId: String, startedAtEpochMs: Long): TranscriptionChunk?

    /** Atomically inserts raw segments and marks the chunk completed; retries are idempotent. */
    suspend fun completeChunk(
        chunkId: String,
        segments: List<TranscriptSegment>,
        completedAtEpochMs: Long,
    )

    suspend fun failChunk(chunkId: String, failure: DomainFailure, failedAtEpochMs: Long)

    suspend fun pauseJob(jobId: String, pausedAtEpochMs: Long)

    suspend fun resumeJob(jobId: String, resumedAtEpochMs: Long)

    suspend fun cancelJob(jobId: String, cancelledAtEpochMs: Long)

    suspend fun failJob(jobId: String, failure: DomainFailure, failedAtEpochMs: Long)

    suspend fun completeJob(jobId: String, completedAtEpochMs: Long)

    /** Recovers PROCESSING chunks to PENDING and RUNNING jobs to PAUSED atomically. */
    suspend fun recoverInterrupted(interruptedAtEpochMs: Long): RecoverySummary
}
