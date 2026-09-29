package io.github.surioustype.localscribe.core.model

/** Metadata for an audio item. The URI remains opaque to the core module. */
data class AudioSource(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val mimeType: String,
)

enum class SourceAccessStatus {
    AVAILABLE,
    PERMISSION_REQUIRED,
    MISSING,
}

data class AudioSourceRecord(
    val source: AudioSource,
    val accessStatus: SourceAccessStatus,
    val hasPersistedPermission: Boolean,
    /** SHA-256 of the selected audio bytes, used to verify a relinked source. */
    val contentFingerprint: String? = null,
)
