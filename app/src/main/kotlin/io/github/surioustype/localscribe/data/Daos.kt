package io.github.surioustype.localscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioSourceDao {
    @Query("SELECT * FROM audio_sources ORDER BY displayName COLLATE NOCASE, id")
    fun observe(): Flow<List<AudioSourceEntity>>

    @Query("SELECT * FROM audio_sources WHERE id = :sourceId")
    suspend fun get(sourceId: String): AudioSourceEntity?

    @Query("SELECT COUNT(*) FROM transcription_jobs WHERE sourceId = :sourceId")
    suspend fun jobCount(sourceId: String): Int

    @Query(
        "SELECT COUNT(*) FROM audio_sources WHERE uri = :uri AND hasPersistedPermission = 1 " +
            "AND id != :sourceId",
    )
    suspend fun persistedUriOwnerCount(sourceId: String, uri: String): Int

    @Upsert
    suspend fun upsert(entity: AudioSourceEntity)

    @Query("DELETE FROM audio_sources WHERE id = :sourceId")
    suspend fun delete(sourceId: String): Int
}

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcription_jobs ORDER BY createdAtEpochMs DESC, id")
    fun observeJobs(): Flow<List<TranscriptionJobEntity>>

    @Query("SELECT * FROM transcription_jobs WHERE id = :jobId")
    fun observeJob(jobId: String): Flow<TranscriptionJobEntity?>

    @Query("SELECT * FROM transcription_jobs WHERE id = :jobId")
    suspend fun getJob(jobId: String): TranscriptionJobEntity?

    @Query("SELECT * FROM transcription_chunks WHERE jobId = :jobId ORDER BY startMs, id")
    fun observeChunks(jobId: String): Flow<List<TranscriptionChunkEntity>>

    @Query("SELECT * FROM transcription_chunks WHERE id = :chunkId")
    suspend fun getChunk(chunkId: String): TranscriptionChunkEntity?

    @Query(
        "SELECT * FROM transcription_chunks WHERE jobId = :jobId " +
            "AND status IN ('PENDING', 'FAILED') ORDER BY startMs, id LIMIT 1",
    )
    suspend fun nextEligibleChunk(jobId: String): TranscriptionChunkEntity?

    @Query(
        "SELECT COUNT(*) FROM transcription_chunks WHERE jobId = :jobId AND status != 'COMPLETED'",
    )
    suspend fun unfinishedChunkCount(jobId: String): Int

    @Query(
        "SELECT transcript_segments.* FROM transcript_segments " +
            "INNER JOIN transcription_chunks ON transcription_chunks.id = " +
            "transcript_segments.chunkId " +
            "WHERE transcription_chunks.jobId = :jobId " +
            "ORDER BY transcript_segments.absoluteStartMs, " +
            "transcript_segments.absoluteEndMs, transcript_segments.id",
    )
    fun observeSegments(jobId: String): Flow<List<TranscriptSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(entity: TranscriptionJobEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(entities: List<TranscriptionChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegments(entities: List<TranscriptSegmentEntity>)

    @Query(
        "UPDATE transcription_chunks SET status = 'PROCESSING', attempt = attempt + 1, " +
            "startedAtEpochMs = :startedAtEpochMs, completedAtEpochMs = NULL, " +
            "failureCode = NULL, failureDiagnostic = NULL WHERE id = :chunkId " +
            "AND status IN ('PENDING', 'FAILED')",
    )
    suspend fun claimChunk(chunkId: String, startedAtEpochMs: Long): Int

    @Query("DELETE FROM transcript_segments WHERE chunkId = :chunkId")
    suspend fun deleteSegments(chunkId: String)

    @Query(
        "DELETE FROM transcript_segments WHERE chunkId IN " +
            "(SELECT id FROM transcription_chunks WHERE jobId = :jobId)",
    )
    suspend fun deleteSegmentsForJob(jobId: String)

    @Query(
        "UPDATE transcription_chunks SET status = 'PENDING', attempt = 0, " +
            "startedAtEpochMs = NULL, completedAtEpochMs = NULL, failureCode = NULL, " +
            "failureDiagnostic = NULL WHERE jobId = :jobId",
    )
    suspend fun resetChunksForSourceRestart(jobId: String)

    @Query(
        "UPDATE transcription_jobs SET sourceFingerprint = :sourceFingerprint, " +
            "updatedAtEpochMs = :updatedAtEpochMs, completedAtEpochMs = NULL, " +
            "failureCode = NULL, failureDiagnostic = NULL WHERE id = :jobId",
    )
    suspend fun updateSourceFingerprint(
        jobId: String,
        sourceFingerprint: String,
        updatedAtEpochMs: Long,
    )

    @Query(
        "UPDATE transcription_chunks SET status = :status, " +
            "completedAtEpochMs = :completedAtEpochMs, failureCode = :failureCode, " +
            "failureDiagnostic = :failureDiagnostic WHERE id = :chunkId",
    )
    suspend fun updateChunkState(
        chunkId: String,
        status: String,
        completedAtEpochMs: Long?,
        failureCode: String?,
        failureDiagnostic: String?,
    )

    @Query(
        "UPDATE transcription_jobs SET status = :status, updatedAtEpochMs = :updatedAtEpochMs, " +
            "completedAtEpochMs = :completedAtEpochMs, failureCode = :failureCode, " +
            "failureDiagnostic = :failureDiagnostic WHERE id = :jobId",
    )
    suspend fun updateJobState(
        jobId: String,
        status: String,
        updatedAtEpochMs: Long,
        completedAtEpochMs: Long?,
        failureCode: String?,
        failureDiagnostic: String?,
    )

    @Query(
        "UPDATE transcription_chunks SET status = :toStatus " +
            "WHERE jobId = :jobId AND status IN (:fromStatuses)",
    )
    suspend fun updateChunkStatuses(
        jobId: String,
        fromStatuses: List<String>,
        toStatus: String,
    ): Int

    @Query("UPDATE transcription_chunks SET status = 'PENDING' WHERE status = 'PROCESSING'")
    suspend fun recoverProcessingChunks(): Int

    @Query(
        "UPDATE transcription_jobs SET status = 'PAUSED', updatedAtEpochMs = :atEpochMs, " +
            "failureCode = 'TIMEOUT', failureDiagnostic = 'process_interrupted' " +
            "WHERE status = 'RUNNING'",
    )
    suspend fun recoverRunningJobs(atEpochMs: Long): Int
}

@Dao
interface InstalledModelDao {
    @Query(
        "SELECT * FROM installed_models " +
            "ORDER BY displayName COLLATE NOCASE, descriptorId, installedAtEpochMs DESC, " +
            "verifiedAtEpochMs DESC, sha256 DESC, id DESC",
    )
    fun observe(): Flow<List<InstalledModelEntity>>

    @Query(
        "SELECT * FROM installed_models WHERE descriptorId = :modelId " +
            "ORDER BY installedAtEpochMs DESC, verifiedAtEpochMs DESC, " +
            "sha256 DESC, id DESC LIMIT 1",
    )
    suspend fun getByDescriptorId(modelId: String): InstalledModelEntity?

    @Query(
        "SELECT * FROM installed_models WHERE descriptorId = :modelId AND sha256 = :sha256 LIMIT 1",
    )
    suspend fun getByDescriptorIdAndSha256(modelId: String, sha256: String): InstalledModelEntity?

    @Upsert
    suspend fun upsert(entity: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM installed_models WHERE descriptorId = :modelId")
    suspend fun deleteByDescriptorId(modelId: String)
}

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM model_benchmarks ORDER BY createdAtEpochMs DESC, id")
    fun observe(): Flow<List<BenchmarkEntity>>

    @Query("SELECT * FROM model_benchmarks WHERE id = :id")
    suspend fun get(id: String): BenchmarkEntity?

    @Upsert
    suspend fun upsert(entity: BenchmarkEntity)

    @Query("DELETE FROM model_benchmarks WHERE id = :id")
    suspend fun delete(id: String)
}
