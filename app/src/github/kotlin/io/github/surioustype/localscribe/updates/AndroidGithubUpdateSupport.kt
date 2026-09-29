package io.github.surioustype.localscribe.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.surioustype.localscribe.core.model.AppRelease
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.ReleaseAsset
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateState
import java.io.File
import java.security.MessageDigest

class SharedPreferencesUpdateCheckStore(context: Context) : UpdateCheckStore {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    override fun cachedState(): UpdateState? {
        val availability =
            preferences.getString(KEY_AVAILABILITY, null)?.let {
                runCatching { UpdateAvailability.valueOf(it) }.getOrNull()
            } ?: return null
        val checkedAt =
            preferences.getLong(KEY_LAST_CHECK, Long.MIN_VALUE).takeUnless {
                it ==
                    Long.MIN_VALUE
            }
        val release =
            if (availability ==
                UpdateAvailability.AVAILABLE
            ) {
                readRelease() ?: return null
            } else {
                null
            }
        val failure =
            preferences.getString(KEY_FAILURE_CODE, null)?.let { code ->
                runCatching { FailureCode.valueOf(code) }.getOrNull()?.let {
                    DomainFailure(it, preferences.getString(KEY_FAILURE_DIAGNOSTIC, null))
                }
            }
        return UpdateState(availability, release, checkedAt, failure)
    }

    override fun save(state: UpdateState) {
        preferences
            .edit()
            .clear()
            .apply {
                putString(KEY_AVAILABILITY, state.availability.name)
                state.lastCheckedAtEpochMs?.let { putLong(KEY_LAST_CHECK, it) }
                state.failure?.let {
                    putString(KEY_FAILURE_CODE, it.code.name)
                    putString(KEY_FAILURE_DIAGNOSTIC, it.diagnostic)
                }
                state.release?.let { release ->
                    putString(KEY_RELEASE_VERSION, release.version.value)
                    putString(KEY_RELEASE_NOTES, release.releaseNotes)
                    putLong(KEY_RELEASE_PUBLISHED, release.publishedAtEpochMs)
                    putString(KEY_APK_NAME, release.apk.fileName)
                    putString(KEY_APK_URL, release.apk.downloadUrl)
                    putLong(KEY_APK_BYTES, release.apk.bytes)
                    putString(KEY_APK_SHA, release.apk.sha256)
                }
            }.apply()
    }

    private fun readRelease(): AppRelease? =
        runCatching {
            AppRelease(
                version =
                    AppVersion(
                        requireNotNull(preferences.getString(KEY_RELEASE_VERSION, null)),
                    ),
                releaseNotes = requireNotNull(preferences.getString(KEY_RELEASE_NOTES, null)),
                publishedAtEpochMs =
                    preferences.getLong(KEY_RELEASE_PUBLISHED, Long.MIN_VALUE).also {
                        require(
                            it != Long.MIN_VALUE,
                        )
                    },
                apk =
                    ReleaseAsset(
                        fileName = requireNotNull(preferences.getString(KEY_APK_NAME, null)),
                        downloadUrl = requireNotNull(preferences.getString(KEY_APK_URL, null)),
                        bytes = preferences.getLong(KEY_APK_BYTES, -1L).also { require(it > 0) },
                        sha256 = requireNotNull(preferences.getString(KEY_APK_SHA, null)),
                    ),
            )
        }.getOrNull()

    private companion object {
        const val KEY_AVAILABILITY = "availability"
        const val KEY_LAST_CHECK = "last_check_epoch_ms"
        const val KEY_FAILURE_CODE = "failure_code"
        const val KEY_FAILURE_DIAGNOSTIC = "failure_diagnostic"
        const val KEY_RELEASE_VERSION = "release_version"
        const val KEY_RELEASE_NOTES = "release_notes"
        const val KEY_RELEASE_PUBLISHED = "release_published_at"
        const val KEY_APK_NAME = "apk_name"
        const val KEY_APK_URL = "apk_url"
        const val KEY_APK_BYTES = "apk_bytes"
        const val KEY_APK_SHA = "apk_sha256"
    }
}

class AndroidUpdatePlatform(
    private val context: Context,
    private val expectedPackageName: String = context.packageName,
) : UpdatePlatform {
    override fun verifyAndCreateUri(apk: File, release: AppRelease): String {
        val packageManager = context.packageManager
        val candidate =
            packageManager.getPackageArchiveInfo(apk.path, signingFlags())
                ?: throw SecurityException("Downloaded file is not a readable APK")
        val installed = packageManager.getPackageInfo(expectedPackageName, signingFlags())
        ApkIdentityPolicy.requireValidUpdate(
            installed = installed.toIdentity(),
            candidate = candidate.toIdentity(),
            expectedVersionName = release.version.value.removePrefix("v"),
        )
        return FileProvider.getUriForFile(context, "${context.packageName}.files", apk).toString()
    }

    override fun requestInstall(localUri: String) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(localUri), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.toIdentity(): ApkIdentity {
        val currentSigners =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo
                    ?.apkContentsSigners
                    .orEmpty()
                    .map { it.toByteArray().sha256() }
                    .toSet()
            } else {
                signatures.orEmpty().map { it.toByteArray().sha256() }.toSet()
            }
        val lineage =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                signingInfo?.hasPastSigningCertificates() == true
            ) {
                signingInfo
                    ?.signingCertificateHistory
                    .orEmpty()
                    .map { it.toByteArray().sha256() }
                    .toSet()
            } else {
                null
            }
        val compatibleVersionCode =
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                longVersionCode
            } else {
                versionCode.toLong()
            }
        return ApkIdentity(packageName, versionName, compatibleVersionCode, currentSigners, lineage)
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        @Suppress("DEPRECATION")
        fun signingFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
    }
}
