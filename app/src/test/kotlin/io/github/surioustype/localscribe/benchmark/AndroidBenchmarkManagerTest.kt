package io.github.surioustype.localscribe.benchmark

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.EngineSegment
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.TranscriptionResult
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import io.github.surioustype.localscribe.core.ports.DemoAudioRepository
import io.github.surioustype.localscribe.core.ports.TranscriptionEngine
import io.github.surioustype.localscribe.data.BenchmarkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidBenchmarkManagerTest {
    @Test
    fun `benchmark persists actual sample identity and monotonic inference duration`() =
        runTest {
            val engine =
                FakeEngine(TranscriptionResult(listOf(EngineSegment(0, 1_000, "recognized text"))))
            val store = FakeStore()
            val clock = FakeClock(longArrayOf(1, 101))
            val manager = manager(engine, store, clock)

            val record =
                manager.benchmark(
                    model(),
                    InferenceConfig(threadCount = 2),
                    SAMPLE,
                    hardware(),
                )

            assertEquals(SAMPLE, record.sampleId)
            assertEquals(100, record.processingDurationMs)
            assertEquals(1_000, record.audioDurationMs)
            assertEquals(1, engine.loadCalls)
            assertEquals(1, engine.unloadCalls)
            assertEquals(record, store.records.single())
        }

    @Test
    fun `quality demo exposes recognized text score and aligned token differences`() =
        runTest {
            val engine =
                FakeEngine(TranscriptionResult(listOf(EngineSegment(0, 1_000, "the fast dog"))))
            val result =
                manager(
                    engine,
                ).runQualityDemo(model(), InferenceConfig(1), SAMPLE, hardware())

            assertEquals("the fast dog", result.recognizedText)
            assertEquals(1, result.score.wordErrors)
            assertTrue(result.differences.any { it.kind == TokenDifferenceKind.SUBSTITUTION })
        }

    @Test
    fun `quality alignment uses the score normalization token stream`() =
        runTest {
            val engine =
                FakeEngine(TranscriptionResult(listOf(EngineSegment(0, 1_000, "THE quick, dog"))))
            val result =
                manager(
                    engine,
                ).runQualityDemo(model(), InferenceConfig(1), SAMPLE, hardware())

            assertEquals(
                result.score.wordErrors,
                result.differences.count {
                    it.kind !=
                        TokenDifferenceKind.MATCH
                },
            )
        }

    @Test
    fun `benchmark cancellation unloads engine and is not persisted`() =
        runTest {
            val engine = FakeEngine(throwCancellation = true)
            val store = FakeStore()
            val manager = manager(engine, store)

            var cancelled = false
            try {
                manager.benchmark(model(), InferenceConfig(1), SAMPLE, hardware())
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertEquals(1, engine.unloadCalls)
            assertTrue(store.records.isEmpty())
        }

    @Test
    fun `load failure still attempts unload and leaves no record`() =
        runTest {
            val engine = FakeEngine(loadFailure = IllegalStateException("load"))
            val store = FakeStore()
            try {
                manager(engine, store).benchmark(model(), InferenceConfig(1), SAMPLE, hardware())
            } catch (
                _: IllegalStateException,
            ) {
            }
            assertEquals(1, engine.unloadCalls)
            assertTrue(store.records.isEmpty())
        }

    @Test
    fun `cancellation survives unload failure and leaves no record`() =
        runTest {
            val engine =
                FakeEngine(
                    throwCancellation = true,
                    unloadFailure = IllegalStateException("unload"),
                )
            val store = FakeStore()
            var cancelled = false
            try {
                manager(engine, store).benchmark(model(), InferenceConfig(1), SAMPLE, hardware())
            } catch (
                _: CancellationException,
            ) {
                cancelled =
                    true
            }
            assertTrue(cancelled)
            assertEquals(1, engine.unloadCalls)
            assertTrue(store.records.isEmpty())
        }

    @Test
    fun `unload failure after success prevents persistence`() =
        runTest {
            val engine = FakeEngine(unloadFailure = IllegalStateException("unload"))
            val store = FakeStore()
            try {
                manager(engine, store).benchmark(model(), InferenceConfig(1), SAMPLE, hardware())
            } catch (
                _: IllegalStateException,
            ) {
            }
            assertEquals(1, engine.unloadCalls)
            assertTrue(store.records.isEmpty())
        }

    @Test
    fun `cancellation arriving during unload is preserved and does not persist`() =
        runTest {
            val unloadEntered = CompletableDeferred<Unit>()
            val allowUnload = CompletableDeferred<Unit>()
            val engine = FakeEngine(unloadEntered = unloadEntered, allowUnload = allowUnload)
            val store = FakeStore()
            val benchmarkManager = manager(engine, store)
            val work =
                async {
                    benchmarkManager.benchmark(
                        model(),
                        InferenceConfig(1),
                        SAMPLE,
                        hardware(),
                    )
                }
            unloadEntered.await()
            work.cancel()
            allowUnload.complete(Unit)
            var cancelled = false
            try {
                work.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(
                BenchmarkRunPhase.CANCELLED,
                benchmarkManager.observeRunState().first().phase,
            )
            assertTrue(store.records.isEmpty())
        }

    @Test
    fun `direct benchmark and quality demo publish running then completed`() =
        runTest {
            val manager = manager(FakeEngine())
            manager.benchmark(model(), InferenceConfig(1), SAMPLE, hardware())
            assertEquals(BenchmarkRunPhase.COMPLETED, manager.observeRunState().first().phase)
            manager.runQualityDemo(model(), InferenceConfig(1), SAMPLE, hardware())
            assertEquals(BenchmarkRunPhase.COMPLETED, manager.observeRunState().first().phase)
        }

    @Test
    fun `inference failure publishes sanitized domain failure`() =
        runTest {
            val manager =
                manager(FakeEngine(transcribeFailure = IllegalStateException("private path")))
            try {
                manager.benchmark(model(), InferenceConfig(1), SAMPLE, hardware())
            } catch (
                _: IllegalStateException,
            ) {
            }
            val state = manager.observeRunState().first()
            assertEquals(BenchmarkRunPhase.FAILED, state.phase)
            assertEquals("benchmark_failed", state.failure?.diagnostic)
        }

    @Test
    fun `batch success exposes model progress and completed state`() =
        runTest {
            val manager = manager(FakeEngine())
            manager.benchmarkSelected(
                listOf(model(), model().copy(id = "two")),
                InferenceConfig(1),
                SAMPLE,
                hardware(),
            )
            val state = manager.observeRunState().first()
            assertEquals(BenchmarkRunPhase.COMPLETED, state.phase)
            assertEquals(2, state.completedModels)
            assertEquals(2, state.totalModels)
        }

    @Test
    fun `cancellation while waiting for shared mutex publishes cancelled without loading`() =
        runTest {
            val mutex = Mutex()
            mutex.lock()
            val engine = FakeEngine()
            val manager =
                AndroidBenchmarkManager({
                    engine
                }, FakeStore(), FakeDemoAudio(), mutex, FakeClock(longArrayOf(1, 2)), FakeSampler())
            try {
                val job =
                    launch {
                        manager.runQualityDemo(
                            model(),
                            InferenceConfig(1),
                            SAMPLE,
                            hardware(),
                        )
                    }
                runCurrent()
                assertEquals(BenchmarkRunPhase.RUNNING, manager.observeRunState().first().phase)
                job.cancelAndJoin()
                assertEquals(BenchmarkRunPhase.CANCELLED, manager.observeRunState().first().phase)
                assertEquals(0, engine.loadCalls)
            } finally {
                mutex.unlock()
            }
        }

    @Test
    fun `quality demo is running while inference is suspended`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val engine = FakeEngine(entered = entered, release = release)
            val manager = manager(engine)
            val work =
                async { manager.runQualityDemo(model(), InferenceConfig(1), SAMPLE, hardware()) }
            entered.await()
            assertEquals(BenchmarkRunPhase.RUNNING, manager.observeRunState().first().phase)
            assertEquals("tiny", manager.observeRunState().first().modelId)
            release.complete(Unit)
            work.await()
            assertEquals(BenchmarkRunPhase.COMPLETED, manager.observeRunState().first().phase)
        }

    @Test
    fun `records compare only with identical device sample model hash and configuration`() {
        val base =
            BenchmarkRecord(
                id = "a",
                deviceId = "device",
                modelId = "tiny",
                modelHash = "hash",
                config = InferenceConfig(1),
                audioDurationMs = 1,
                processingDurationMs = 1,
                realTimeFactor = 1.0,
                realTimeMultiplier = 1.0,
                createdAtEpochMs = 1,
                sampleId = SAMPLE,
            )

        assertEquals(
            listOf(base),
            BenchmarkComparisons.compatibleWith(base, listOf(base, base.copy(sampleId = "other"))),
        )
        assertTrue(
            BenchmarkComparisons
                .compatibleWith(
                    base,
                    listOf(base.copy(modelHash = "other")),
                ).isEmpty(),
        )
        assertTrue(
            BenchmarkComparisons
                .compatibleWith(
                    base,
                    listOf(base.copy(config = InferenceConfig(2))),
                ).isEmpty(),
        )
    }

    private fun manager(
        engine: FakeEngine,
        store: FakeStore = FakeStore(),
        clock: FakeClock = FakeClock(longArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
    ) = AndroidBenchmarkManager(
        engineFactory = { engine },
        benchmarkStore = store,
        demoAudioRepository = FakeDemoAudio(),
        executionMutex = Mutex(),
        clock = clock,
        sampler = FakeSampler(),
    )

    private fun model() =
        InstalledModel(
            "row",
            "tiny",
            "Tiny",
            "/verified/tiny.bin",
            "hash",
            1,
            1,
            1,
        )

    private fun hardware() = HardwareProfile("device", 1, 1, 1, emptyList(), 1)

    private class FakeDemoAudio : DemoAudioRepository {
        override fun observeSamples() =
            flowOf(
                emptyList<io.github.surioustype.localscribe.core.model.DemoSample>(),
            )

        override suspend fun getSample(sampleId: String) =
            io.github.surioustype.localscribe.core.model.DemoSample(
                SAMPLE,
                "sample",
                "asset://sample",
                1_000,
                setOf("en"),
                "the quick dog",
                "PD",
                "source",
            )

        override suspend fun readPcm(sampleId: String) = FloatArray(16_000)
    }

    private class FakeStore : BenchmarkStore {
        val records = mutableListOf<BenchmarkRecord>()

        override fun observe(): Flow<List<BenchmarkRecord>> = MutableStateFlow(records)

        override suspend fun get(id: String) = records.firstOrNull { it.id == id }

        override suspend fun upsert(record: BenchmarkRecord) {
            records += record
        }

        override suspend fun remove(id: String) {
            records.removeAll { it.id == id }
        }
    }

    private class FakeEngine(
        private val result: TranscriptionResult = TranscriptionResult(emptyList()),
        private val throwCancellation: Boolean = false,
        private val loadFailure: Throwable? = null,
        private val unloadFailure: Throwable? = null,
        private val transcribeFailure: Throwable? = null,
        private val entered: CompletableDeferred<Unit>? = null,
        private val release: CompletableDeferred<Unit>? = null,
        private val unloadEntered: CompletableDeferred<Unit>? = null,
        private val allowUnload: CompletableDeferred<Unit>? = null,
    ) : TranscriptionEngine {
        var loadCalls = 0
        var unloadCalls = 0

        override suspend fun loadModel(model: InstalledModel, vadModel: InstalledModel?) {
            loadCalls++
            loadFailure?.let { throw it }
        }

        override suspend fun transcribe(
            pcm: FloatArray,
            config: InferenceConfig,
            prompt: String?,
            cancellationSignal: CancellationSignal,
        ): TranscriptionResult {
            entered?.complete(Unit)
            release?.await()
            transcribeFailure?.let { throw it }
            if (throwCancellation) throw CancellationException("cancelled")
            return result
        }

        override suspend fun unloadModel() {
            unloadCalls++
            unloadEntered?.complete(Unit)
            allowUnload?.await()
            unloadFailure?.let { throw it }
        }
    }

    private class FakeClock(private val values: LongArray) : BenchmarkClock {
        private var index = 0

        override fun elapsedRealtimeMs(): Long = values[index++]

        override fun epochMs(): Long = 10
    }

    private class FakeSampler : BenchmarkSampler {
        override fun snapshot() = BenchmarkSample(memoryBytes = 12, thermalStatus = 0)
    }

    private companion object {
        const val SAMPLE = "sample"
    }
}
