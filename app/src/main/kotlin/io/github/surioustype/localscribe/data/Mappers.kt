package io.github.surioustype.localscribe.data

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.core.model.VadConfig

internal fun AudioSourceRecord.toEntity() =
    AudioSourceEntity(
        id = source.id,
        uri = source.uri,
        displayName = source.displayName,
        durationMs = source.durationMs,
        mimeType = source.mimeType,
        accessStatus = accessStatus.name,
        hasPersistedPermission = hasPersistedPermission,
        contentFingerprint = contentFingerprint,
    )

internal fun AudioSourceEntity.toModel() =
    AudioSourceRecord(
        source = AudioSource(id, uri, displayName, durationMs, mimeType),
        accessStatus = SourceAccessStatus.valueOf(accessStatus),
        hasPersistedPermission = hasPersistedPermission,
        contentFingerprint = contentFingerprint,
    )

internal fun TranscriptionJob.toEntity(): TranscriptionJobEntity {
    val vad = config.inference.vad
    return TranscriptionJobEntity(
        id = id,
        sourceId = sourceId,
        modelId = config.modelId,
        modelHash = modelHash,
        sourceFingerprint = sourceFingerprint,
        threadCount = config.inference.threadCount,
        language = config.inference.language,
        translateToEnglish = config.inference.translateToEnglish,
        temperature = config.inference.temperature,
        vadModelId = vad?.modelId,
        vadModelHash = vad?.modelHash,
        vadThreshold = vad?.threshold,
        vadMinimumSpeechDurationMs = vad?.minimumSpeechDurationMs,
        vadMinimumSilenceDurationMs = vad?.minimumSilenceDurationMs,
        chunkDurationMs = config.chunkDurationMs,
        overlapMs = config.overlapMs,
        contextMaxCharacters = config.contextMaxCharacters,
        status = status.name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        failureCode = failure?.code?.name,
        failureDiagnostic = failure?.diagnostic,
    )
}

internal fun TranscriptionJobEntity.toModel(): TranscriptionJob {
    val vad =
        if (vadModelId != null && vadModelHash != null) {
            VadConfig(
                modelId = vadModelId,
                modelHash = vadModelHash,
                threshold = vadThreshold ?: 0.5f,
                minimumSpeechDurationMs = vadMinimumSpeechDurationMs ?: 250,
                minimumSilenceDurationMs = vadMinimumSilenceDurationMs ?: 100,
            )
        } else {
            null
        }
    return TranscriptionJob(
        id = id,
        sourceId = sourceId,
        config =
            TranscriptionConfig(
                modelId = modelId,
                inference =
                    InferenceConfig(
                        threadCount,
                        language,
                        translateToEnglish,
                        temperature,
                        vad,
                    ),
                chunkDurationMs = chunkDurationMs,
                overlapMs = overlapMs,
                contextMaxCharacters = contextMaxCharacters,
            ),
        modelHash = modelHash,
        sourceFingerprint = sourceFingerprint,
        status = JobStatus.valueOf(status),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        failure = failureCode.toFailure(failureDiagnostic),
    )
}

internal fun TranscriptionChunk.toEntity() =
    TranscriptionChunkEntity(
        id = id,
        jobId = jobId,
        modelId = modelId,
        startMs = startMs,
        endMs = endMs,
        status = status.name,
        attempt = attempt,
        createdAtEpochMs = createdAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        failureCode = failure?.code?.name,
        failureDiagnostic = failure?.diagnostic,
    )

internal fun TranscriptionChunkEntity.toModel() =
    TranscriptionChunk(
        id = id,
        jobId = jobId,
        modelId = modelId,
        startMs = startMs,
        endMs = endMs,
        status = ChunkStatus.valueOf(status),
        attempt = attempt,
        createdAtEpochMs = createdAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs,
        failure = failureCode.toFailure(failureDiagnostic),
    )

internal fun TranscriptSegment.toEntity() =
    TranscriptSegmentEntity(
        id,
        chunkId,
        absoluteStartMs,
        absoluteEndMs,
        text,
    )

internal fun TranscriptSegmentEntity.toModel() =
    TranscriptSegment(
        id,
        chunkId,
        absoluteStartMs,
        absoluteEndMs,
        text,
    )

internal fun InstalledModel.toEntity() =
    InstalledModelEntity(
        id,
        descriptorId,
        displayName,
        filePath,
        sha256,
        bytes,
        installedAtEpochMs,
        verifiedAtEpochMs,
    )

internal fun InstalledModelEntity.toModel() =
    InstalledModel(
        id,
        descriptorId,
        displayName,
        filePath,
        sha256,
        bytes,
        installedAtEpochMs,
        verifiedAtEpochMs,
    )

internal fun BenchmarkRecord.toEntity(): BenchmarkEntity {
    val vad = config.vad
    return BenchmarkEntity(
        id,
        deviceId,
        modelId,
        modelHash,
        config.threadCount,
        config.language,
        config.translateToEnglish,
        config.temperature,
        vad?.modelId,
        vad?.modelHash,
        vad?.threshold,
        vad?.minimumSpeechDurationMs,
        vad?.minimumSilenceDurationMs,
        audioDurationMs,
        processingDurationMs,
        realTimeFactor,
        realTimeMultiplier,
        thermalStatus,
        approximatePeakMemoryBytes,
        createdAtEpochMs,
        sampleId,
    )
}

internal fun BenchmarkEntity.toModel(): BenchmarkRecord {
    val vad =
        if (vadModelId != null && vadModelHash != null) {
            VadConfig(
                vadModelId,
                vadModelHash,
                vadThreshold ?: 0.5f,
                vadMinimumSpeechDurationMs ?: 250,
                vadMinimumSilenceDurationMs ?: 100,
            )
        } else {
            null
        }
    return BenchmarkRecord(
        id,
        deviceId,
        modelId,
        modelHash,
        InferenceConfig(threadCount, language, translateToEnglish, temperature, vad),
        audioDurationMs,
        processingDurationMs,
        realTimeFactor,
        realTimeMultiplier,
        thermalStatus,
        approximatePeakMemoryBytes,
        createdAtEpochMs,
        sampleId,
    )
}

private fun String?.toFailure(diagnostic: String?): DomainFailure? =
    this?.let { DomainFailure(FailureCode.valueOf(it), diagnostic) }
