package io.github.surioustype.localscribe.models

import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelDownload
import io.github.surioustype.localscribe.core.model.ModelDownloadStatus
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import io.github.surioustype.localscribe.core.ports.ModelCatalogRepository
import io.github.surioustype.localscribe.core.ports.ModelDownloadManager
import io.github.surioustype.localscribe.network.NetworkClient
import io.github.surioustype.localscribe.network.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class SecureModelDownloadManager(
    private val catalog: ModelCatalogRepository,
    private val installedModels: InstalledModelRepository,
    private val modelDirectory: File,
    private val networkClient: NetworkClient,
    private val scope: CoroutineScope,
    private val nowEpochMs: () -> Long,
    private val modelMutex: Mutex,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeOwnershipClaim: suspend () -> Unit = {},
    private val beforeTerminalUpdate: suspend () -> Unit = {},
) : ModelDownloadManager {
    private val downloads = MutableStateFlow<Map<String, ModelDownload>>(emptyMap())
    private val jobs = mutableMapOf<String, JobReservation>()
    private val jobsMutex = Mutex()

    override fun observeDownloads(): Flow<List<ModelDownload>> =
        downloads.asStateFlow().map { it.values.sortedBy(ModelDownload::modelId) }

    override suspend fun enqueue(modelId: String) {
        requireSafeModelId(modelId)
        val descriptor =
            catalog.getModel(modelId) ?: throw IllegalArgumentException("Unknown model")
        val job =
            jobsMutex.withLock {
                if (jobs.containsKey(modelId)) return
                lateinit var ownedJob: Job
                ownedJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            beforeOwnershipClaim()
                            jobsMutex.withLock {
                                jobs[modelId]
                                    ?.takeIf { it.job == ownedJob }
                                    ?.started = true
                            }
                            if (!reuseInstalledRevision(descriptor)) {
                                update(
                                    ModelDownload(
                                        modelId,
                                        ModelDownloadStatus.QUEUED,
                                        0,
                                        descriptor.downloadBytes,
                                    ),
                                )
                                download(descriptor)
                            }
                        } catch (cancelled: CancellationException) {
                            publishCancellationIfOwner(modelId, ownedJob, descriptor)
                        } finally {
                            withContext(NonCancellable) {
                                jobsMutex.withLock {
                                    if (jobs[modelId]?.job == ownedJob) jobs.remove(modelId)
                                }
                            }
                        }
                    }
                jobs[modelId] = JobReservation(ownedJob, descriptor)
                ownedJob
            }
        if (!job.start()) {
            jobsMutex.withLock {
                if (jobs[modelId]?.job == job) jobs.remove(modelId)
            }
        }
    }

    override suspend fun cancel(modelId: String) {
        jobsMutex.withLock {
            val reservation = jobs[modelId] ?: return@withLock
            reservation.job.cancel()
            if (!reservation.started) {
                update(
                    ModelDownload(
                        modelId,
                        ModelDownloadStatus.CANCELLED,
                        0,
                        reservation.descriptor.downloadBytes,
                    ),
                )
                jobs.remove(modelId)
            }
        }
    }

    override suspend fun retry(modelId: String) {
        val job = jobsMutex.withLock { jobs[modelId]?.job }
        job?.cancel()
        job?.join()
        jobsMutex.withLock {
            if (jobs[modelId]?.job == job) jobs.remove(modelId)
        }
        enqueue(modelId)
    }

    private suspend fun reuseInstalledRevision(descriptor: ModelDescriptor): Boolean {
        return modelMutex.withLock {
            runCatching {
                val installed =
                    installedModels.getInstalledModel(
                        descriptor.id,
                        descriptor.sha256,
                    ) ?: return@withLock false
                val expectedFile =
                    File(
                        modelDirectory,
                        "${descriptor.id}-${descriptor.sha256}.bin",
                    ).canonicalFile
                val installedFile = File(installed.filePath).canonicalFile
                if (
                    installedFile != expectedFile ||
                    !installedFile.isFile ||
                    installedFile.length() != descriptor.downloadBytes ||
                    installed.bytes != descriptor.downloadBytes ||
                    !installed.sha256.equals(descriptor.sha256, ignoreCase = true)
                ) {
                    return@withLock false
                }
                if (withContext(ioDispatcher) { sha256(installedFile) != descriptor.sha256 }) {
                    return@withLock false
                }
                update(
                    ModelDownload(
                        descriptor.id,
                        ModelDownloadStatus.COMPLETED,
                        descriptor.downloadBytes,
                        descriptor.downloadBytes,
                    ),
                )
                true
            }.getOrElse { exception ->
                if (exception is CancellationException) throw exception
                false
            }
        }
    }

    private suspend fun publishCancellationIfOwner(
        modelId: String,
        ownedJob: Job,
        descriptor: ModelDescriptor,
    ) {
        jobsMutex.withLock {
            if (jobs[modelId]?.job != ownedJob) return@withLock
            update(
                ModelDownload(
                    modelId,
                    ModelDownloadStatus.CANCELLED,
                    0,
                    descriptor.downloadBytes,
                ),
            )
            jobs.remove(modelId)
        }
    }

    private suspend fun download(descriptor: ModelDescriptor) {
        val finalFile = File(modelDirectory, "${descriptor.id}-${descriptor.sha256}.bin")
        val partialFile = File(modelDirectory, "${descriptor.id}-${descriptor.sha256}.part")
        var fileMoved = false
        var metadataRegistered = false
        var publicationCompleted = false
        val installedModel =
            InstalledModel(
                id = "${descriptor.id}-${descriptor.sha256}",
                descriptorId = descriptor.id,
                displayName = descriptor.displayName,
                filePath = finalFile.canonicalPath,
                sha256 = descriptor.sha256,
                bytes = descriptor.downloadBytes,
                installedAtEpochMs = nowEpochMs(),
                verifiedAtEpochMs = nowEpochMs(),
            )
        try {
            withContext(ioDispatcher) {
                modelDirectory.mkdirs()
                require(modelDirectory.isDirectory) { "Model directory is unavailable" }
                val existing =
                    partialFile.length().takeIf {
                        partialFile.isFile &&
                            it in 1 until descriptor.downloadBytes
                    }
                        ?: 0L
                if (partialFile.exists() && existing == 0L) partialFile.delete()
                val headers =
                    if (existing >
                        0L
                    ) {
                        mapOf("Range" to "bytes=$existing-")
                    } else {
                        emptyMap()
                    }
                networkClient.get(descriptor.downloadUrl, headers).use { response ->
                    val append =
                        when {
                            existing == 0L && response.statusCode == 200 -> false
                            existing > 0L && response.statusCode == 206 -> {
                                validateContentRange(
                                    response.headers.header("Content-Range"),
                                    existing,
                                    descriptor.downloadBytes,
                                )
                                true
                            }
                            existing > 0L && response.statusCode == 200 -> false
                            else -> throw IllegalStateException(
                                "Unexpected model HTTP status ${response.statusCode}",
                            )
                        }
                    val initial = if (append) existing else 0L
                    streamBounded(response.body, partialFile, append, initial, descriptor)
                }
                update(
                    ModelDownload(
                        descriptor.id,
                        ModelDownloadStatus.VERIFYING,
                        partialFile.length(),
                        descriptor.downloadBytes,
                    ),
                )
                require(partialFile.length() == descriptor.downloadBytes) {
                    "Downloaded model has unexpected length"
                }
                require(
                    sha256(partialFile) == descriptor.sha256,
                ) { "Downloaded model checksum mismatch" }
                modelMutex.withLock {
                    coroutineContext.ensureActive()
                    withContext(NonCancellable) {
                        Files.move(
                            partialFile.toPath(),
                            finalFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                        fileMoved = true
                        installedModels.register(installedModel)
                        metadataRegistered = true
                        beforeTerminalUpdate()
                        update(
                            ModelDownload(
                                descriptor.id,
                                ModelDownloadStatus.COMPLETED,
                                descriptor.downloadBytes,
                                descriptor.downloadBytes,
                            ),
                        )
                        publicationCompleted = true
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (publicationCompleted) return
            rollbackPublication(finalFile, fileMoved, metadataRegistered, installedModel)
            update(
                ModelDownload(
                    descriptor.id,
                    ModelDownloadStatus.CANCELLED,
                    partialFile.length(),
                    descriptor.downloadBytes,
                ),
            )
        } catch (exception: Exception) {
            rollbackPublication(finalFile, fileMoved, metadataRegistered, installedModel)
            update(
                ModelDownload(
                    descriptor.id,
                    ModelDownloadStatus.FAILED,
                    partialFile.length(),
                    descriptor.downloadBytes,
                    DomainFailure(
                        if (exception.message?.contains("checksum", true) ==
                            true
                        ) {
                            FailureCode.MODEL_CHECKSUM_MISMATCH
                        } else {
                            FailureCode.DOWNLOAD_INTERRUPTED
                        },
                        exception.message,
                    ),
                ),
            )
        }
    }

    private suspend fun rollbackPublication(
        finalFile: File,
        fileMoved: Boolean,
        metadataRegistered: Boolean,
        installedModel: InstalledModel,
    ) {
        if (!fileMoved && !metadataRegistered) return
        withContext(NonCancellable) {
            modelMutex.withLock {
                if (metadataRegistered) installedModels.remove(installedModel)
                if (fileMoved && finalFile.exists()) {
                    check(finalFile.delete()) { "Unable to roll back published model file" }
                }
            }
        }
    }

    private suspend fun streamBounded(
        input: java.io.InputStream,
        output: File,
        append: Boolean,
        initialBytes: Long,
        descriptor: ModelDescriptor,
    ) {
        FileOutputStream(output, append).use { sink ->
            val buffer = ByteArray(64 * 1024)
            var received = initialBytes
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                received += count
                require(
                    received <= descriptor.downloadBytes,
                ) { "Model response exceeds declared size" }
                sink.write(buffer, 0, count)
                update(
                    ModelDownload(
                        descriptor.id,
                        ModelDownloadStatus.DOWNLOADING,
                        received,
                        descriptor.downloadBytes,
                    ),
                )
            }
            sink.fd.sync()
        }
    }

    private fun validateContentRange(value: String?, start: Long, total: Long) {
        val match =
            value?.let(CONTENT_RANGE::matchEntire)
                ?: throw IllegalArgumentException("Missing or invalid Content-Range")
        require(match.groupValues[1].toLong() == start) { "Content-Range starts at the wrong byte" }
        require(
            match.groupValues[2].toLong() == total - 1 && match.groupValues[3].toLong() == total,
        ) {
            "Content-Range has invalid bounds"
        }
    }

    private fun update(download: ModelDownload) {
        downloads.update { current -> current + (download.modelId to download) }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
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

    private fun requireSafeModelId(modelId: String) {
        require(Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(modelId)) { "Unsafe model id" }
    }

    private data class JobReservation(
        val job: Job,
        val descriptor: ModelDescriptor,
        var started: Boolean = false,
    )

    private companion object {
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
