package io.github.surioustype.localscribe.audio

import io.github.surioustype.localscribe.core.model.WHISPER_SAMPLE_RATE_HZ
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/** Stateful, chunk-independent channel mixer and linear resampler. */
class StreamingMonoResampler(
    private val inputSampleRate: Int,
    private val channelCount: Int,
    private val outputSampleRate: Int = WHISPER_SAMPLE_RATE_HZ,
) {
    private val sourceStep = inputSampleRate.toDouble() / outputSampleRate
    private var sourceFramesSeen = 0L
    private var nextOutputPosition = 0.0
    private var previousSample = 0f
    private var hasPreviousSample = false

    init {
        require(inputSampleRate > 0) { "inputSampleRate must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
        require(outputSampleRate > 0) { "outputSampleRate must be positive" }
    }

    fun process(interleavedSamples: FloatArray, endOfInput: Boolean = false): FloatArray {
        require(interleavedSamples.size % channelCount == 0) {
            "interleaved sample count must contain complete frames"
        }
        val frameCount = interleavedSamples.size / channelCount
        val estimated = ceil(frameCount * outputSampleRate.toDouble() / inputSampleRate).toInt() + 2
        val output = FloatArrayBuilder(estimated)
        var sampleOffset = 0
        repeat(frameCount) {
            var mono = 0f
            repeat(channelCount) { channel -> mono += interleavedSamples[sampleOffset + channel] }
            mono /= channelCount
            sampleOffset += channelCount

            val sourcePosition = sourceFramesSeen.toDouble()
            if (!hasPreviousSample) {
                previousSample = mono
                hasPreviousSample = true
            }
            while (nextOutputPosition <= sourcePosition + POSITION_EPSILON) {
                val value =
                    if (sourceFramesSeen == 0L) {
                        mono
                    } else {
                        val fraction =
                            (nextOutputPosition - (sourcePosition - 1.0)).coerceIn(
                                0.0,
                                1.0,
                            )
                        previousSample + ((mono - previousSample) * fraction.toFloat())
                    }
                output.add(value)
                nextOutputPosition += sourceStep
            }
            previousSample = mono
            sourceFramesSeen++
        }

        if (endOfInput && hasPreviousSample) {
            val expectedCount = ((sourceFramesSeen * outputSampleRate) / inputSampleRate).toInt()
            val emittedCount = ((nextOutputPosition / sourceStep) + POSITION_EPSILON).toInt()
            repeat((expectedCount - emittedCount).coerceAtLeast(0)) {
                output.add(previousSample)
                nextOutputPosition += sourceStep
            }
        }
        return output.toArray()
    }

    private companion object {
        const val POSITION_EPSILON = 1e-9
    }
}

object SafePcmNormalizer {
    fun normalizeInPlace(samples: FloatArray, targetPeak: Float = 0.95f, maximumGain: Float = 2f) {
        require(targetPeak in 0f..1f)
        require(maximumGain >= 1f)
        val peak = samples.maxOfOrNull { abs(it) } ?: return
        if (peak <= java.lang.Float.MIN_NORMAL) return
        val gain = min(maximumGain, targetPeak / peak)
        for (index in samples.indices) {
            samples[index] = (samples[index] * gain).coerceIn(-targetPeak, targetPeak)
        }
    }
}

/** Accumulates bounded mono 16 kHz output across decoder format transitions. */
internal class StreamingPcmAccumulator(private val maximumSamples: Int) {
    private val output = FloatArrayBuilder(maximumSamples.coerceAtMost(16_000))
    private var inputSampleRate: Int? = null
    private var channelCount: Int? = null
    private var resampler: StreamingMonoResampler? = null
    private var finished = false

    init {
        require(maximumSamples >= 0) { "maximumSamples must be non-negative" }
    }

    fun updateFormat(inputSampleRate: Int, channelCount: Int) {
        check(!finished) { "PCM accumulator is finished" }
        if (this.inputSampleRate == inputSampleRate && this.channelCount == channelCount) return
        finishCurrentFormat()
        this.inputSampleRate = inputSampleRate
        this.channelCount = channelCount
        resampler = StreamingMonoResampler(inputSampleRate, channelCount)
    }

    fun process(interleavedSamples: FloatArray) {
        check(!finished) { "PCM accumulator is finished" }
        val converted =
            checkNotNull(
                resampler,
            ) { "PCM format is missing" }.process(interleavedSamples)
        output.addAll(converted, maximumSamples)
    }

    fun finish(): FloatArray {
        if (!finished) {
            finishCurrentFormat()
            finished = true
        }
        return output.toArray()
    }

    private fun finishCurrentFormat() {
        resampler?.process(FloatArray(0), endOfInput = true)?.let {
            output.addAll(it, maximumSamples)
        }
    }
}

internal class FloatArrayBuilder(initialCapacity: Int = 1_024) {
    private var values = FloatArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun addAll(newValues: FloatArray, maximumSize: Int = Int.MAX_VALUE) {
        val copiedSize = min(newValues.size, (maximumSize - size).coerceAtLeast(0))
        if (copiedSize == 0) return
        if (size + copiedSize > values.size) {
            values = values.copyOf(maxOf(values.size * 2, size + copiedSize))
        }
        newValues.copyInto(values, size, endIndex = copiedSize)
        size += copiedSize
    }

    fun toArray(): FloatArray = values.copyOf(size)
}
