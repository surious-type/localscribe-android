package io.github.surioustype.localscribe.updates

import io.github.surioustype.localscribe.core.model.AppRelease
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateDownloadStatus
import io.github.surioustype.localscribe.network.ByteArrayNetworkResponse
import io.github.surioustype.localscribe.network.NetworkClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class GithubAppUpdateManagerTest {
    @Test
    fun `automatic check honors cache while forced manual check bypasses it`() =
        runTest {
            var requests = 0
            val store = MemoryUpdateCheckStore(900L)
            val manager =
                manager(store = store, now = { 1_000L }) { url, _ ->
                    requests++
                    response(url)
                }

            val cached = manager.checkForUpdate(AppVersion("1.0.0"), force = false)
            val forced = manager.checkForUpdate(AppVersion("1.0.0"), force = true)

            assertEquals(UpdateAvailability.UP_TO_DATE, cached.availability)
            assertEquals(UpdateAvailability.AVAILABLE, forced.availability)
            assertEquals(2, requests)
            assertEquals(1_000L, store.lastCheckEpochMs())
        }

    @Test
    fun `failed request remains failed rather than up to date`() =
        runTest {
            val store = MemoryUpdateCheckStore(null)
            val manager = manager(store = store) { _, _ -> ByteArrayNetworkResponse(503) }

            val state = manager.checkForUpdate(AppVersion("1.0.0"), force = true)
            val restarted =
                manager(store = store) { _, _ -> error("cached failure must not make a request") }

            assertEquals(UpdateAvailability.FAILED, state.availability)
            assertEquals(
                UpdateAvailability.FAILED,
                restarted.checkForUpdate(AppVersion("1.0.0"), force = false).availability,
            )
        }

    @Test
    fun `available result survives process restart inside cache interval`() =
        runTest {
            val store = MemoryUpdateCheckStore(null)
            val first = manager(store = store) { url, _ -> response(url) }
            first.checkForUpdate(AppVersion("1.0.0"), force = true)
            var apkRequests = 0
            val restarted =
                manager(store = store) { url, _ ->
                    check(url.endsWith(".apk")) { "cached update check must not make a request" }
                    apkRequests++
                    response(url)
                }

            val cached = restarted.checkForUpdate(AppVersion("1.0.0"), force = false)
            restarted.downloadAvailableUpdate()
            advanceUntilIdle()

            assertEquals(UpdateAvailability.AVAILABLE, cached.availability)
            assertEquals(AppVersion("v1.1.0"), cached.release?.version)
            assertEquals(1, apkRequests)
            assertEquals(
                UpdateDownloadStatus.READY_TO_INSTALL,
                restarted.observeDownload().first().status,
            )
        }

    @Test
    fun `cached available release at or below running version becomes up to date`() =
        runTest {
            listOf(AppVersion("1.1.0"), AppVersion("1.2.0")).forEach { runningVersion ->
                val store = MemoryUpdateCheckStore(null)
                manager(store = store) { url, _ -> response(url) }
                    .checkForUpdate(AppVersion("1.0.0"), force = true)
                val restarted =
                    manager(store = store) { _, _ -> error("fresh cache must not make a request") }

                val cached = restarted.checkForUpdate(runningVersion, force = false)
                val downloadFailure =
                    runCatching { restarted.downloadAvailableUpdate() }
                        .exceptionOrNull()

                assertEquals(UpdateAvailability.UP_TO_DATE, cached.availability)
                assertEquals(null, cached.release)
                assertTrue(downloadFailure is IllegalStateException)
            }
        }

    @Test
    fun `failed forced check clears previously available release`() =
        runTest {
            var failChecks = false
            var apkRequests = 0
            val manager =
                manager { url, _ ->
                    when {
                        url.endsWith(".apk") -> {
                            apkRequests++
                            response(url)
                        }
                        failChecks -> ByteArrayNetworkResponse(503)
                        else -> response(url)
                    }
                }
            assertEquals(
                UpdateAvailability.AVAILABLE,
                manager.checkForUpdate(AppVersion("1.0.0"), force = true).availability,
            )

            failChecks = true
            val failed = manager.checkForUpdate(AppVersion("1.0.0"), force = true)
            val downloadFailure =
                runCatching { manager.downloadAvailableUpdate() }
                    .exceptionOrNull()

            assertEquals(UpdateAvailability.FAILED, failed.availability)
            assertTrue(downloadFailure is IllegalStateException)
            assertEquals(0, apkRequests)
        }

    @Test
    fun `overlapping checks commit in order and final failure has no downloadable release`() =
        runTest {
            val firstChecksumEntered = CompletableDeferred<Unit>()
            val releaseFirstChecksum = CompletableDeferred<Unit>()
            val secondReleaseEntered = CompletableDeferred<Unit>()
            val releaseSecondFailure = CompletableDeferred<Unit>()
            var releaseRequests = 0
            var apkRequests = 0
            val manager =
                manager { url, _ ->
                    when {
                        url.endsWith(".apk") -> {
                            apkRequests++
                            response(url)
                        }
                        url.endsWith("/releases/latest") && ++releaseRequests == 1 -> response(url)
                        url.endsWith("/releases/latest") -> {
                            secondReleaseEntered.complete(Unit)
                            releaseSecondFailure.await()
                            ByteArrayNetworkResponse(503)
                        }
                        url.endsWith(".sha256") -> {
                            firstChecksumEntered.complete(Unit)
                            releaseFirstChecksum.await()
                            response(url)
                        }
                        else -> error("Unexpected URL $url")
                    }
                }

            val first = async { manager.checkForUpdate(AppVersion("1.0.0"), force = true) }
            firstChecksumEntered.await()
            val second = async { manager.checkForUpdate(AppVersion("1.0.0"), force = true) }
            runCurrent()
            releaseFirstChecksum.complete(Unit)
            assertEquals(UpdateAvailability.AVAILABLE, first.await().availability)
            secondReleaseEntered.await()
            releaseSecondFailure.complete(Unit)

            assertEquals(UpdateAvailability.FAILED, second.await().availability)
            val downloadFailure =
                runCatching { manager.downloadAvailableUpdate() }
                    .exceptionOrNull()
            assertTrue(downloadFailure is IllegalStateException)
            assertEquals(0, apkRequests)
        }

    @Test
    fun `download selection waits for in flight check outcome`() =
        runTest {
            var failNextCheck = false
            var apkRequests = 0
            val failedCheckEntered = CompletableDeferred<Unit>()
            val releaseFailedCheck = CompletableDeferred<Unit>()
            val manager =
                manager { url, _ ->
                    when {
                        url.endsWith(".apk") -> {
                            apkRequests++
                            response(url)
                        }
                        failNextCheck && url.endsWith("/releases/latest") -> {
                            failedCheckEntered.complete(Unit)
                            releaseFailedCheck.await()
                            ByteArrayNetworkResponse(503)
                        }
                        else -> response(url)
                    }
                }
            manager.checkForUpdate(AppVersion("1.0.0"), force = true)
            failNextCheck = true

            val check = async { manager.checkForUpdate(AppVersion("1.0.0"), force = true) }
            failedCheckEntered.await()
            val download =
                async { runCatching { manager.downloadAvailableUpdate() }.exceptionOrNull() }
            runCurrent()

            assertFalse(download.isCompleted)
            releaseFailedCheck.complete(Unit)
            assertEquals(UpdateAvailability.FAILED, check.await().availability)
            assertTrue(download.await() is IllegalStateException)
            assertEquals(0, apkRequests)
        }

    @Test
    fun `future cache timestamp is stale`() =
        runTest {
            var requests = 0
            val store = MemoryUpdateCheckStore(20_000L)
            val manager =
                manager(store = store, now = { 10_000L }) { url, _ ->
                    requests++
                    response(url)
                }

            val state = manager.checkForUpdate(AppVersion("1.0.0"), force = false)

            assertEquals(UpdateAvailability.AVAILABLE, state.availability)
            assertEquals(2, requests)
        }

    @Test
    fun `never checked manager starts unknown`() =
        runTest {
            val manager = manager { _, _ -> error("not requested") }

            assertEquals(
                "UNKNOWN",
                manager
                    .observeState()
                    .first()
                    .availability.name,
            )
        }

    @Test
    fun `download verifies checksum then requires package and signer verification`() =
        runTest {
            val platform = FakeUpdatePlatform(accept = false)
            val manager = manager(platform = platform) { url, _ -> response(url) }
            manager.checkForUpdate(AppVersion("1.0.0"), force = true)

            manager.downloadAvailableUpdate()
            advanceUntilIdle()

            assertTrue(platform.verifyCalled)
            assertEquals(UpdateDownloadStatus.FAILED, manager.observeDownload().first().status)
        }

    private fun kotlinx.coroutines.test.TestScope.manager(
        store: UpdateCheckStore = MemoryUpdateCheckStore(null),
        now: () -> Long = { 10_000L },
        platform: FakeUpdatePlatform = FakeUpdatePlatform(true),
        client: NetworkClient,
    ) = GithubAppUpdateManager(
        networkClient = client,
        checkStore = store,
        platform = platform,
        updateDirectory =
            kotlin.io.path
                .createTempDirectory("updates-test")
                .toFile(),
        scope = this,
        nowEpochMs = now,
        automaticChecksEnabled = { true },
        cacheIntervalMs = 500L,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun response(url: String): ByteArrayNetworkResponse =
        when {
            url.endsWith(
                "/releases/latest",
            ) -> ByteArrayNetworkResponse(200, bytes = RELEASE_JSON.toByteArray())
            url.endsWith(
                ".sha256",
            ) ->
                ByteArrayNetworkResponse(
                    200,
                    bytes = "$APK_SHA  localscribe-v1.1.0.apk\n".toByteArray(),
                )
            url.endsWith(".apk") -> ByteArrayNetworkResponse(200, bytes = APK_BYTES)
            else -> error("Unexpected URL $url")
        }

    private class FakeUpdatePlatform(private val accept: Boolean) : UpdatePlatform {
        var verifyCalled = false

        override fun verifyAndCreateUri(apk: File, release: AppRelease): String {
            verifyCalled = true
            check(accept) { "Package signer mismatch" }
            return "content://verified/update.apk"
        }

        override fun requestInstall(localUri: String) = Unit
    }

    private companion object {
        const val RELEASE_DOWNLOAD_BASE =
            "https://github.com/surious-type/localscribe-android/releases/download/" + "v1.1.0/"

        val APK_BYTES = "signed apk bytes".toByteArray()
        val APK_SHA =
            MessageDigest.getInstance("SHA-256").digest(APK_BYTES).joinToString("") {
                "%02x".format(it)
            }
        val RELEASE_JSON
            get() =
                """
                {"draft":false,"prerelease":false,"tag_name":"v1.1.0","body":"Notes",
                 "published_at":"2026-09-17T12:00:00Z","assets":[
                   {"name":"localscribe-v1.1.0.apk",
                    "browser_download_url":"${RELEASE_DOWNLOAD_BASE}localscribe-v1.1.0.apk",
                    "size":${APK_BYTES.size}},
                   {"name":"localscribe-v1.1.0.apk.sha256",
                    "browser_download_url":"${RELEASE_DOWNLOAD_BASE}localscribe-v1.1.0.apk.sha256",
                    "size":90}
                 ]}
                """.trimIndent()
    }
}
