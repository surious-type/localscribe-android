package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.AppRelease
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.ReleaseAsset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant

class ReleaseParser {
    fun parse(releaseJson: String, checksumText: String): AppRelease {
        val release =
            try {
                JSON.parseToJsonElement(releaseJson).jsonObject
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException("Invalid release JSON", exception)
            }
        require(release.requiredBoolean("draft").not()) { "Draft releases are not installable" }
        require(release.requiredBoolean("prerelease").not()) { "Prereleases are not installable" }

        val tag = release.requiredString("tag_name")
        val version = AppVersion(tag)
        require('-' !in version.value) { "Prerelease versions are not installable" }
        val versionWithoutPrefix = version.value.removePrefix("v")
        val apkName = "localscribe-v$versionWithoutPrefix.apk"
        val checksumName = "$apkName.sha256"
        val assets =
            release["assets"] as? JsonArray
                ?: throw IllegalArgumentException("Missing release assets")
        val apkAssets = assets.objectsNamed(apkName)
        val checksumAssets = assets.objectsNamed(checksumName)
        require(apkAssets.size == 1) { "Release must contain exactly one expected APK" }
        require(
            checksumAssets.size == 1,
        ) { "Release must contain exactly one expected checksum asset" }

        val apk = apkAssets.single()
        val apkUrl = apk.requiredString("browser_download_url")
        val checksumUrl = checksumAssets.single().requiredString("browser_download_url")
        requireExpectedReleaseAssetUrl(apkUrl, tag, apkName)
        requireExpectedReleaseAssetUrl(checksumUrl, tag, checksumName)
        val bytes = apk.requiredLong("size")
        require(bytes > 0) { "APK size must be positive" }
        require(
            checksumAssets.single().requiredLong("size") > 0,
        ) { "Checksum size must be positive" }

        return AppRelease(
            version = version,
            releaseNotes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            publishedAtEpochMs = parsePublishedAt(release.requiredString("published_at")),
            apk =
                ReleaseAsset(
                    fileName = apkName,
                    downloadUrl = apkUrl,
                    bytes = bytes,
                    sha256 = parseChecksum(checksumText, apkName),
                ),
        )
    }

    private fun parsePublishedAt(value: String): Long =
        try {
            Instant.parse(value).toEpochMilli()
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("Invalid release publication timestamp", exception)
        }

    private fun parseChecksum(text: String, apkName: String): String {
        val lines =
            text
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        require(lines.isNotEmpty()) { "Missing APK checksum" }
        val rawChecksum =
            lines.singleOrNull()?.takeIf { line ->
                line.length == SHA_256_LENGTH &&
                    line.isHexadecimal()
            }
        if (rawChecksum != null) return rawChecksum.lowercase()

        val matches =
            lines.mapNotNull { line ->
                val parts = line.split(WHITESPACE).filter(String::isNotEmpty)
                if (parts.size < 2) return@mapNotNull null
                val fileName = parts.last().removePrefix("*")
                parts.first().takeIf { checksum ->
                    fileName == apkName &&
                        checksum.length == SHA_256_LENGTH &&
                        checksum.isHexadecimal()
                }
            }
        require(matches.size == 1) { "Expected exactly one checksum for the APK" }
        return matches.single().lowercase()
    }

    private fun requireExpectedReleaseAssetUrl(
        value: String,
        tag: String,
        fileName: String,
    ) {
        val uri =
            try {
                URI(value)
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException("Invalid release asset URL", exception)
            }
        val expectedPath = "/$GITHUB_REPOSITORY/releases/download/$tag/$fileName"
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(GITHUB_HOST, ignoreCase = true) &&
                (uri.port == -1 || uri.port == HTTPS_PORT) &&
                uri.rawPath == expectedPath &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) { "Release asset URL must match the expected GitHub release path" }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.jsonPrimitive?.booleanOrNull ?: throw IllegalArgumentException("Missing $name")

    private fun JsonObject.requiredLong(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("Missing $name")

    private fun JsonArray.objectsNamed(name: String): List<JsonObject> =
        mapNotNull { element ->
            val objectValue = element as? JsonObject ?: return@mapNotNull null
            objectValue.takeIf { it["name"]?.jsonPrimitive?.contentOrNull == name }
        }

    private fun String.isHexadecimal(): Boolean =
        all { character ->
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        }

    private companion object {
        const val GITHUB_HOST = "github.com"
        const val GITHUB_REPOSITORY = "surious-type/localscribe-android"
        const val HTTPS_PORT = 443
        const val SHA_256_LENGTH = 64
        val WHITESPACE = Regex("\\s+")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
