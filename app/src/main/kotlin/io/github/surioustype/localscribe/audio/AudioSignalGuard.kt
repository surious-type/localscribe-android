package io.github.surioustype.localscribe.audio

import io.github.surioustype.localscribe.core.model.WHISPER_SAMPLE_RATE_HZ
import kotlin.math.sqrt

/**
 * Conservative PCM gate, not a replacement for Silero VAD.
 *
 * Only skips windows with near-digital silence and shortens a long silent tail.
 * If background noise is audible the complete signal is retained for Whisper/VAD.
 * This never removes a non-silent interior interval or changes source timestamps.
 */
internal object AudioSignalGuard {
    private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
    private const val PEAK_FLOOR = 0.004f
    private const val RMS_FLOOR = 0.0008f
    private const val MIN_TRAILING_SILENCE_SAMPLES = WHISPER_SAMPLE_RATE_HZ * 2
    private const val CONTEXT_SAMPLES = WHISPER_SAMPLE_RATE_HZ / 2
    private const val MIN_SPEECH_WINDOW_SAMPLES = WHISPER_SAMPLE_RATE_HZ

    fun prepare(pcm: FloatArray): PreparedAudioWindow {
        if (pcm.isEmpty()) return PreparedAudioWindow(FloatArray(0), 0L, 0L, true)

        var lastSignalEnd = 0
        var index = 0
        while (index < pcm.size) {
            val end = minOf(index + FRAME_SAMPLES, pcm.size)
            var peak = 0f
            var sumSquares = 0.0
            for (i in index until end) {
                val amplitude = kotlin.math.abs(pcm[i])
                if (amplitude > peak) peak = amplitude
                sumSquares += amplitude.toDouble() * amplitude.toDouble()
            }
            val rms = sqrt(sumSquares / (end - index))
            if (peak >= PEAK_FLOOR || rms >= RMS_FLOOR) lastSignalEnd = end
            index = end
        }
        if (lastSignalEnd == 0) {
            return PreparedAudioWindow(FloatArray(0), 0L, samplesToMs(pcm.size), true)
        }

        val silenceSamples = pcm.size - lastSignalEnd
        val keepSamples =
            if (silenceSamples >= MIN_TRAILING_SILENCE_SAMPLES) {
                minOf(
                    pcm.size,
                    maxOf(lastSignalEnd + CONTEXT_SAMPLES, MIN_SPEECH_WINDOW_SAMPLES),
                )
            } else {
                pcm.size
            }
        return PreparedAudioWindow(
            pcm = if (keepSamples == pcm.size) pcm else pcm.copyOf(keepSamples),
            effectiveDurationMs = samplesToMs(keepSamples),
            trimmedTailMs = samplesToMs(pcm.size - keepSamples),
            isSilent = false,
        )
    }

    private fun samplesToMs(samples: Int): Long =
        samples.toLong() * 1_000L / WHISPER_SAMPLE_RATE_HZ
}

internal data class PreparedAudioWindow(
    val pcm: FloatArray,
    val effectiveDurationMs: Long,
    val trimmedTailMs: Long,
    val isSilent: Boolean,
)
