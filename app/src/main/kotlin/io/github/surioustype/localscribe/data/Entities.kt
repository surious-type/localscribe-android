package io.github.surioustype.localscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_sources",
    indices = [Index(value = ["accessStatus"])],
)
data class AudioSourceEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val mimeType: String,
    val accessStatus: String,
    val hasPersistedPermission: Boolean,
    val contentFingerprint: String?,
)

@Entity(
    tableName = "transcription_jobs",
    foreignKeys = [
        ForeignKey(
            entity = AudioSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("sourceId"), Index("status"), Index("modelHash")],
)
data class TranscriptionJobEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val modelId: String,
    val modelHash: String,
    val sourceFingerprint: String?,
    val threadCount: Int,
    val language: String?,
    val translateToEnglish: Boolean,
    val temperature: Float,
    val vadModelId: String?,
    val vadModelHash: String?,
    val vadThreshold: Float?,
    val vadMinimumSpeechDurationMs: Long?,
    val vadMinimumSilenceDurationMs: Long?,
    val chunkDurationMs: Long,
    val overlapMs: Long,
    val contextMaxCharacters: Int,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val failureCode: String?,
    val failureDiagnostic: String?,
)

@Entity(
    tableName = "transcription_chunks",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("jobId"),
        Index(value = ["jobId", "status", "startMs"]),
        Index(value = ["jobId", "startMs", "endMs"], unique = true),
    ],
)
data class TranscriptionChunkEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val modelId: String,
    val startMs: Long,
    val endMs: Long,
    val status: String,
    val attempt: Int,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long?,
    val completedAtEpochMs: Long?,
    val failureCode: String?,
    val failureDiagnostic: String?,
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = TranscriptionChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chunkId"), Index(value = ["chunkId", "absoluteStartMs", "absoluteEndMs"])],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val chunkId: String,
    val absoluteStartMs: Long,
    val absoluteEndMs: Long,
    val text: String,
)

@Entity(
    tableName = "installed_models",
    indices = [
        Index(value = ["descriptorId", "sha256"], unique = true),
        Index(value = ["sha256"]),
    ],
)
data class InstalledModelEntity(
    @PrimaryKey val id: String,
    val descriptorId: String,
    val displayName: String,
    val filePath: String,
    val sha256: String,
    val bytes: Long,
    val installedAtEpochMs: Long,
    val verifiedAtEpochMs: Long,
)

@Entity(
    tableName = "model_benchmarks",
    indices = [
        Index(value = ["deviceId", "modelId", "modelHash"]),
        Index(value = ["sampleId"]),
    ],
)
data class BenchmarkEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val modelId: String,
    val modelHash: String,
    val threadCount: Int,
    val language: String?,
    val translateToEnglish: Boolean,
    val temperature: Float,
    val vadModelId: String?,
    val vadModelHash: String?,
    val vadThreshold: Float?,
    val vadMinimumSpeechDurationMs: Long?,
    val vadMinimumSilenceDurationMs: Long?,
    val audioDurationMs: Long,
    val processingDurationMs: Long,
    val realTimeFactor: Double,
    val realTimeMultiplier: Double,
    val thermalStatus: Int?,
    val approximatePeakMemoryBytes: Long?,
    val createdAtEpochMs: Long,
    val sampleId: String,
)
