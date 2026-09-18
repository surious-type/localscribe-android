package io.github.surioustype.localscribe.core.model

enum class ModelQuality {
    LOW,
    BALANCED,
    HIGH,
}

enum class ModelKind {
    TRANSCRIPTION,
    VAD,
}

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val languages: Set<String>,
    val quality: ModelQuality,
    val kind: ModelKind = ModelKind.TRANSCRIPTION,
)

data class InstalledModel(
    val id: String,
    val descriptorId: String,
    val displayName: String,
    val filePath: String,
    val sha256: String,
    val bytes: Long,
    val installedAtEpochMs: Long,
    val verifiedAtEpochMs: Long,
)

enum class ModelDownloadStatus {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class ModelDownload(
    val modelId: String,
    val status: ModelDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val failure: DomainFailure? = null,
)
