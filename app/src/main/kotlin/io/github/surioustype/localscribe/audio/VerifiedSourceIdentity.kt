package io.github.surioustype.localscribe.audio

import io.github.surioustype.localscribe.core.model.AudioSourceRecord

/** Keep the previously pinned fingerprint for relinking, while detecting current-byte changes. */
internal data class VerifiedSourceIdentity(
    val observedRecord: AudioSourceRecord,
    val shouldPersist: Boolean,
)

internal fun verifyWithoutReplacingPinnedFingerprint(
    saved: AudioSourceRecord,
    actualFingerprint: String,
): VerifiedSourceIdentity =
    VerifiedSourceIdentity(
        observedRecord = saved.copy(contentFingerprint = actualFingerprint),
        shouldPersist = saved.contentFingerprint == null,
    )
