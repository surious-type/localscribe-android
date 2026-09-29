package io.github.surioustype.localscribe.updates

import io.github.surioustype.localscribe.core.domain.ReleaseParser
import io.github.surioustype.localscribe.core.model.AppRelease
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateDownload
import io.github.surioustype.localscribe.core.model.UpdateDownloadStatus
import io.github.surioustype.localscribe.core.model.UpdateState
import io.github.surioustype.localscribe.core.ports.AppUpdateManager
import io.github.surioustype.localscribe.models.readBounded
import io.github.surioustype.localscribe.network.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class GithubAppUpdateManager(
    private val networkClient: NetworkClient,
    private val checkStore: UpdateCheckStore,
    private val platform: UpdatePlatform,
    private val updateDirectory: File,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val automaticChecksEnabled: () -> Boolean,
    private val cacheIntervalMs: Long = DEFAULT_CACHE_INTERVAL_MS,
    private val releaseParser: ReleaseParser = ReleaseParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdateManager {
    private val initialCachedState = checkStore.cachedState()
    private val state =
        MutableStateFlow(
            if (automaticChecksEnabled()) {
                initialCachedState ?: UpdateState(UpdateAvailability.UNKNOWN)
            } else {
                UpdateState(
                    UpdateAvailability.DISABLED,
                    lastCheckedAtEpochMs = initialCachedState?.lastCheckedAtEpochMs,
                )
            },
        )
    private val download = MutableStateFlow(UpdateDownload(UpdateDownloadStatus.IDLE, 0, 0))
    private val checkMutex = Mutex()
    private var downloadJob: Job? = null
    private var currentRelease: AppRelease? = null

    override fun observeState(): StateFlow<UpdateState> = state

    override fun observeDownload(): StateFlow<UpdateDownload> = download

    override suspend fun checkForUpdate(currentVersion: AppVersion, force: Boolean): UpdateState =
        checkMutex.withLock { checkForUpdateLocked(currentVersion, force) }

    private suspend fun checkForUpdateLocked(
        currentVersion: AppVersion,
        force: Boolean,
    ): UpdateState {
        val now = nowEpochMs()
        val cachedState = checkStore.cachedState()
        val lastCheck = cachedState?.lastCheckedAtEpochMs
        currentRelease = null
        if (!force && !automaticChecksEnabled()) {
            return UpdateState(UpdateAvailability.DISABLED, lastCheckedAtEpochMs = lastCheck).also {
                state.value =
                    it
            }
        }
        if (!force && lastCheck != null && lastCheck <= now && now - lastCheck < cacheIntervalMs) {
            normalizeCachedState(requireNotNull(cachedState), currentVersion)?.let { cached ->
                currentRelease = cached.release
                return cached.also { state.value = it }
            }
        }
        state.value = UpdateState(UpdateAvailability.CHECKING, lastCheckedAtEpochMs = lastCheck)
        return try {
            val releaseJson = fetchBounded(RELEASE_API_URL, MAX_RELEASE_JSON_BYTES)
            val checksumUrl = exactChecksumUrl(releaseJson)
            val checksum = fetchBounded(checksumUrl, MAX_CHECKSUM_BYTES)
            val release = releaseParser.parse(releaseJson, checksum)
            currentRelease = release.takeIf { it.version > currentVersion }
            val result =
                UpdateState(
                    availability =
                        if (currentRelease ==
                            null
                        ) {
                            UpdateAvailability.UP_TO_DATE
                        } else {
                            UpdateAvailability.AVAILABLE
                        },
                    release = currentRelease,
                    lastCheckedAtEpochMs = now,
                )
            checkStore.save(result)
            result.also { state.value = it }
        } catch (exception: Exception) {
            val result =
                UpdateState(
                    availability = UpdateAvailability.FAILED,
                    lastCheckedAtEpochMs = now,
                    failure = DomainFailure(FailureCode.NETWORK_UNAVAILABLE, exception.message),
                )
            checkStore.save(result)
            result.also { state.value = it }
        }
    }

    private fun normalizeCachedState(
        cached: UpdateState,
        currentVersion: AppVersion,
    ): UpdateState? {
        if (cached.availability != UpdateAvailability.AVAILABLE) return cached
        val release = cached.release ?: return null
        return if (release.version > currentVersion) {
            cached
        } else {
            UpdateState(
                UpdateAvailability.UP_TO_DATE,
                lastCheckedAtEpochMs = cached.lastCheckedAtEpochMs,
            )
        }
    }

    override suspend fun downloadAvailableUpdate() =
        checkMutex.withLock {
            if (downloadJob?.isActive == true) return@withLock
            check(
                state.value.availability == UpdateAvailability.AVAILABLE,
            ) { "No update is available" }
            val release = currentRelease ?: throw IllegalStateException("No update is available")
            downloadJob = scope.launch { download(release) }
        }

    override suspend fun cancelDownload() {
        downloadJob?.cancel()
    }

    override suspend fun requestInstall() {
        val uri =
            download.value.localUri ?: throw IllegalStateException("No verified update is ready")
        platform.requestInstall(uri)
    }

    private suspend fun download(release: AppRelease) {
        val partial = File(updateDirectory, "${release.apk.fileName}.part")
        val final = File(updateDirectory, release.apk.fileName)
        try {
            withContext(ioDispatcher) {
                updateDirectory.mkdirs()
                networkClient.get(release.apk.downloadUrl, emptyMap()).use { response ->
                    require(
                        response.statusCode == 200,
                    ) { "Update download failed with HTTP ${response.statusCode}" }
                    FileOutputStream(partial, false).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = response.body.read(buffer)
                            if (count < 0) break
                            received += count
                            require(
                                received <= release.apk.bytes && received <= MAX_APK_BYTES,
                            ) { "APK exceeds declared size" }
                            output.write(buffer, 0, count)
                            download.value =
                                UpdateDownload(
                                    UpdateDownloadStatus.DOWNLOADING,
                                    received,
                                    release.apk.bytes,
                                )
                        }
                        output.fd.sync()
                    }
                }
                download.value =
                    UpdateDownload(
                        UpdateDownloadStatus.VERIFYING,
                        partial.length(),
                        release.apk.bytes,
                    )
                require(partial.length() == release.apk.bytes) { "APK has unexpected size" }
                require(partial.sha256() == release.apk.sha256) { "APK checksum mismatch" }
                check(!final.exists() || final.delete()) { "Unable to replace cached APK" }
                Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                val uri = platform.verifyAndCreateUri(final, release)
                download.value =
                    UpdateDownload(
                        UpdateDownloadStatus.READY_TO_INSTALL,
                        final.length(),
                        release.apk.bytes,
                        uri,
                    )
            }
        } catch (cancelled: CancellationException) {
            download.value =
                UpdateDownload(UpdateDownloadStatus.CANCELLED, partial.length(), release.apk.bytes)
        } catch (exception: Exception) {
            final.delete()
            download.value =
                UpdateDownload(
                    UpdateDownloadStatus.FAILED,
                    partial.length(),
                    release.apk.bytes,
                    failure =
                        DomainFailure(
                            FailureCode.UPDATE_VERIFICATION_FAILED,
                            exception.message,
                        ),
                )
        }
    }

    private suspend fun fetchBounded(url: String, maxBytes: Int): String =
        networkClient
            .get(
                url,
                mapOf(
                    "Accept" to "application/vnd.github+json",
                    "X-GitHub-Api-Version" to "2022-11-28",
                ),
            ).use {
                require(it.statusCode == 200) { "Update check failed with HTTP ${it.statusCode}" }
                it.body.readBounded(maxBytes).decodeToString()
            }

    private fun exactChecksumUrl(releaseJson: String): String {
        val root = JSON.parseToJsonElement(releaseJson).jsonObject
        val tag =
            root["tag_name"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Missing release tag")
        val expectedName = "localscribe-$tag.apk.sha256"
        val assets =
            root["assets"] as? JsonArray ?: throw IllegalArgumentException("Missing release assets")
        val matches =
            assets.mapNotNull { asset ->
                val value = asset.jsonObject
                value
                    .takeIf { it["name"]?.jsonPrimitive?.contentOrNull == expectedName }
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
        require(matches.size == 1) { "Release must contain exactly one checksum asset" }
        val url = matches.single()
        val uri = URI(url)
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.port == -1 &&
                uri.rawPath ==
                "/surious-type/localscribe-android/releases/download/$tag/$expectedName" &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) { "Checksum URL must match the expected GitHub release path" }
        return url
    }

    private suspend fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/surious-type/localscribe-android/releases/latest"
        const val DEFAULT_CACHE_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val MAX_RELEASE_JSON_BYTES = 512 * 1024
        const val MAX_CHECKSUM_BYTES = 8 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
