package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.BenchmarkTiming

object BenchmarkCalculator {
    fun calculate(audioDurationMs: Long, processingDurationMs: Long): BenchmarkTiming {
        require(audioDurationMs > 0) { "Audio duration must be positive" }
        require(processingDurationMs > 0) { "Processing duration must be positive" }
        return BenchmarkTiming(
            realTimeFactor = processingDurationMs.toDouble() / audioDurationMs,
            realTimeMultiplier = audioDurationMs.toDouble() / processingDurationMs,
        )
    }
}
