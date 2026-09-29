package io.github.surioustype.localscribe.models

import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelDownloadStatus
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.ModelQuality
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import io.github.surioustype.localscribe.core.ports.ModelCatalogRepository
import io.github.surioustype.localscribe.network.ByteArrayNetworkResponse
import io.github.surioustype.localscribe.network.NetworkClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloadManagerTest {
    @Test
    fun `cancellation during durable publication keeps registered model and completed state`() =
        runTest {
            val bytes = "verified model".toByteArray()
            val descriptor = descriptor(bytes)
            val installed = FakeInstalledRepository().apply { blockRegister = true }
            val directory = tempDirectory()
            val manager =
                manager(descriptor, installed, directory) { _, _ ->
                    ByteArrayNetworkResponse(
                        200,
                        mapOf("Content-Length" to bytes.size.toString()),
                        bytes,
                    )
                }

            manager.enqueue(descriptor.id)
            installed.registerStarted.await()
            manager.cancel(descriptor.id)
            installed.releaseRegister.complete(Unit)
            advanceUntilIdle()

            val published = requireNotNull(installed.model)
            assertTrue(File(published.filePath).exists())
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
    fun `registration failure rolls back moved revision and publishes failure`() =
        runTest {
            val bytes = "verified model".toByteArray()
            val descriptor = descriptor(bytes)
            val installed = FakeInstalledRepository().apply { throwOnRegister = true }
            val directory = tempDirectory()
            val manager =
                manager(descriptor, installed, directory) { _, _ ->
                    ByteArrayNetworkResponse(200, bytes = bytes)
                }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(null, installed.model)
            assertFalse(File(directory, "${descriptor.id}-${descriptor.sha256}.bin").exists())
            assertEquals(
                ModelDownloadStatus.FAILED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `terminal state publication failure rolls back only the new revision`() =
        runTest {
            val bytes = "verified model".toByteArray()
            val descriptor = descriptor(bytes)
            val directory = tempDirectory()
            val olderFile = File(directory, "tiny-older.bin").apply { writeText("older") }
            val installed =
                FakeInstalledRepository().apply {
                    models +=
                        InstalledModel(
                            id = "older",
                            descriptorId = descriptor.id,
                            displayName = descriptor.displayName,
                            filePath = olderFile.path,
                            sha256 = "older-hash",
                            bytes = olderFile.length(),
                            installedAtEpochMs = 1,
                            verifiedAtEpochMs = 1,
                        )
                }
            val manager =
                manager(
                    descriptor,
                    installed,
                    directory,
                    beforeTerminalUpdate = {
                        throw IllegalStateException("terminal update failed")
                    },
                ) { _, _ -> ByteArrayNetworkResponse(200, bytes = bytes) }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(listOf("older"), installed.models.map { it.id })
            assertTrue(olderFile.exists())
            assertFalse(File(directory, "${descriptor.id}-${descriptor.sha256}.bin").exists())
            assertEquals(
                ModelDownloadStatus.FAILED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `downloads verifies and atomically publishes immutable model`() =
        runTest {
            val bytes = "verified model".toByteArray()
            val descriptor = descriptor(bytes)
            val installed = FakeInstalledRepository()
            val directory = tempDirectory()
            val manager =
                manager(descriptor, installed, directory) { _, _ ->
                    ByteArrayNetworkResponse(
                        200,
                        mapOf("Content-Length" to bytes.size.toString()),
                        bytes,
                    )
                }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            val result = installed.model
            assertNotNull(result)
            assertArrayEquals(bytes, File(requireNotNull(result).filePath).readBytes())
            assertTrue(File(result.filePath).name.contains(descriptor.sha256))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
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
    fun `valid resume requires matching content range`() =
        runTest {
            val bytes = "resumable model data".toByteArray()
            val descriptor = descriptor(bytes)
            val directory = tempDirectory()
            File(
                directory,
                "${descriptor.id}-${descriptor.sha256}.part",
            ).writeBytes(bytes.copyOfRange(0, 5))
            val manager =
                manager(descriptor, FakeInstalledRepository(), directory) { _, headers ->
                    assertEquals("bytes=5-", headers["Range"])
                    ByteArrayNetworkResponse(
                        206,
                        mapOf(
                            "Content-Range" to "bytes 5-${bytes.lastIndex}/${bytes.size}",
                        ),
                        bytes.copyOfRange(5, bytes.size),
                    )
                }

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
        }

    @Test
    fun `range ignored with 200 truncates partial before restart`() =
        runTest {
            val bytes = "fresh model".toByteArray()
            val descriptor = descriptor(bytes)
            val directory = tempDirectory()
            File(directory, "${descriptor.id}-${descriptor.sha256}.part").writeText("stale")
            val installed = FakeInstalledRepository()
            val manager =
                manager(
                    descriptor,
                    installed,
                    directory,
                ) { _, _ -> ByteArrayNetworkResponse(200, bytes = bytes) }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertArrayEquals(bytes, File(requireNotNull(installed.model).filePath).readBytes())
        }

    @Test
    fun `malformed partial response is rejected without publishing`() =
        runTest {
            val bytes = "resumable model data".toByteArray()
            val descriptor = descriptor(bytes)
            val directory = tempDirectory()
            File(
                directory,
                "${descriptor.id}-${descriptor.sha256}.part",
            ).writeBytes(bytes.copyOfRange(0, 5))
            val installed = FakeInstalledRepository()
            val manager =
                manager(descriptor, installed, directory) { _, _ ->
                    ByteArrayNetworkResponse(
                        206,
                        mapOf(
                            "Content-Range" to "bytes 4-${bytes.lastIndex}/${bytes.size}",
                        ),
                        bytes.copyOfRange(5, bytes.size),
                    )
                }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(null, installed.model)
            assertEquals(
                ModelDownloadStatus.FAILED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `truncation and checksum mismatch never register model`() =
        runTest {
            val expected = "complete".toByteArray()
            val descriptor = descriptor(expected)
            val installed = FakeInstalledRepository()
            val manager =
                manager(
                    descriptor,
                    installed,
                    tempDirectory(),
                ) { _, _ -> ByteArrayNetworkResponse(200, bytes = "wrong".toByteArray()) }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(null, installed.model)
            assertEquals(
                ModelDownloadStatus.FAILED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `same size checksum mismatch never registers model`() =
        runTest {
            val expected = "complete".toByteArray()
            val descriptor = descriptor(expected)
            val installed = FakeInstalledRepository()
            val manager =
                manager(descriptor, installed, tempDirectory()) { _, _ ->
                    ByteArrayNetworkResponse(200, bytes = "corrupt!".toByteArray())
                }

            manager.enqueue(descriptor.id)
            advanceUntilIdle()

            assertEquals(null, installed.model)
            assertEquals(
                ModelDownloadStatus.FAILED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
        }

    @Test
    fun `cancellation closes stream and retains resumable partial`() =
        runTest {
            val bytes = ByteArray(1024) { it.toByte() }
            val descriptor = descriptor(bytes)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val response = BlockingNetworkResponse(bytes, entered, release)
            val directory = tempDirectory()
            val manager =
                manager(
                    descriptor,
                    FakeInstalledRepository(),
                    directory,
                    ioDispatcher = Dispatchers.IO,
                ) { _, _ -> response }

            manager.enqueue(descriptor.id)
            entered.await()
            manager.cancel(descriptor.id)
            release.complete(Unit)
            manager.observeDownloads().first { it.single().status == ModelDownloadStatus.CANCELLED }

            assertEquals(
                ModelDownloadStatus.CANCELLED,
                manager
                    .observeDownloads()
                    .first()
                    .single()
                    .status,
            )
            assertTrue(response.closed)
            assertTrue(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        }

    @Test
    fun `deletion waits for shared execution mutex and rejects active model`() =
        runTest {
            val bytes = "model".toByteArray()
            val descriptor = descriptor(bytes)
            val directory = tempDirectory()
            val file =
                File(
                    directory,
                    "${descriptor.id}-${descriptor.sha256}.bin",
                ).apply { writeBytes(bytes) }
            val installed = FakeInstalledRepository().apply { model = installed(descriptor, file) }
            val mutex = Mutex(locked = true)
            val store = InstalledModelFileStore(directory, installed, mutex) { true }

            val result = async { runCatching { store.delete(descriptor.id) } }
            assertFalse(result.isCompleted)
            mutex.unlock()
            advanceUntilIdle()

            assertTrue(result.await().isFailure)
            assertTrue(file.exists())
        }

    @Test
    fun `deletion holds shared execution mutex through file and metadata removal`() =
        runTest {
            val bytes = "model".toByteArray()
            val descriptor = descriptor(bytes)
            val directory = tempDirectory()
            val file =
                File(
                    directory,
                    "${descriptor.id}-${descriptor.sha256}.bin",
                ).apply { writeBytes(bytes) }
            val installed = FakeInstalledRepository().apply { model = installed(descriptor, file) }
            val mutex = Mutex(locked = true)
            val store = InstalledModelFileStore(directory, installed, mutex) { false }

            val deletion = async { store.delete(descriptor.id) }
            assertFalse(deletion.isCompleted)
            assertTrue(file.exists())
            assertNotNull(installed.model)
            mutex.unlock()
            deletion.await()

            assertFalse(file.exists())
            assertEquals(null, installed.model)
        }

    @Test
    fun `descriptor deletion removes every retained revision before metadata`() =
        runTest {
            val directory = tempDirectory()
            val firstFile = File(directory, "tiny-first.bin").apply { writeText("first") }
            val secondFile = File(directory, "tiny-second.bin").apply { writeText("second") }
            val installed =
                FakeInstalledRepository().apply {
                    models +=
                        InstalledModel(
                            "first",
                            "tiny",
                            "Tiny",
                            firstFile.path,
                            "first",
                            firstFile.length(),
                            1,
                            1,
                        )
                    models +=
                        InstalledModel(
                            "second",
                            "tiny",
                            "Tiny",
                            secondFile.path,
                            "second",
                            secondFile.length(),
                            2,
                            2,
                        )
                }
            val store = InstalledModelFileStore(directory, installed, Mutex()) { false }

            store.delete("tiny")

            assertFalse(firstFile.exists())
            assertFalse(secondFile.exists())
            assertTrue(installed.models.isEmpty())
        }

    private fun kotlinx.coroutines.test.TestScope.manager(
        descriptor: ModelDescriptor,
        installed: FakeInstalledRepository,
        directory: File,
        modelMutex: Mutex = Mutex(),
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        beforeTerminalUpdate: suspend () -> Unit = {},
        client: NetworkClient,
    ) = SecureModelDownloadManager(
        catalog = FakeCatalog(descriptor),
        installedModels = installed,
        modelDirectory = directory,
        networkClient = client,
        scope = this,
        nowEpochMs = { 10L },
        modelMutex = modelMutex,
        ioDispatcher = ioDispatcher,
        beforeTerminalUpdate = beforeTerminalUpdate,
    )

    private fun descriptor(bytes: ByteArray) =
        ModelDescriptor(
            id = "tiny",
            displayName = "Tiny",
            version = "main",
            downloadUrl = "https://example.com/tiny.bin",
            sha256 = bytes.sha256(),
            downloadBytes = bytes.size.toLong(),
            installedBytes = bytes.size.toLong(),
            languages = setOf("multilingual"),
            quality = ModelQuality.LOW,
            kind = ModelKind.TRANSCRIPTION,
        )

    private fun tempDirectory(): File =
        kotlin.io.path
            .createTempDirectory("models-test")
            .toFile()

    private class FakeCatalog(private val descriptor: ModelDescriptor) : ModelCatalogRepository {
        override fun observeCatalog(): Flow<List<ModelDescriptor>> =
            MutableStateFlow(
                listOf(descriptor),
            )

        override suspend fun refresh(force: Boolean) = Unit

        override suspend fun getModel(modelId: String): ModelDescriptor? =
            descriptor.takeIf {
                it.id ==
                    modelId
            }
    }

    private class FakeInstalledRepository : InstalledModelRepository {
        val models = mutableListOf<InstalledModel>()
        var blockRegister = false
        var throwOnRegister = false
        val registerStarted = CompletableDeferred<Unit>()
        val releaseRegister = CompletableDeferred<Unit>()
        var model: InstalledModel?
            get() = models.singleOrNull()
            set(value) {
                models.clear()
                if (value != null) models += value
            }

        override fun observeInstalledModels(): Flow<List<InstalledModel>> =
            MutableStateFlow(
                models.toList(),
            )

        override suspend fun getInstalledModel(modelId: String): InstalledModel? =
            models.filter { it.descriptorId == modelId }.maxByOrNull { it.installedAtEpochMs }

        override suspend fun register(model: InstalledModel) {
            if (throwOnRegister) throw IllegalStateException("register failed")
            if (blockRegister) {
                registerStarted.complete(Unit)
                releaseRegister.await()
            }
            models.removeAll { it.id == model.id }
            models += model
        }

        override suspend fun remove(model: InstalledModel) {
            models.removeAll { it.id == model.id }
        }

        override suspend fun remove(modelId: String) {
            models.removeAll { it.descriptorId == modelId }
        }
    }

    private fun installed(descriptor: ModelDescriptor, file: File) =
        InstalledModel(
            "installed",
            descriptor.id,
            descriptor.displayName,
            file.path,
            descriptor.sha256,
            file.length(),
            1,
            1,
        )

    private fun ByteArray.sha256(): String =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(this)
            .joinToString("") {
                "%02x".format(it)
            }

    private class BlockingNetworkResponse(
        bytes: ByteArray,
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : io.github.surioustype.localscribe.network.NetworkResponse {
        var closed = false
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

        override fun close() {
            closed = true
            body.close()
        }
    }
}
