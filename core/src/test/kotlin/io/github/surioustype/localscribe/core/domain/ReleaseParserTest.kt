package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    private val parser = ReleaseParser()
    private val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `versions use semantic numeric and prerelease ordering`() {
        assertTrue(AppVersion("1.10.0") > AppVersion("1.9.0"))
        assertTrue(AppVersion("v2.0.0") > AppVersion("1.99.99"))
        assertTrue(AppVersion("1.0.0") > AppVersion("1.0.0-rc.1"))
        assertTrue(AppVersion("1.0.0-beta.11") > AppVersion("1.0.0-beta.2"))
        assertTrue(AppVersion("1.0.0-alpha.1") > AppVersion("1.0.0-alpha"))
    }

    @Test
    fun `versions outside the frozen grammar are rejected`() {
        listOf(
            "1.2",
            "1.2.3.4",
            "1.02.3",
            "1.2.3+build",
            "1.2.3-",
            "version1.2.3",
        ).forEach { invalid ->
            assertFails { AppVersion(invalid) }
        }
    }

    @Test
    fun `stable GitHub release with exact APK and checksum assets parses`() {
        val release = parser.parse(releaseJson(), "$hash  localscribe-v1.2.3.apk\n")

        assertEquals(AppVersion("v1.2.3"), release.version)
        assertEquals("notes", release.releaseNotes)
        assertEquals(0L, release.publishedAtEpochMs)
        assertEquals("localscribe-v1.2.3.apk", release.apk.fileName)
        assertEquals(hash, release.apk.sha256)
        assertEquals(42L, release.apk.bytes)
    }

    @Test
    fun `draft prerelease missing ambiguous and unsafe release assets are rejected`() {
        assertFails { parser.parse(releaseJson(draft = true), hash) }
        assertFails { parser.parse(releaseJson(prerelease = true), hash) }
        assertFails { parser.parse(releaseJson(includeChecksum = false), hash) }
        assertFails { parser.parse(releaseJson(duplicateApk = true), hash) }
        assertFails { parser.parse(releaseJson(apkUrl = "http://example.com/app.apk"), hash) }
        assertFails { parser.parse(releaseJson(), "") }
        assertFails { parser.parse(releaseJson(), "f".repeat(64) + "  other.apk") }
    }

    @Test
    fun `release assets must belong to the exact GitHub repository tag and file path`() {
        assertFails {
            parser.parse(
                releaseJson(apkUrl = DEFAULT_APK_URL.replace("github.com", "attacker.example")),
                hash,
            )
        }
        assertFails {
            parser.parse(
                releaseJson(
                    apkUrl = DEFAULT_APK_URL.replace("localscribe-android", "other-repository"),
                ),
                hash,
            )
        }
        assertFails {
            parser.parse(
                releaseJson(apkUrl = DEFAULT_APK_URL.replace("/v1.2.3/", "/v9.9.9/")),
                hash,
            )
        }
        assertFails {
            parser.parse(
                releaseJson(checksumUrl = DEFAULT_CHECKSUM_URL.replace("/v1.2.3/", "/v9.9.9/")),
                hash,
            )
        }
    }

    private fun releaseJson(
        draft: Boolean = false,
        prerelease: Boolean = false,
        includeChecksum: Boolean = true,
        duplicateApk: Boolean = false,
        apkUrl: String = DEFAULT_APK_URL,
        checksumUrl: String = DEFAULT_CHECKSUM_URL,
    ): String {
        val apk = """{"name":"localscribe-v1.2.3.apk","browser_download_url":"$apkUrl","size":42}"""
        val assets =
            buildList {
                add(apk)
                if (duplicateApk) add(apk)
                if (includeChecksum) {
                    add(
                        """{"name":"localscribe-v1.2.3.apk.sha256","browser_download_url":"$checksumUrl","size":100}""",
                    )
                }
            }.joinToString(",")
        return """
            {
                "tag_name":"v1.2.3",
                "draft":$draft,
                "prerelease":$prerelease,
                "body":"notes",
                "published_at":"1970-01-01T00:00:00Z",
                "assets":[$assets]
            }
            """.trimIndent()
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private companion object {
        const val DEFAULT_APK_URL =
            "https://github.com/surious-type/localscribe-android/releases/download/" +
                "v1.2.3/localscribe-v1.2.3.apk"
        const val DEFAULT_CHECKSUM_URL =
            "https://github.com/surious-type/localscribe-android/releases/download/" +
                "v1.2.3/localscribe-v1.2.3.apk.sha256"
    }
}
