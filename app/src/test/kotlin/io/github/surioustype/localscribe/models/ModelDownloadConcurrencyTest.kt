package io.github.surioustype.localscribe.models

import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelDownloadStatus
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.ModelQuality
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import io.github.surioustype.localscribe.core.ports.ModelCatalogRepository
import io.github.surioustype.localscribe.network.ByteArrayNetworkResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadConcurrencyTest {
    @Test
    fun `simultaneous enqueue owns one transfer for a model`() =
        runTest {
            val bytes = "one model".toByteArray()
            val descriptor = descriptor("small", bytes)
            val catalog = GatedCatalog(mapOf(descriptor.id to descriptor), expectedLookups = 16)
            val transfers = AtomicInteger()
            val manager =
                manager(catalog) { _, _ ->
                    transfers.incrementAndGet()
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }

            List(16) { async(Dispatchers.Default) { manager.enqueue(descriptor.id) } }.awaitAll()
            advanceUntilIdle()

            assertEquals(1, transfers.get())
            assertEquals(
                ModelDownloadStatus.COMPLETED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `separate model downloads preserve both status entries`() =
        runTest {
            val smallBytes = "small model".toByteArray()
            val vadBytes = "vad model".toByteArray()
            val descriptors = listOf(descriptor("small", smallBytes), descriptor("vad", vadBytes))
            val transfers = ConcurrentHashMap<String, AtomicInteger>()
            val manager =
                manager(
                    GatedCatalog(descriptors.associateBy { it.id }, expectedLookups = 2),
                ) { url, _ ->
                    transfers.computeIfAbsent(url) { AtomicInteger() }.incrementAndGet()
                    val bytes = if (url.endsWith("small.bin")) smallBytes else vadBytes
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }

            descriptors.map { async(Dispatchers.Default) { manager.enqueue(it.id) } }.awaitAll()
            advanceUntilIdle()

            assertEquals(2, transfers.size)
            assertEquals(
                listOf("small", "vad"),
                manager
                    .observeDownloads()
                    .first()
                    .filter {
                        it.status == ModelDownloadStatus.COMPLETED
                    }.map { it.modelId },
            )
        }

    @Test
    fun `completed revision releases ownership for normal enqueue of new catalog revision`() =
        runTest {
            val revisionA = descriptor("small", "revision A".toByteArray())
            val revisionB = descriptor("small", "revision B".toByteArray())
            val catalog = MutableCatalog(revisionA, "revision A".toByteArray())
            val installed = ConcurrentInstalledModels()
            val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val transfers = AtomicInteger()
            val manager =
                manager(
                    catalog = catalog,
                    installedModels = installed,
                    executionScope = immediateScope,
                    ioDispatcher = Dispatchers.Unconfined,
                ) { url, _ ->
                    transfers.incrementAndGet()
                    val bytes =
                        if (url.endsWith(
                                "small.bin",
                            )
                        ) {
                            catalog.currentBytes
                        } else {
                            error("Unexpected URL $url")
                        }
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }

            manager.enqueue("small")
            catalog.replace(revisionB, "revision B".toByteArray())
            manager.enqueue("small")

            assertEquals(2, transfers.get())
            assertEquals(
                setOf(revisionA.sha256, revisionB.sha256),
                installed
                    .snapshot()
                    .map {
                        it.sha256
                    }.toSet(),
            )
            immediateScope.cancel()
        }

    @Test
    fun `deleted revision can be reinstalled by normal enqueue in same manager`() =
        runTest {
            val bytes = "revision A".toByteArray()
            val descriptor = descriptor("small", bytes)
            val catalog = MutableCatalog(descriptor, bytes)
            val installed = ConcurrentInstalledModels()
            val directory =
                kotlin.io.path
                    .createTempDirectory("reinstall-model")
                    .toFile()
            val immediateScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val modelMutex = Mutex()
            val transfers = AtomicInteger()
            val manager =
                manager(
                    catalog = catalog,
                    installedModels = installed,
                    modelDirectory = directory,
                    executionScope = immediateScope,
                    modelMutex = modelMutex,
                    ioDispatcher = Dispatchers.Unconfined,
                ) { _, _ ->
                    transfers.incrementAndGet()
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }
            val store = InstalledModelFileStore(directory, installed, modelMutex) { false }

            manager.enqueue(descriptor.id)
            val firstFile = File(installed.snapshot().single().filePath)
            store.delete(descriptor.id)
            assertFalse(firstFile.exists())
            assertEquals(emptyList<InstalledModel>(), installed.snapshot())
            manager.enqueue(descriptor.id)

            assertEquals(2, transfers.get())
            assertEquals(descriptor.sha256, installed.snapshot().single().sha256)
            assertEquals(true, File(installed.snapshot().single().filePath).isFile)
            assertEquals(true, firstFile.isFile)
            immediateScope.cancel()
        }

    @Test
    fun `blocked reuse probe does not delay cancellation of another model`() =
        runTest {
            val largeBytes = "installed large model".toByteArray()
            val smallBytes = "active small model".toByteArray()
            val large = descriptor("large", largeBytes)
            val small = descriptor("small", smallBytes)
            val directory =
                kotlin.io.path
                    .createTempDirectory("blocked-reuse")
                    .toFile()
            val installedFile =
                File(directory, "${large.id}-${large.sha256}.bin").apply {
                    writeBytes(largeBytes)
                }
            val installed = GatedReuseRepository(installed(large, installedFile))
            val responseEntered = CompletableDeferred<Unit>()
            val responseRelease = CompletableDeferred<Unit>()
            val manager =
                manager(
                    catalog = MapCatalog(mapOf(large.id to large, small.id to small)),
                    installedModels = installed,
                    modelDirectory = directory,
                    ioDispatcher = Dispatchers.IO,
                ) { url, _ ->
                    if (url.endsWith("small.bin")) {
                        BlockingNetworkResponse(smallBytes, responseEntered, responseRelease)
                    } else {
                        error("Unexpected transfer for $url")
                    }
                }

            manager.enqueue(small.id)
            responseEntered.await()
            manager.enqueue(large.id)
            runCurrent()
            installed.probeEntered.await()

            val cancellation = async { manager.cancel(small.id) }
            runCurrent()

            assertTrue(cancellation.isCompleted)
            cancellation.await()
            installed.probeRelease.complete(Unit)
            responseRelease.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `delete queued before reuse cannot publish a missing model as complete`() =
        runTest {
            val bytes = "installed revision".toByteArray()
            val descriptor = descriptor("small", bytes)
            val directory =
                kotlin.io.path
                    .createTempDirectory("delete-reuse-race")
                    .toFile()
            val file =
                File(directory, "${descriptor.id}-${descriptor.sha256}.bin").apply {
                    writeBytes(bytes)
                }
            val installed = ConcurrentInstalledModels()
            installed.register(installed(descriptor, file))
            val modelMutex = Mutex(locked = true)
            val transfers = AtomicInteger()
            val manager =
                manager(
                    catalog = MapCatalog(mapOf(descriptor.id to descriptor)),
                    installedModels = installed,
                    modelDirectory = directory,
                    modelMutex = modelMutex,
                    ioDispatcher = Dispatchers.Unconfined,
                ) { _, _ ->
                    transfers.incrementAndGet()
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }
            val store = InstalledModelFileStore(directory, installed, modelMutex) { false }

            val deletion = async { store.delete(descriptor.id) }
            runCurrent()
            manager.enqueue(descriptor.id)
            runCurrent()
            modelMutex.unlock()
            advanceUntilIdle()
            deletion.await()

            val restored = installed.snapshot().single()
            assertEquals(1, transfers.get())
            assertTrue(File(restored.filePath).isFile)
            assertEquals(
                ModelDownloadStatus.COMPLETED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `cancel before a reserved job starts allows the next enqueue to own the transfer`() =
        runTest {
            val bytes = "cancel before start".toByteArray()
            val descriptor = descriptor("small", bytes)
            val transfers = AtomicInteger()
            val manager =
                manager(MapCatalog(mapOf(descriptor.id to descriptor))) { _, _ ->
                    transfers.incrementAndGet()
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }

            manager.enqueue(descriptor.id)
            manager.cancel(descriptor.id)
            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(1, transfers.get())
            assertEquals(
                ModelDownloadStatus.COMPLETED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `cancelled former owner cannot overwrite its completed replacement`() =
        runTest {
            val bytes = "replacement owner".toByteArray()
            val descriptor = descriptor("small", bytes)
            val ownershipClaimEntered = CompletableDeferred<Unit>()
            val ownershipClaimRelease = CompletableDeferred<Unit>()
            val ownershipClaims = AtomicInteger()
            val transfers = AtomicInteger()
            val manager =
                manager(
                    catalog = MapCatalog(mapOf(descriptor.id to descriptor)),
                    beforeOwnershipClaim = {
                        if (ownershipClaims.incrementAndGet() == 1) {
                            ownershipClaimEntered.complete(Unit)
                            withContext(NonCancellable) { ownershipClaimRelease.await() }
                        }
                    },
                ) { _, _ ->
                    transfers.incrementAndGet()
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }

            manager.enqueue(descriptor.id)
            runCurrent()
            ownershipClaimEntered.await()
            manager.cancel(descriptor.id)
            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(
                ModelDownloadStatus.COMPLETED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
            ownershipClaimRelease.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, transfers.get())
            assertEquals(
                ModelDownloadStatus.COMPLETED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `reuse publishes completed before a queued deletion can remove the revision`() =
        runTest {
            val bytes = "reuse before delete".toByteArray()
            val descriptor = descriptor("small", bytes)
            val directory =
                kotlin.io.path
                    .createTempDirectory("reuse-publication-order")
                    .toFile()
            val file =
                File(directory, "${descriptor.id}-${descriptor.sha256}.bin").apply {
                    writeBytes(bytes)
                }
            val installed = GatedReuseRepository(installed(descriptor, file))
            val modelMutex = GateAfterFirstUnlockMutex()
            val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manager =
                manager(
                    catalog = MapCatalog(mapOf(descriptor.id to descriptor)),
                    installedModels = installed,
                    modelDirectory = directory,
                    executionScope = executionScope,
                    modelMutex = modelMutex,
                    ioDispatcher = Dispatchers.Unconfined,
                ) { _, _ ->
                    error("A verified revision must not download")
                }
            val store = InstalledModelFileStore(directory, installed, modelMutex) { false }

            manager.enqueue(descriptor.id)
            installed.probeEntered.await()
            val deletion = async(Dispatchers.Default) { store.delete(descriptor.id) }
            installed.probeRelease.complete(Unit)
            modelMutex.firstUnlockEntered.await()

            assertEquals(
                ModelDownloadStatus.COMPLETED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
            deletion.await()
            modelMutex.firstUnlockRelease.complete(Unit)
            executionScope.cancel()
        }

    private fun kotlinx.coroutines.test.TestScope.manager(
        catalog: ModelCatalogRepository,
        installedModels: InstalledModelRepository = ConcurrentInstalledModels(),
        modelDirectory: File =
            kotlin.io.path
                .createTempDirectory("concurrent-models")
                .toFile(),
        executionScope: CoroutineScope = this,
        modelMutex: Mutex = Mutex(),
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            StandardTestDispatcher(testScheduler),
        beforeOwnershipClaim: suspend () -> Unit = {},
        client: io.github.surioustype.localscribe.network.NetworkClient,
    ) = SecureModelDownloadManager(
        catalog = catalog,
        installedModels = installedModels,
        modelDirectory = modelDirectory,
        networkClient = client,
        scope = executionScope,
        nowEpochMs = { 1L },
        modelMutex = modelMutex,
        ioDispatcher = ioDispatcher,
        beforeOwnershipClaim = beforeOwnershipClaim,
    )

    private fun descriptor(id: String, bytes: ByteArray) =
        ModelDescriptor(
            id = id,
            displayName = id,
            version = "main",
            downloadUrl = "https://example.com/$id.bin",
            sha256 = bytes.sha256(),
            downloadBytes = bytes.size.toLong(),
            installedBytes = bytes.size.toLong(),
            languages = setOf("multilingual"),
            quality = ModelQuality.LOW,
            kind = if (id == "vad") ModelKind.VAD else ModelKind.TRANSCRIPTION,
        )

    private class GatedCatalog(
        private val models: Map<String, ModelDescriptor>,
        private val expectedLookups: Int,
    ) : ModelCatalogRepository {
        private val arrivals = AtomicInteger()
        private val gate = CompletableDeferred<Unit>()

        override fun observeCatalog(): Flow<List<ModelDescriptor>> =
            MutableStateFlow(
                models.values.toList(),
            )

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun getModel(modelId: String): ModelDescriptor? {
            if (arrivals.incrementAndGet() == expectedLookups) gate.complete(Unit)
            gate.await()
            return models[modelId]
        }
    }

    private class MutableCatalog(
        initial: ModelDescriptor,
        initialBytes: ByteArray,
    ) : ModelCatalogRepository {
        var current: ModelDescriptor = initial
            private set
        var currentBytes: ByteArray = initialBytes
            private set

        fun replace(descriptor: ModelDescriptor, bytes: ByteArray) {
            current = descriptor
            currentBytes = bytes
        }

        override fun observeCatalog(): Flow<List<ModelDescriptor>> =
            MutableStateFlow(
                listOf(current),
            )

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun getModel(modelId: String): ModelDescriptor? =
            current.takeIf {
                it.id ==
                    modelId
            }
    }

    private class MapCatalog(
        private val models: Map<String, ModelDescriptor>,
    ) : ModelCatalogRepository {
        override fun observeCatalog(): Flow<List<ModelDescriptor>> =
            MutableStateFlow(models.values.toList())

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun getModel(modelId: String): ModelDescriptor? = models[modelId]
    }

    private class ConcurrentInstalledModels : InstalledModelRepository {
        private val models = ConcurrentHashMap<String, InstalledModel>()

        override fun observeInstalledModels(): Flow<List<InstalledModel>> =
            MutableStateFlow(
                models.values.toList(),
            )

        override suspend fun getInstalledModel(modelId: String): InstalledModel? =
            models.values
                .filter {
                    it.descriptorId == modelId
                }.maxByOrNull { it.installedAtEpochMs }

        override suspend fun register(model: InstalledModel) {
            models[model.id] = model
        }

        override suspend fun remove(modelId: String) {
            models.entries.removeIf { it.value.descriptorId == modelId }
        }

        fun snapshot(): List<InstalledModel> = models.values.toList()
    }

    private class GatedReuseRepository(
        private val installed: InstalledModel,
    ) : InstalledModelRepository {
        val probeEntered = CompletableDeferred<Unit>()
        val probeRelease = CompletableDeferred<Unit>()

        override fun observeInstalledModels(): Flow<List<InstalledModel>> =
            MutableStateFlow(listOf(installed))

        override suspend fun getInstalledModel(modelId: String): InstalledModel? =
            installed.takeIf { it.descriptorId == modelId }

        override suspend fun getInstalledModel(modelId: String, sha256: String): InstalledModel? {
            if (modelId == installed.descriptorId && sha256 == installed.sha256) {
                probeEntered.complete(Unit)
                probeRelease.await()
                return installed
            }
            return null
        }

        override suspend fun register(model: InstalledModel) = Unit

        override suspend fun remove(modelId: String) = Unit
    }

    private fun installed(descriptor: ModelDescriptor, file: File) =
        InstalledModel(
            id = "${descriptor.id}-${descriptor.sha256}",
            descriptorId = descriptor.id,
            displayName = descriptor.displayName,
            filePath = file.path,
            sha256 = descriptor.sha256,
            bytes = file.length(),
            installedAtEpochMs = 1L,
            verifiedAtEpochMs = 1L,
        )

    private class BlockingNetworkResponse(
        bytes: ByteArray,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : io.github.surioustype.localscribe.network.NetworkResponse {
        override val statusCode: Int = 200
        override val headers: Map<String, String> = emptyMap()
        override val body: java.io.InputStream =
            object : java.io.ByteArrayInputStream(bytes) {
                private var first = true

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (first) {
                        first = false
                        entered.complete(Unit)
                        kotlinx.coroutines.runBlocking { release.await() }
                    }
                    return super.read(buffer, offset, minOf(length, 1))
                }
            }

        override fun close() = body.close()
    }

    private class GateAfterFirstUnlockMutex(
        private val delegate: Mutex = Mutex(),
    ) : Mutex by delegate {
        val firstUnlockEntered = CompletableDeferred<Unit>()
        val firstUnlockRelease = CompletableDeferred<Unit>()
        private val unlockCount = AtomicInteger()

        override fun unlock(owner: Any?) {
            delegate.unlock(owner)
            if (unlockCount.incrementAndGet() == 1) {
                firstUnlockEntered.complete(Unit)
                runBlocking { firstUnlockRelease.await() }
            }
        }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
