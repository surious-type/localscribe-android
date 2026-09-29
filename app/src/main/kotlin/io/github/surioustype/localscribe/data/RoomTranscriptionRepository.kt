package io.github.surioustype.localscribe.data

import androidx.room.withTransaction
import io.github.surioustype.localscribe.core.domain.JobTransitions
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.RecoverySummary
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.execution.DurableTranscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PersistenceStateException(
    val failure: DomainFailure,
) : IllegalStateException(failure.diagnostic)

class RoomTranscriptionRepository(
    private val database: LocalScribeDatabase,
) : DurableTranscriptionRepository {
    private val dao = database.transcriptionDao()

    override fun observeJobs(): Flow<List<TranscriptionJob>> =
        dao.observeJobs().map { rows -> rows.map { it.toModel() } }

    override fun observeJob(jobId: String): Flow<TranscriptionJob?> =
        dao.observeJob(jobId).map { it?.toModel() }

    override fun observeChunks(jobId: String): Flow<List<TranscriptionChunk>> =
        dao.observeChunks(jobId).map { rows -> rows.map { it.toModel() } }

    override fun observeSegments(jobId: String): Flow<List<TranscriptSegment>> =
        dao.observeSegments(jobId).map { rows -> rows.map { it.toModel() } }

    override suspend fun getJob(jobId: String): TranscriptionJob? = dao.getJob(jobId)?.toModel()

    override suspend fun createJob(job: TranscriptionJob, chunks: List<TranscriptionChunk>) {
        require(job.status == JobStatus.PENDING) { "New jobs must be pending" }
        require(chunks.isNotEmpty()) { "A job must contain at least one chunk" }
        require(chunks.all { it.jobId == job.id && it.status == ChunkStatus.PENDING }) {
            "Every planned chunk must belong to the pending job"
        }
        database.withTransaction {
            dao.insertJob(job.toEntity())
            dao.insertChunks(chunks.map { it.toEntity() })
        }
    }

    override suspend fun claimNextChunk(
        jobId: String,
        startedAtEpochMs: Long,
    ): TranscriptionChunk? =
        database.withTransaction {
            val job = requireJob(jobId)
            if (job.status != JobStatus.RUNNING.name) return@withTransaction null
            val next = dao.nextEligibleChunk(jobId) ?: return@withTransaction null
            if (dao.claimChunk(next.id, startedAtEpochMs) != 1) return@withTransaction null
            requireNotNull(dao.getChunk(next.id)).toModel()
        }

    override suspend fun completeChunk(
        chunkId: String,
        segments: List<TranscriptSegment>,
        completedAtEpochMs: Long,
    ) {
        database.withTransaction {
            val chunk = requireChunk(chunkId)
            if (chunk.status == ChunkStatus.COMPLETED.name) return@withTransaction
            val job = requireJob(chunk.jobId)
            requireState(job.status == JobStatus.RUNNING.name, "job_not_running")
            requireState(chunk.status == ChunkStatus.PROCESSING.name, "chunk_not_processing")
            requireState(segments.all { it.chunkId == chunkId }, "segment_chunk_mismatch")
            dao.deleteSegments(chunkId)
            if (segments.isNotEmpty()) dao.insertSegments(segments.map { it.toEntity() })
            dao.updateChunkState(
                chunkId,
                ChunkStatus.COMPLETED.name,
                completedAtEpochMs,
                null,
                null,
            )
        }
    }

    override suspend fun failChunk(chunkId: String, failure: DomainFailure, failedAtEpochMs: Long) {
        database.withTransaction {
            val chunk = requireChunk(chunkId)
            requireState(chunk.status == ChunkStatus.PROCESSING.name, "chunk_not_processing")
            dao.updateChunkState(
                chunkId,
                ChunkStatus.FAILED.name,
                failedAtEpochMs,
                failure.code.name,
                failure.diagnostic,
            )
        }
    }

    override suspend fun pauseJob(jobId: String, pausedAtEpochMs: Long) =
        pauseJob(
            jobId,
            pausedAtEpochMs,
            null,
        )

    override suspend fun pauseJob(jobId: String, pausedAtEpochMs: Long, reason: DomainFailure?) {
        database.withTransaction {
            val job = requireJob(jobId)
            if (job.status == JobStatus.PAUSED.name) return@withTransaction
            transitionJob(job, JobStatus.PAUSED, pausedAtEpochMs, reason)
            dao.updateChunkStatuses(
                jobId,
                listOf(ChunkStatus.PENDING.name, ChunkStatus.PROCESSING.name),
                ChunkStatus.PAUSED.name,
            )
        }
    }

    override suspend fun resumeJob(jobId: String, resumedAtEpochMs: Long) {
        database.withTransaction {
            val job = requireJob(jobId)
            transitionJob(job, JobStatus.RUNNING, resumedAtEpochMs, null)
            dao.updateChunkStatuses(
                jobId,
                listOf(ChunkStatus.PAUSED.name),
                ChunkStatus.PENDING.name,
            )
        }
    }

    override suspend fun restartForSourceFingerprint(
        jobId: String,
        sourceFingerprint: String,
        restartedAtEpochMs: Long,
    ) {
        database.withTransaction {
            val job = requireJob(jobId)
            requireState(job.status == JobStatus.RUNNING.name, "job_not_running")
            dao.deleteSegmentsForJob(jobId)
            dao.resetChunksForSourceRestart(jobId)
            dao.updateSourceFingerprint(jobId, sourceFingerprint, restartedAtEpochMs)
        }
    }

    override suspend fun cancelJob(jobId: String, cancelledAtEpochMs: Long) {
        database.withTransaction {
            val job = requireJob(jobId)
            if (job.status in terminalJobStates) return@withTransaction
            transitionJob(job, JobStatus.CANCELLED, cancelledAtEpochMs, null)
            dao.updateChunkStatuses(
                jobId,
                listOf(
                    ChunkStatus.PENDING.name,
                    ChunkStatus.PROCESSING.name,
                    ChunkStatus.PAUSED.name,
                    ChunkStatus.FAILED.name,
                ),
                ChunkStatus.CANCELLED.name,
            )
        }
    }

    override suspend fun failJob(jobId: String, failure: DomainFailure, failedAtEpochMs: Long) {
        database.withTransaction {
            val job = requireJob(jobId)
            if (job.status == JobStatus.FAILED.name) return@withTransaction
            transitionJob(job, JobStatus.FAILED, failedAtEpochMs, failure)
        }
    }

    override suspend fun completeJob(jobId: String, completedAtEpochMs: Long) {
        database.withTransaction {
            val job = requireJob(jobId)
            if (job.status == JobStatus.COMPLETED.name) return@withTransaction
            requireState(dao.unfinishedChunkCount(jobId) == 0, "job_has_unfinished_chunks")
            transitionJob(job, JobStatus.COMPLETED, completedAtEpochMs, null, completedAtEpochMs)
        }
    }

    override suspend fun recoverInterrupted(interruptedAtEpochMs: Long): RecoverySummary =
        database.withTransaction {
            RecoverySummary(
                recoveredChunkCount = dao.recoverProcessingChunks(),
                pausedJobCount = dao.recoverRunningJobs(interruptedAtEpochMs),
            )
        }

    private suspend fun transitionJob(
        entity: TranscriptionJobEntity,
        to: JobStatus,
        updatedAtEpochMs: Long,
        failure: DomainFailure?,
        completedAtEpochMs: Long? = null,
    ) {
        val from = JobStatus.valueOf(entity.status)
        requireState(
            JobTransitions.canTransition(from, to),
            "invalid_job_transition_${from.name}_${to.name}",
        )
        dao.updateJobState(
            entity.id,
            to.name,
            updatedAtEpochMs,
            completedAtEpochMs,
            failure?.code?.name,
            failure?.diagnostic,
        )
    }

    private suspend fun requireJob(jobId: String) =
        dao.getJob(jobId)
            ?: throw PersistenceStateException(DomainFailure(FailureCode.UNKNOWN, "job_missing"))

    private suspend fun requireChunk(chunkId: String) =
        dao.getChunk(chunkId)
            ?: throw PersistenceStateException(DomainFailure(FailureCode.UNKNOWN, "chunk_missing"))

    private fun requireState(valid: Boolean, diagnostic: String) {
        if (!valid) {
            throw PersistenceStateException(
                DomainFailure(FailureCode.INVALID_STATE_TRANSITION, diagnostic),
            )
        }
    }

    private companion object {
        val terminalJobStates =
            setOf(
                JobStatus.CANCELLED.name,
                JobStatus.FAILED.name,
                JobStatus.COMPLETED.name,
            )
    }
}
