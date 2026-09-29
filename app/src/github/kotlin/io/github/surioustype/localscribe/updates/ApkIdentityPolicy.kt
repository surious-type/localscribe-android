package io.github.surioustype.localscribe.updates

data class ApkIdentity(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val currentSigners: Set<String>,
    val signerLineage: Set<String>?,
)

object ApkIdentityPolicy {
    fun requireValidUpdate(
        installed: ApkIdentity,
        candidate: ApkIdentity,
        expectedVersionName: String,
    ) {
        requireSecurity(candidate.packageName == installed.packageName, "APK package name mismatch")
        requireSecurity(candidate.versionName == expectedVersionName, "APK versionName mismatch")
        requireSecurity(
            candidate.versionCode > installed.versionCode,
            "APK versionCode is not newer",
        )
        requireSecurity(
            installed.currentSigners.isNotEmpty() && candidate.currentSigners.isNotEmpty(),
            "APK signer information is unavailable",
        )
        if (installed.currentSigners == candidate.currentSigners) return

        requireSecurity(
            installed.currentSigners.size == 1 && candidate.currentSigners.size == 1,
            "APK signer rotation is ambiguous",
        )
        val lineage = candidate.signerLineage
        requireSecurity(
            lineage != null &&
                lineage.containsAll(installed.currentSigners) &&
                lineage.containsAll(candidate.currentSigners),
            "APK signing lineage does not continue the installed signer",
        )
    }

    private fun requireSecurity(condition: Boolean, message: String) {
        if (!condition) throw SecurityException(message)
    }
}
