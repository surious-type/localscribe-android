package io.github.surioustype.localscribe.benchmark

import android.os.SystemClock
import io.github.surioustype.localscribe.core.domain.BenchmarkCalculator
import io.github.surioustype.localscribe.core.domain.QualityMetrics
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.QualityScore
import io.github.surioustype.localscribe.core.model.VadConfig
import io.github.surioustype.localscribe.core.ports.BenchmarkManager
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import io.github.surioustype.localscribe.core.ports.DemoAudioRepository
import io.github.surioustype.localscribe.core.ports.TranscriptionEngine
import io.github.surioustype.localscribe.data.BenchmarkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

fun interface BenchmarkEngineFactory {
    fun create(): TranscriptionEngine
}

enum class BenchmarkRunPhase { IDLE, RUNNING, COMPLETED, CANCELLED, FAILED }

data class BenchmarkRunState(
    val phase: BenchmarkRunPhase = BenchmarkRunPhase.IDLE,
    val completedModels: Int = 0,
    val totalModels: Int = 0,
    val modelId: String? = null,
    val failure: DomainFailure? = null,
)

data class QualityDemoResult(
    val sampleId: String,
    val referenceText: String,
    val recognizedText: String,
    val score: QualityScore,
    val differences: List<TokenDifference>,
    val benchmark: BenchmarkRecord,
)

enum class TokenDifferenceKind { MATCH, INSERTION, DELETION, SUBSTITUTION }

data class TokenDifference(
    val kind: TokenDifferenceKind,
    val referenceToken: String?,
    val recognizedToken: String?,
)

