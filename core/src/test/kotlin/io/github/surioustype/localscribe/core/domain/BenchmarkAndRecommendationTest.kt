package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.ModelQuality
import io.github.surioustype.localscribe.core.model.RecommendationLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkAndRecommendationTest {
    @Test
    fun `benchmark calculation returns reciprocal timing measures`() {
        val result =
            BenchmarkCalculator.calculate(
                audioDurationMs = 30_000,
                processingDurationMs = 8_400,
            )

        assertEquals(0.28, result.realTimeFactor, 1e-12)
        assertEquals(30_000.0 / 8_400.0, result.realTimeMultiplier, 1e-12)
    }

    @Test
    fun `benchmark calculation rejects zero and negative inputs`() {
        assertFails { BenchmarkCalculator.calculate(0, 1) }
        assertFails { BenchmarkCalculator.calculate(1, 0) }
        assertFails { BenchmarkCalculator.calculate(-1, 1) }
        assertFails { BenchmarkCalculator.calculate(1, -1) }
    }

    @Test
    fun `recommendations use matching device and artifact benchmark before hardware guesses`() {
        val catalog =
            listOf(
                model("small", "hash-small", 200),
                model("medium", "hash-medium", 400),
                model("large", "hash-large", 2_000),
            )
        val benchmarks =
            listOf(
                benchmark("small-current", "small", "hash-small", rtf = 0.4, created = 2),
                benchmark("small-stale-artifact", "small", "old-hash", rtf = 0.1, created = 3),
                benchmark("medium-stale-artifact", "medium", "old-hash", rtf = 0.2, created = 3),
                benchmark("large-current", "large", "hash-large", rtf = 0.3, created = 2),
            )

        val result =
            RecommendationEngine()
                .recommend(catalog, benchmarks, hardware(available = 1_000))
                .associateBy { it.modelId }

        assertEquals(RecommendationLevel.RECOMMENDED, result.getValue("small").level)
        assertEquals(RecommendationLevel.NOT_ENOUGH_DATA, result.getValue("medium").level)
        assertEquals(RecommendationLevel.MAY_BE_SLOW, result.getValue("large").level)
    }

    @Test
    fun `latest valid benchmark determines the recommendation`() {
        val catalog = listOf(model("small", "hash", 200))
        val benchmarks =
            listOf(
                benchmark("old", "small", "hash", rtf = 0.3, created = 1),
                benchmark("new", "small", "hash", rtf = 1.2, created = 2),
            )

        val result = RecommendationEngine().recommend(catalog, benchmarks, hardware(1_000)).single()

        assertEquals(RecommendationLevel.MAY_BE_SLOW, result.level)
    }

    @Test
    fun `VAD artifacts are excluded from transcription recommendations`() {
        val transcription = model("small", "hash-small", 200)
        val vad = model("silero", "hash-vad", 50).copy(kind = ModelKind.VAD)

        val result =
            RecommendationEngine().recommend(
                catalog = listOf(transcription, vad),
                benchmarks = emptyList(),
                hardwareProfile = hardware(1_000),
            )

        assertEquals(listOf("small"), result.map { it.modelId })
    }

    @Test
    fun `measured peak memory prevents recommendation when model file itself fits`() {
        val catalog = listOf(model("small", "hash", 200))
        val benchmarks =
            listOf(
                benchmark(
                    id = "measured",
                    modelId = "small",
                    hash = "hash",
                    rtf = 0.3,
                    created = 1,
                    peakMemoryBytes = 2_000,
                ),
            )

        val result = RecommendationEngine().recommend(catalog, benchmarks, hardware(1_000)).single()

        assertEquals(RecommendationLevel.MAY_BE_SLOW, result.level)
    }

    @Test
    fun `missing peak memory uses conservative model size fallback`() {
        val catalog = listOf(model("medium", "hash", 600))
        val benchmarks = listOf(benchmark("measured", "medium", "hash", rtf = 0.3, created = 1))

        val result = RecommendationEngine().recommend(catalog, benchmarks, hardware(1_000)).single()

        assertEquals(RecommendationLevel.MAY_BE_SLOW, result.level)
    }

    private fun model(id: String, hash: String, bytes: Long) =
        ModelDescriptor(
            id = id,
            displayName = id,
            version = "1",
            downloadUrl = "https://example.com/$id",
            sha256 = hash,
            downloadBytes = bytes,
            installedBytes = bytes,
            languages = setOf("multi"),
            quality = ModelQuality.BALANCED,
        )

    private fun benchmark(
        id: String,
        modelId: String,
        hash: String,
        rtf: Double,
        created: Long,
        peakMemoryBytes: Long? = null,
    ) =
        BenchmarkRecord(
            id = id,
            deviceId = "device",
            modelId = modelId,
            modelHash = hash,
            config = InferenceConfig(threadCount = 4),
            audioDurationMs = 10_000,
            processingDurationMs = (10_000 * rtf).toLong(),
            realTimeFactor = rtf,
            realTimeMultiplier = 1.0 / rtf,
            approximatePeakMemoryBytes = peakMemoryBytes,
            createdAtEpochMs = created,
        )

    private fun hardware(available: Long) =
        HardwareProfile(
            deviceId = "device",
            totalMemoryBytes = 4_000,
            availableMemoryBytes = available,
            cpuCoreCount = 8,
            supportedAbis = listOf("arm64-v8a"),
            androidApiLevel = 35,
        )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
