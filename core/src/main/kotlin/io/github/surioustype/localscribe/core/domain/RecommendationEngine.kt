package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.ModelRecommendation
import io.github.surioustype.localscribe.core.model.RecommendationLevel

class RecommendationEngine {
    fun recommend(
        catalog: List<ModelDescriptor>,
        benchmarks: List<BenchmarkRecord>,
        hardwareProfile: HardwareProfile,
    ): List<ModelRecommendation> =
        catalog.filter { it.kind == ModelKind.TRANSCRIPTION }.map { model ->
            val benchmark =
                benchmarks
                    .asSequence()
                    .filter { record ->
                        record.deviceId == hardwareProfile.deviceId &&
                            record.modelId == model.id &&
                            record.modelHash.equals(model.sha256, ignoreCase = true) &&
                            record.audioDurationMs > 0 &&
                            record.processingDurationMs > 0 &&
                            record.realTimeFactor.isFinite() &&
                            record.realTimeFactor > 0
                    }.maxWithOrNull(
                        compareBy<BenchmarkRecord> { it.createdAtEpochMs }.thenBy { it.id },
                    )
            val measuredPeakMemoryBytes = benchmark?.approximatePeakMemoryBytes?.takeIf { it > 0 }
            val requiredMemoryBytes =
                measuredPeakMemoryBytes ?: conservativeMemoryFallback(model.installedBytes)

            when {
                requiredMemoryBytes > hardwareProfile.availableMemoryBytes ->
                    ModelRecommendation(
                        modelId = model.id,
                        level = RecommendationLevel.MAY_BE_SLOW,
                        reason =
                            if (measuredPeakMemoryBytes != null) {
                                "Measured peak memory exceeds currently available memory."
                            } else {
                                "Estimated inference memory exceeds currently available memory."
                            },
                    )

                benchmark == null ->
                    ModelRecommendation(
                        modelId = model.id,
                        level = RecommendationLevel.NOT_ENOUGH_DATA,
                        reason = "Run this model benchmark on the current device and artifact.",
                    )

                benchmark.realTimeFactor <= RECOMMENDED_RTF ->
                    ModelRecommendation(
                        modelId = model.id,
                        level = RecommendationLevel.RECOMMENDED,
                        reason = benchmarkReason(benchmark),
                    )

                benchmark.realTimeFactor <= REALTIME_RTF ->
                    ModelRecommendation(
                        modelId = model.id,
                        level = RecommendationLevel.USABLE,
                        reason = benchmarkReason(benchmark),
                    )

                else ->
                    ModelRecommendation(
                        modelId = model.id,
                        level = RecommendationLevel.MAY_BE_SLOW,
                        reason = benchmarkReason(benchmark),
                    )
            }
        }

    private fun benchmarkReason(record: BenchmarkRecord): String =
        "Measured RTF ${"%.2f".format(java.util.Locale.ROOT, record.realTimeFactor)} " +
            "with ${record.config.threadCount} threads."

    /** Reserves room for weights plus inference buffers when no measured peak is available. */
    private fun conservativeMemoryFallback(installedBytes: Long): Long {
        val nonNegativeBytes = installedBytes.coerceAtLeast(0)
        return if (nonNegativeBytes > Long.MAX_VALUE / FALLBACK_MEMORY_MULTIPLIER) {
            Long.MAX_VALUE
        } else {
            nonNegativeBytes * FALLBACK_MEMORY_MULTIPLIER
        }
    }

    private companion object {
        const val FALLBACK_MEMORY_MULTIPLIER = 2L
        const val RECOMMENDED_RTF = 0.5
        const val REALTIME_RTF = 1.0
    }
}
