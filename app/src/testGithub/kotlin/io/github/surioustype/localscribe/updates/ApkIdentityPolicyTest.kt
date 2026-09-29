package io.github.surioustype.localscribe.updates

import org.junit.Assert.assertThrows
import org.junit.Test

class ApkIdentityPolicyTest {
    @Test
    fun `accepts authentic newer package with current signer`() {
        ApkIdentityPolicy.requireValidUpdate(
            installed = identity(versionCode = 10, currentSigners = setOf("signer")),
            candidate = identity(versionCode = 11, currentSigners = setOf("signer")),
            expectedVersionName = "1.1.0",
        )
    }

    @Test
    fun `accepts single signer rotation with an explicit continuing lineage`() {
        ApkIdentityPolicy.requireValidUpdate(
            installed = identity(versionCode = 10, currentSigners = setOf("old")),
            candidate =
                identity(
                    versionCode = 11,
                    currentSigners = setOf("new"),
                    signerLineage = setOf("old", "new"),
                ),
            expectedVersionName = "1.1.0",
        )
    }

    @Test
    fun `rejects package version name and non increasing version code mismatches`() {
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10),
                identity(packageName = "other", versionCode = 11),
                "1.1.0",
            )
        }
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10),
                identity(versionName = "1.2.0", versionCode = 11),
                "1.1.0",
            )
        }
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10),
                identity(versionCode = 10),
                "1.1.0",
            )
        }
    }

    @Test
    fun `rejects unavailable signatures and unrelated signer`() {
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10, currentSigners = emptySet()),
                identity(versionCode = 11),
                "1.1.0",
            )
        }
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10),
                identity(versionCode = 11, currentSigners = emptySet()),
                "1.1.0",
            )
        }
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10, currentSigners = setOf("old")),
                identity(versionCode = 11, currentSigners = setOf("unrelated")),
                "1.1.0",
            )
        }
    }

    @Test
    fun `rejects ambiguous multiple signer rotation`() {
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10, currentSigners = setOf("old-a", "old-b")),
                identity(
                    versionCode = 11,
                    currentSigners = setOf("new"),
                    signerLineage = setOf("old-a", "old-b", "new"),
                ),
                "1.1.0",
            )
        }
        assertThrows(SecurityException::class.java) {
            ApkIdentityPolicy.requireValidUpdate(
                identity(versionCode = 10, currentSigners = setOf("old")),
                identity(
                    versionCode = 11,
                    currentSigners = setOf("new-a", "new-b"),
                    signerLineage = setOf("old", "new-a", "new-b"),
                ),
                "1.1.0",
            )
        }
    }

    private fun identity(
        packageName: String = "io.github.surioustype.localscribe",
        versionName: String = "1.1.0",
        versionCode: Long,
        currentSigners: Set<String> = setOf("signer"),
        signerLineage: Set<String>? = null,
    ) = ApkIdentity(packageName, versionName, versionCode, currentSigners, signerLineage)
}
