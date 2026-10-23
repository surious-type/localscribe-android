package io.github.surioustype.localscribe.audio

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedSourceIdentityTest {
    private fun source(fingerprint: String?) =
        AudioSourceRecord(
            source = AudioSource("audio", "content://audio", "Lecture", 10_000, "audio/wav"),
            accessStatus = SourceAccessStatus.AVAILABLE,
            hasPersistedPermission = true,
            contentFingerprint = fingerprint,
        )

    @Test
    fun changedExternalAudioIsDetectedWithoutDestroyingOriginalRecoveryIdentity() {
        val pinned = source("original")
        val checked = verifyWithoutReplacingPinnedFingerprint(pinned, "different")

        assertEquals("different", checked.observedRecord.contentFingerprint)
        assertFalse(checked.shouldPersist)
        assertEquals("original", pinned.contentFingerprint)
    }

    @Test
    fun missingLegacyFingerprintCanBePinnedOnFirstValidVerification() {
        val legacy = source(null)
        val checked = verifyWithoutReplacingPinnedFingerprint(legacy, "verified")

        assertTrue(checked.shouldPersist)
        assertEquals("verified", checked.observedRecord.contentFingerprint)
        assertEquals(null, legacy.contentFingerprint)
    }
}
