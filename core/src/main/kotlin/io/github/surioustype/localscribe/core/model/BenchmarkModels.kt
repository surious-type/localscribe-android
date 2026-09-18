package io.github.surioustype.localscribe.core.model

data class HardwareProfile(
    val deviceId: String,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val cpuCoreCount: Int,
    val supportedAbis: List<String>,
    val androidApiLevel: Int,
)

data class BenchmarkRecord(
    val id: String,
    val deviceId: String,
    val modelId: String,
    val modelHash: String,
    val config: InferenceConfig,
    val audioDurationMs: Long,
    val processingDurationMs: Long,
    val realTimeFactor: Double,
    val realTimeMultiplier: Double,
    val thermalStatus: Int? = null,
    val approximatePeakMemoryBytes: Long? = null,
    val createdAtEpochMs: Long,
    val sampleId: String = "unspecified",
)

data class BenchmarkTiming(
    val realTimeFactor: Double,
    val realTimeMultiplier: Double,
)

enum class RecommendationLevel {
    RECOMMENDED,
    USABLE,
    MAY_BE_SLOW,
    NOT_ENOUGH_DATA,
}

data class ModelRecommendation(
    val modelId: String,
    val level: RecommendationLevel,
    val reason: String,
)

data class DemoSample(
    val id: String,
    val displayName: String,
    val uri: String,
    val durationMs: Long,
    val languageTags: Set<String>,
    val referenceTranscript: String,
    val licenseName: String,
    val sourceUrl: String,
)

data class QualityScore(
    val wordErrorRate: Double,
    val characterErrorRate: Double,
    val wordErrors: Int,
    val referenceWordCount: Int,
)
