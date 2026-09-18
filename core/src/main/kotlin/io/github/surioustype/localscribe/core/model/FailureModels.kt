package io.github.surioustype.localscribe.core.model

enum class FailureCode {
    SOURCE_PERMISSION_REQUIRED,
    SOURCE_MISSING,
    UNSUPPORTED_CODEC,
    STORAGE_FULL,
    MODEL_CHECKSUM_MISMATCH,
    MODEL_CORRUPTED,
    INSUFFICIENT_MEMORY,
    NATIVE_FAILURE,
    TIMEOUT,
    THERMAL_CRITICAL,
    DOWNLOAD_INTERRUPTED,
    NETWORK_UNAVAILABLE,
    UPDATE_VERIFICATION_FAILED,
    INVALID_STATE_TRANSITION,
    UNKNOWN,
}

/** A stable code for persistence/UI mapping plus an optional non-sensitive diagnostic. */
data class DomainFailure(
    val code: FailureCode,
    val diagnostic: String? = null,
)
