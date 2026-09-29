package io.github.surioustype.localscribe.execution

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.EngineSegment
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.RecoverySummary
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.core.model.TranscriptionResult
import io.github.surioustype.localscribe.core.ports.AudioPipeline
import io.github.surioustype.localscribe.core.ports.AudioRepository
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import io.github.surioustype.localscribe.core.ports.TranscriptionEngine
import io.github.surioustype.localscribe.engine.WhisperEngineException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranscriptionCoordinatorTest {
    @Test
    fun `loads one model and stores engine timestamps as absolute exactly once`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks())
            val engine = FakeEngine()
            val coordinator = coordinator(repository, engine)

            val outcome = coordinator.execute(JOB_ID)

            assertEquals(ExecutionOutcome.Completed, outcome)
            assertEquals(1, engine.loadCount)
            assertEquals(1, engine.unloadCount)
            assertEquals(listOf(null, "first"), engine.prompts)
            assertEquals(
                listOf(
                    TranscriptSegment("chunk-1:0", "chunk-1", 100, 400, "first"),
                    TranscriptSegment("chunk-2:0", "chunk-2", 90_100, 90_400, "first"),
                ),
                repository.segments,
            )
            assertEquals(JobStatus.COMPLETED, repository.currentJob.status)
        }

    @Test
    fun `pause racing native completion cannot commit the in-flight chunk`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1))
            val engine = FakeEngine(blockTranscription = true)
            val coordinator = coordinator(repository, engine)
            val execution = async { coordinator.execute(JOB_ID) }
            engine.started.await()

            coordinator.pause(JOB_ID, 50)
            runCurrent()

            assertEquals(ExecutionOutcome.Paused, execution.await())
            assertEquals(JobStatus.PAUSED, repository.currentJob.status)
            assertTrue(repository.segments.isEmpty())
            assertEquals(ChunkStatus.PAUSED, repository.currentChunks.single().status)
            assertEquals(1, engine.unloadCount)
        }

    @Test
    fun `moderate heat reduces threads for future chunks`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1))
            val engine = FakeEngine()
            val coordinator =
                coordinator(
                    repository,
                    engine,
                    ThermalState { ThermalSnapshot(ThermalSeverity.MODERATE) },
                )

            assertEquals(ExecutionOutcome.Completed, coordinator.execute(JOB_ID))
            assertEquals(listOf(2), engine.configs.map { it.threadCount })
        }

    @Test
    fun `severe heat persists an actionable pause before decoding`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1))
            val engine = FakeEngine()
            val coordinator =
                coordinator(
                    repository,
                    engine,
                    ThermalState { ThermalSnapshot(ThermalSeverity.SEVERE) },
                )

            assertEquals(ExecutionOutcome.Paused, coordinator.execute(JOB_ID))
            assertEquals(JobStatus.PAUSED, repository.currentJob.status)
            assertEquals(FailureCode.THERMAL_CRITICAL, repository.currentJob.failure?.code)
            assertTrue(engine.configs.isEmpty())
            assertEquals(1, engine.unloadCount)
        }

    @Test
    fun `pause before execution is a safe no-op`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1))
            val coordinator = coordinator(repository, FakeEngine())

            coordinator.pause(JOB_ID, 2)

            assertEquals(JobStatus.PENDING, repository.currentJob.status)
            assertEquals(ChunkStatus.PENDING, repository.currentChunks.single().status)
        }

    @Test
    fun `pause after start is retained while initial job read is suspended`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1), blockFirstGet = true)
            val engine = FakeEngine()
            val coordinator = coordinator(repository, engine)
            val execution = async { coordinator.execute(JOB_ID) }
            repository.firstGetStarted.await()

            coordinator.pause(JOB_ID, 2)
            repository.releaseFirstGet.complete(Unit)

            assertEquals(ExecutionOutcome.Paused, execution.await())
            assertEquals(JobStatus.PENDING, repository.currentJob.status)
            assertEquals(ChunkStatus.PENDING, repository.currentChunks.single().status)
            assertEquals(0, engine.loadCount)
        }

    @Test
    fun `pause after start is retained while execution mutex is held`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1))
            val engine = FakeEngine()
            val executionMutex = Mutex(locked = true)
            val coordinator = coordinator(repository, engine, executionMutex = executionMutex)
            val execution = async { coordinator.execute(JOB_ID) }
            runCurrent()

            coordinator.pause(JOB_ID, 2)
            executionMutex.unlock()

            assertEquals(ExecutionOutcome.Paused, execution.await())
            assertEquals(JobStatus.PENDING, repository.currentJob.status)
            assertEquals(ChunkStatus.PENDING, repository.currentChunks.single().status)
            assertEquals(0, engine.loadCount)
        }

    @Test
    fun `external cancellation durably pauses the owned running session`() =
        runTest {
            val repository = FakeDurableRepository(job(), chunks().take(1))
            val engine = FakeEngine(blockTranscription = true)
            val coordinator = coordinator(repository, engine)
            val execution = async { coordinator.execute(JOB_ID) }
            engine.started.await()

            execution.cancelAndJoin()

            assertEquals(JobStatus.PAUSED, repository.currentJob.status)
            assertEquals(ChunkStatus.PAUSED, repository.currentChunks.single().status)
            assertEquals(FailureCode.TIMEOUT, repository.currentJob.failure?.code)
            assertEquals(1, engine.unloadCount)
        }

    @Test
    fun `missing source pauses and retains completed checkpoints`() =
        runTest {
            val completed = chunks().first().copy(status = ChunkStatus.COMPLETED)
            val repository = FakeDurableRepository(job(), listOf(completed, chunks().last()))
            val audio = FakeAudioRepository().apply { available = false }
            val coordinator = coordinator(repository, FakeEngine(), audioRepository = audio)

            assertEquals(ExecutionOutcome.Paused, coordinator.execute(JOB_ID))
            assertEquals(JobStatus.PAUSED, repository.currentJob.status)
            assertEquals(FailureCode.SOURCE_MISSING, repository.currentJob.failure?.code)
            assertEquals(ChunkStatus.COMPLETED, repository.currentChunks.first().status)
            assertEquals(ChunkStatus.PAUSED, repository.currentChunks.last().status)
        }

    @Test
    fun `permission loss while reading pauses and resumes only unfinished chunks`() =
        runTest {
            val completed = chunks().first().copy(status = ChunkStatus.COMPLETED)
            val repository = FakeDurableRepository(job(), listOf(completed, chunks().last()))
            val audioPipeline = FakeAudioPipeline().apply { readFailure = SecurityException() }
            val coordinator = coordinator(repository, FakeEngine(), audioPipeline = audioPipeline)

            assertEquals(ExecutionOutcome.Paused, coordinator.execute(JOB_ID))
            assertEquals(JobStatus.PAUSED, repository.currentJob.status)
            assertEquals(
                FailureCode.SOURCE_PERMISSION_REQUIRED,
                repository.currentJob.failure?.code,
            )
            assertEquals(ChunkStatus.COMPLETED, repository.currentChunks.first().status)
            assertEquals(ChunkStatus.PAUSED, repository.currentChunks.last().status)

            audioPipeline.readFailure = null

            assertEquals(ExecutionOutcome.Completed, coordinator.execute(JOB_ID))
            assertEquals(JobStatus.COMPLETED, repository.currentJob.status)
            assertEquals(listOf("chunk-2"), audioPipeline.readChunkIds)
        }

    @Test
    fun `changed source fingerprint restarts instead of mixing completed checkpoints`() =
        runTest {
            val completed = chunks().first().copy(status = ChunkStatus.COMPLETED)
            val repository = FakeDurableRepository(job(), listOf(completed, chunks().last()))
            val audio = FakeAudioRepository().apply { verifiedFingerprint = "changed" }
            val pipeline = FakeAudioPipeline()
            val coordinator =
                coordinator(
                    repository,
                    FakeEngine(),
                    audioRepository = audio,
                    audioPipeline = pipeline,
                )

            assertEquals(ExecutionOutcome.Completed, coordinator.execute(JOB_ID))
            assertEquals("changed", repository.currentJob.sourceFingerprint)
            assertEquals(listOf("chunk-1", "chunk-2"), pipeline.readChunkIds)
            assertEquals(listOf(1, 1), repository.currentChunks.map { it.attempt })
        }

    @Test
    fun `corrupt pinned model during load pauses and resumes only unfinished chunks`() =
        runTest {
            val completed = chunks().first().copy(status = ChunkStatus.COMPLETED)
            val repository = FakeDurableRepository(job(), listOf(completed, chunks().last()))
            val engine =
                FakeEngine().apply {
                    loadFailure =
                        WhisperEngineException(
                            DomainFailure(FailureCode.MODEL_CORRUPTED, "model_corrupted"),
                            "model_corrupted",
                        )
                }
            val coordinator = coordinator(repository, engine)

            assertEquals(ExecutionOutcome.Paused, coordinator.execute(JOB_ID))
            assertEquals(JobStatus.PAUSED, repository.currentJob.status)
            assertEquals(FailureCode.MODEL_CORRUPTED, repository.currentJob.failure?.code)
            assertEquals(ChunkStatus.COMPLETED, repository.currentChunks.first().status)
            assertEquals(ChunkStatus.PAUSED, repository.currentChunks.last().status)

            engine.loadFailure = null

            assertEquals(ExecutionOutcome.Completed, coordinator.execute(JOB_ID))
            assertEquals(JobStatus.COMPLETED, repository.currentJob.status)
            assertEquals(1, engine.configs.size)
        }

    @Test
    fun `late stop for completed job is harmless`() =
        runTest {
            val repository =
                FakeDurableRepository(job().copy(status = JobStatus.COMPLETED), chunks())
            val coordinator = coordinator(repository, FakeEngine())

            coordinator.cancel(JOB_ID)

            assertEquals(JobStatus.COMPLETED, repository.currentJob.status)
        }

    private fun coordinator(
        repository: FakeDurableRepository,
        engine: FakeEngine,
        thermalState: ThermalState = ThermalState.None,
        executionMutex: Mutex = Mutex(),
        audioRepository: AudioRepository = FakeAudioRepository(),
        audioPipeline: AudioPipeline = FakeAudioPipeline(),
    ) = TranscriptionCoordinator(
        repository = repository,
        audioRepository = audioRepository,
        installedModels = FakeInstalledModels(),
        audioPipeline = audioPipeline,
        engine = engine,
        clock =
            object : ExecutionClock {
                private var now = 10L

                override fun nowEpochMs(): Long = now++
            },
        thermalState = thermalState,
        executionMutex = executionMutex,
    )

    private fun job() =
        TranscriptionJob(
            id = JOB_ID,
            sourceId = SOURCE_ID,
            config =
                TranscriptionConfig(
                    modelId = MODEL_ID,
                    inference = InferenceConfig(threadCount = 4),
                    contextMaxCharacters = 100,
                ),
            modelHash = MODEL_HASH,
            status = JobStatus.PENDING,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            sourceFingerprint = "original",
        )

    private fun chunks() =
        listOf(
            TranscriptionChunk("chunk-1", JOB_ID, MODEL_ID, 0, 90_000, ChunkStatus.PENDING, 0, 1),
            TranscriptionChunk(
                "chunk-2",
                JOB_ID,
                MODEL_ID,
                90_000,
                180_000,
                ChunkStatus.PENDING,
                0,
                1,
            ),
        )

    private class FakeEngine(
        private val blockTranscription: Boolean = false,
    ) : TranscriptionEngine {
        var loadCount = 0
        var unloadCount = 0
        val prompts = mutableListOf<String?>()
        val configs = mutableListOf<InferenceConfig>()
        val started = CompletableDeferred<Unit>()
        var loadFailure: Throwable? = null

        override suspend fun loadModel(model: InstalledModel, vadModel: InstalledModel?) {
            loadFailure?.let { throw it }
            loadCount++
        }

        override suspend fun transcribe(
            pcm: FloatArray,
            config: InferenceConfig,
            prompt: String?,
            cancellationSignal: CancellationSignal,
        ): TranscriptionResult {
            prompts += prompt
            configs += config
            started.complete(Unit)
            while (blockTranscription && !cancellationSignal.isCancellationRequested()) {
                kotlinx.coroutines.yield()
            }
            if (cancellationSignal.isCancellationRequested()) {
                throw kotlinx.coroutines.CancellationException("controlled")
            }
            return TranscriptionResult(listOf(EngineSegment(100, 400, "first")))
        }

        override suspend fun unloadModel() {
            unloadCount++
        }
    }

    private class FakeAudioPipeline : AudioPipeline {
        var readFailure: Throwable? = null
        val readChunkIds = mutableListOf<String>()

        override suspend fun readWindow(
            source: AudioSource,
            startMs: Long,
            endMs: Long,
        ): FloatArray {
            readFailure?.let { throw it }
            readChunkIds += if (startMs == 90_000L) "chunk-2" else "chunk-1"
            return FloatArray(((endMs - startMs) / 10).toInt())
        }
    }

    private class FakeAudioRepository : AudioRepository {
        var available = true
        var verifiedFingerprint = "original"
        private val record =
            AudioSourceRecord(
                AudioSource(SOURCE_ID, "content://source", "Lecture", 180_000, "audio/wav"),
                SourceAccessStatus.AVAILABLE,
                true,
                "original",
            )

        override fun observeSources(): Flow<List<AudioSourceRecord>> =
            MutableStateFlow(
                listOf(record),
            )

        override suspend fun refresh() = Unit

        override suspend fun getSource(sourceId: String): AudioSourceRecord? =
            record
                .takeIf {
                    sourceId ==
                        SOURCE_ID
                }?.copy(
                    accessStatus =
                        if (available) SourceAccessStatus.AVAILABLE else SourceAccessStatus.MISSING,
                )

        override suspend fun verifySource(sourceId: String): AudioSourceRecord? =
            getSource(sourceId)?.copy(contentFingerprint = verifiedFingerprint)

        override suspend fun importSource(
            uri: String,
            takePersistablePermission: Boolean,
        ): AudioSource = record.source

        override suspend fun relinkSource(
            sourceId: String,
            uri: String,
            takePersistablePermission: Boolean,
        ) = Unit

        override suspend fun removeSource(sourceId: String) = Unit
    }

    private class FakeInstalledModels : InstalledModelRepository {
        private val model =
            InstalledModel(
                id = "installed",
                descriptorId = MODEL_ID,
                displayName = "Small",
                filePath = "/private/model.bin",
                sha256 = MODEL_HASH,
                bytes = 1,
                installedAtEpochMs = 1,
                verifiedAtEpochMs = 1,
            )

        override fun observeInstalledModels(): Flow<List<InstalledModel>> =
            MutableStateFlow(
                listOf(model),
            )

        override suspend fun getInstalledModel(modelId: String): InstalledModel? =
            model.takeIf {
                modelId ==
                    MODEL_ID
            }

        override suspend fun register(model: InstalledModel) = Unit

        override suspend fun remove(modelId: String) = Unit
    }

    private class FakeDurableRepository(
        initialJob: TranscriptionJob,
        initialChunks: List<TranscriptionChunk>,
        private val blockFirstGet: Boolean = false,
    ) : DurableTranscriptionRepository {
        var currentJob = initialJob
        val currentChunks = initialChunks.toMutableList()
        val segments = mutableListOf<TranscriptSegment>()
        val firstGetStarted = CompletableDeferred<Unit>()
        val releaseFirstGet = CompletableDeferred<Unit>()
        private var getJobCalls = 0

        override fun observeJobs(): Flow<List<TranscriptionJob>> =
            MutableStateFlow(
                listOf(currentJob),
            )

        override fun observeJob(jobId: String): Flow<TranscriptionJob?> =
            MutableStateFlow(
                currentJob,
            )

        override fun observeChunks(jobId: String): Flow<List<TranscriptionChunk>> =
            MutableStateFlow(
                currentChunks,
            )

        override fun observeSegments(jobId: String): Flow<List<TranscriptSegment>> =
            MutableStateFlow(
                segments,
            )

        override suspend fun getJob(jobId: String): TranscriptionJob? {
            if (blockFirstGet && getJobCalls++ == 0) {
                firstGetStarted.complete(Unit)
                releaseFirstGet.await()
            }
            return currentJob.takeIf { it.id == jobId }
        }

        override suspend fun createJob(job: TranscriptionJob, chunks: List<TranscriptionChunk>) =
            error(
                "unused",
            )

        override suspend fun claimNextChunk(
            jobId: String,
            startedAtEpochMs: Long,
        ): TranscriptionChunk? {
            val index = currentChunks.indexOfFirst { it.status == ChunkStatus.PENDING }
            if (index < 0 || currentJob.status != JobStatus.RUNNING) return null
            return currentChunks[index]
                .copy(
                    status = ChunkStatus.PROCESSING,
                    attempt = currentChunks[index].attempt + 1,
                    startedAtEpochMs = startedAtEpochMs,
                ).also { currentChunks[index] = it }
        }

        override suspend fun completeChunk(
            chunkId: String,
            segments: List<TranscriptSegment>,
            completedAtEpochMs: Long,
        ) {
            check(currentJob.status == JobStatus.RUNNING)
            val index = currentChunks.indexOfFirst { it.id == chunkId }
            check(currentChunks[index].status == ChunkStatus.PROCESSING)
            this.segments.removeAll { it.chunkId == chunkId }
            this.segments += segments
            currentChunks[index] =
                currentChunks[index].copy(
                    status = ChunkStatus.COMPLETED,
                    completedAtEpochMs = completedAtEpochMs,
                )
        }

        override suspend fun failChunk(
            chunkId: String,
            failure: DomainFailure,
            failedAtEpochMs: Long,
        ) = Unit

        override suspend fun pauseJob(jobId: String, pausedAtEpochMs: Long) =
            pauseJob(
                jobId,
                pausedAtEpochMs,
                null,
            )

        override suspend fun pauseJob(
            jobId: String,
            pausedAtEpochMs: Long,
            reason: DomainFailure?,
        ) {
            currentJob =
                currentJob.copy(
                    status = JobStatus.PAUSED,
                    updatedAtEpochMs = pausedAtEpochMs,
                    failure = reason,
                )
            currentChunks.replaceAll {
                if (it.status == ChunkStatus.PROCESSING || it.status == ChunkStatus.PENDING) {
                    it.copy(status = ChunkStatus.PAUSED)
                } else {
                    it
                }
            }
        }

        override suspend fun resumeJob(jobId: String, resumedAtEpochMs: Long) {
            currentJob =
                currentJob.copy(status = JobStatus.RUNNING, updatedAtEpochMs = resumedAtEpochMs)
            currentChunks.replaceAll {
                if (it.status ==
                    ChunkStatus.PAUSED
                ) {
                    it.copy(status = ChunkStatus.PENDING)
                } else {
                    it
                }
            }
        }

        override suspend fun restartForSourceFingerprint(
            jobId: String,
            sourceFingerprint: String,
            restartedAtEpochMs: Long,
        ) {
            currentJob =
                currentJob.copy(
                    sourceFingerprint = sourceFingerprint,
                    updatedAtEpochMs = restartedAtEpochMs,
                )
            segments.clear()
            currentChunks.indices.forEach { index ->
                currentChunks[index] =
                    currentChunks[index].copy(
                        status = ChunkStatus.PENDING,
                        attempt = 0,
                        startedAtEpochMs = null,
                        completedAtEpochMs = null,
                        failure = null,
                    )
            }
        }

        override suspend fun cancelJob(jobId: String, cancelledAtEpochMs: Long) {
            currentJob =
                currentJob.copy(status = JobStatus.CANCELLED, updatedAtEpochMs = cancelledAtEpochMs)
        }

        override suspend fun failJob(jobId: String, failure: DomainFailure, failedAtEpochMs: Long) {
            currentJob =
                currentJob.copy(
                    status = JobStatus.FAILED,
                    updatedAtEpochMs = failedAtEpochMs,
                    failure = failure,
                )
        }

        override suspend fun completeJob(jobId: String, completedAtEpochMs: Long) {
            currentJob =
                currentJob.copy(status = JobStatus.COMPLETED, updatedAtEpochMs = completedAtEpochMs)
        }

        override suspend fun recoverInterrupted(interruptedAtEpochMs: Long) = RecoverySummary(0, 0)
    }

    private companion object {
        const val JOB_ID = "job"
        const val SOURCE_ID = "source"
        const val MODEL_ID = "small"
        const val MODEL_HASH = "abc123"
    }
}