class AndroidBenchmarkManager(
    private val engineFactory: BenchmarkEngineFactory,
    private val benchmarkStore: BenchmarkStore,
    private val demoAudioRepository: DemoAudioRepository,
    private val executionMutex: Mutex,
    private val clock: BenchmarkClock =
        object : BenchmarkClock {
            override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        },
    private val sampler: BenchmarkSampler,
    private val vadModelResolver: suspend (VadConfig) -> InstalledModel? = { null },
) : BenchmarkManager {
    private val runState = MutableStateFlow(BenchmarkRunState())

    override fun observeBenchmarks(): Flow<List<BenchmarkRecord>> = benchmarkStore.observe()

    fun observeRunState(): Flow<BenchmarkRunState> = runState.asStateFlow()

    override suspend fun benchmark(
        model: InstalledModel,
        config: InferenceConfig,
        sampleId: String,
        hardwareProfile: HardwareProfile,
    ): BenchmarkRecord =
        runWithState(
            model,
            0,
            1,
        ) {
            runOne(model, config, sampleId, hardwareProfile).record
        }

    suspend fun runQualityDemo(
        model: InstalledModel,
        config: InferenceConfig,
        sampleId: String,
        hardwareProfile: HardwareProfile,
    ): QualityDemoResult {
        val result = runWithState(model, 0, 1) { runOne(model, config, sampleId, hardwareProfile) }
        return QualityDemoResult(
            result.sample.id,
            result.sample.referenceTranscript,
            result.recognizedText,
            QualityMetrics.calculate(result.sample.referenceTranscript, result.recognizedText),
            AlignedTokenDifferences.calculate(
                result.sample.referenceTranscript,
                result.recognizedText,
            ),
            result.record,
        )
    }

    suspend fun benchmarkSelected(
        models: List<InstalledModel>,
        config: InferenceConfig,
        sampleId: String,
        hardwareProfile: HardwareProfile,
    ): List<BenchmarkRecord> {
        require(models.isNotEmpty()) { "Select at least one installed model" }
        runState.value = BenchmarkRunState(BenchmarkRunPhase.RUNNING, totalModels = models.size)
        val records = mutableListOf<BenchmarkRecord>()
        try {
            models.forEachIndexed { index, model ->
                runState.value =
                    BenchmarkRunState(
                        BenchmarkRunPhase.RUNNING,
                        index,
                        models.size,
                        model.descriptorId,
                    )
                records += runOne(model, config, sampleId, hardwareProfile).record
            }
            runState.value =
                BenchmarkRunState(BenchmarkRunPhase.COMPLETED, records.size, models.size)
            return records
        } catch (cancelled: CancellationException) {
            runState.value =
                BenchmarkRunState(BenchmarkRunPhase.CANCELLED, records.size, models.size)
            throw cancelled
        } catch (error: Throwable) {
            runState.value =
                BenchmarkRunState(
                    BenchmarkRunPhase.FAILED,
                    records.size,
                    models.size,
                    failure = error.toBenchmarkFailure(),
                )
            throw error
        }
    }

    private suspend fun runOne(
        model: InstalledModel,
        config: InferenceConfig,
        sampleId: String,
        hardwareProfile: HardwareProfile,
    ): CompletedBenchmark =
        executionMutex.withLock {
            require(config.threadCount > 0) { "threadCount must be positive" }
            val sample =
                requireNotNull(
                    demoAudioRepository.getSample(sampleId),
                ) { "Demo sample is unavailable: $sampleId" }
            val pcm = demoAudioRepository.readPcm(sampleId)
            require(pcm.isNotEmpty()) { "Demo audio is empty: $sampleId" }
            val audioDurationMs = pcm.size.toLong() * 1_000 / 16_000
            require(audioDurationMs == sample.durationMs) {
                "Demo PCM duration does not match manifest: $sampleId"
            }
            val engine = engineFactory.create()
            val samples = mutableListOf<BenchmarkSample>()
            var completed: CompletedBenchmark? = null
            var primaryFailure: Throwable? = null
            try {
                samples += sampler.snapshot()
                val vad =
                    config.vad?.let { spec ->
                        requireNotNull(vadModelResolver(spec)) {
                            "Configured VAD model is unavailable"
                        }.also {
                            require(
                                it.sha256.equals(spec.modelHash, true),
                            ) { "Configured VAD hash changed" }
                        }
                    }
                engine.loadModel(model, vad)
                samples += sampler.snapshot()
                lateinit var recognizedText: String
                var processingDurationMs = 0L
                coroutineScope {
                    val collector =
                        launch {
                            while (isActive) {
                                samples += sampler.snapshot()
                                delay(MEMORY_SAMPLE_INTERVAL_MS)
                            }
                        }
                    try {
                        val start = clock.elapsedRealtimeMs()
                        val result =
                            engine.transcribe(
                                pcm,
                                config,
                                null,
                                CancellationSignal { !isActive },
                            )
                        processingDurationMs = (clock.elapsedRealtimeMs() - start).coerceAtLeast(1)
                        recognizedText = result.segments.joinToString(" ") { it.text.trim() }.trim()
                    } finally {
                        collector.cancel()
                    }
                }
                samples += sampler.snapshot()
                val timing = BenchmarkCalculator.calculate(audioDurationMs, processingDurationMs)
                val record =
                    BenchmarkRecord(
                        UUID.randomUUID().toString(),
                        hardwareProfile.deviceId,
                        model.descriptorId,
                        model.sha256,
                        config,
                        audioDurationMs,
                        processingDurationMs,
                        timing.realTimeFactor,
                        timing.realTimeMultiplier,
                        samples.mapNotNull(BenchmarkSample::thermalStatus).maxOrNull(),
                        samples.mapNotNull(BenchmarkSample::memoryBytes).maxOrNull(),
                        clock.epochMs(),
                        sample.id,
                    )
                completed = CompletedBenchmark(sample, recognizedText, record)
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                val cleanupFailure =
                    runCatching {
                        withContext(NonCancellable) { engine.unloadModel() }
                    }.exceptionOrNull()
                if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
                if (primaryFailure != null &&
                    cleanupFailure != null
                ) {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val result = checkNotNull(completed)
            benchmarkStore.upsert(result.record)
            result
        }

    private suspend fun <T> runWithState(
        model: InstalledModel,
        completedModels: Int,
        totalModels: Int,
        block: suspend () -> T,
    ): T {
        runState.value =
            BenchmarkRunState(
                BenchmarkRunPhase.RUNNING,
                completedModels,
                totalModels,
                model.descriptorId,
            )
        return try {
            block().also {
                runState.value =
                    BenchmarkRunState(
                        BenchmarkRunPhase.COMPLETED,
                        totalModels,
                        totalModels,
                        model.descriptorId,
                    )
            }
        } catch (cancelled: CancellationException) {
            runState.value =
                BenchmarkRunState(
                    BenchmarkRunPhase.CANCELLED,
                    completedModels,
                    totalModels,
                    model.descriptorId,
                )
            throw cancelled
        } catch (error: Throwable) {
            runState.value =
                BenchmarkRunState(
                    BenchmarkRunPhase.FAILED,
                    completedModels,
                    totalModels,
                    model.descriptorId,
                    error.toBenchmarkFailure(),
                )
            throw error
        }
    }

    private data class CompletedBenchmark(
        val sample: io.github.surioustype.localscribe.core.model.DemoSample,
        val recognizedText: String,
        val record: BenchmarkRecord,
    )

    private companion object {
        const val MEMORY_SAMPLE_INTERVAL_MS = 100L
    }
}

object BenchmarkComparisons {
    fun compatibleWith(reference: BenchmarkRecord, candidates: List<BenchmarkRecord>) =
        candidates.filter {
            it.deviceId ==
                reference.deviceId &&
                it.modelHash == reference.modelHash &&
                it.sampleId == reference.sampleId &&
                it.config == reference.config
        }
}

private fun Throwable.toBenchmarkFailure(): DomainFailure =
    DomainFailure(
        FailureCode.UNKNOWN,
        "benchmark_failed",
    )

object AlignedTokenDifferences {
    fun calculate(reference: String, recognized: String): List<TokenDifference> {
        val expected = QualityMetrics.normalizedWords(reference)
        val actual = QualityMetrics.normalizedWords(recognized)
        val costs =
            Array(
                expected.size + 1,
            ) { IntArray(actual.size + 1) }
        for (i in expected.indices.reversed()) {
            for (j in actual.indices.reversed()) {
                costs[i][j] =
                    if (expected[i] ==
                        actual[j]
                    ) {
                        costs[i + 1][j + 1]
                    } else {
                        1 +
                            minOf(costs[i + 1][j], costs[i][j + 1], costs[i + 1][j + 1])
                    }
            }
        }
        val result = mutableListOf<TokenDifference>()
        var i = 0
        var j = 0
        while (i < expected.size || j < actual.size) {
            when {
                i == expected.size ->
                    result +=
                        TokenDifference(TokenDifferenceKind.INSERTION, null, actual[j++])
                j == actual.size ->
                    result +=
                        TokenDifference(TokenDifferenceKind.DELETION, expected[i++], null)
                expected[i] == actual[j] ->
                    result +=
                        TokenDifference(TokenDifferenceKind.MATCH, expected[i++], actual[j++])
                costs[i][j] == 1 + costs[i + 1][j + 1] ->
                    result +=
                        TokenDifference(
                            TokenDifferenceKind.SUBSTITUTION,
                            expected[i++],
                            actual[j++],
                        )
                costs[i][j] == 1 + costs[i + 1][j] ->
                    result +=
                        TokenDifference(TokenDifferenceKind.DELETION, expected[i++], null)
                else -> result += TokenDifference(TokenDifferenceKind.INSERTION, null, actual[j++])
            }
        }
        return result
    }
}
