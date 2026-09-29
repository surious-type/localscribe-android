package io.github.surioustype.localscribe.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAudioProcessorTest {
    @Test
    fun `stereo frames are averaged before resampling`() {
        val resampler = StreamingMonoResampler(inputSampleRate = 16_000, channelCount = 2)

        val output =
            resampler.process(
                interleavedSamples = floatArrayOf(1f, -1f, 0.5f, 0.5f, -0.25f, 0.75f),
                endOfInput = true,
            )

        assertArrayEquals(floatArrayOf(0f, 0.5f, 0.25f), output, 0.0001f)
    }

    @Test
    fun `chunked resampling is identical to one shot and preserves duration`() {
        val input = FloatArray(48_000) { index -> index / 48_000f }
        val oneShot = StreamingMonoResampler(48_000, 1).process(input, endOfInput = true)
        val chunked = StreamingMonoResampler(48_000, 1)
        val pieces =
            listOf(
                chunked.process(input.copyOfRange(0, 12_345)),
                chunked.process(input.copyOfRange(12_345, 32_001)),
                chunked.process(input.copyOfRange(32_001, input.size), endOfInput = true),
            )
        val joined = pieces.fold(FloatArray(0)) { acc, piece -> acc + piece }

        assertEquals(16_000, oneShot.size)
        assertArrayEquals(oneShot, joined, 0.000001f)
    }

    @Test
    fun `normalization caps peaks and limits amplification`() {
        val quiet = floatArrayOf(-0.1f, 0.05f)
        SafePcmNormalizer.normalizeInPlace(quiet)
        assertArrayEquals(floatArrayOf(-0.2f, 0.1f), quiet, 0.0001f)

        val hot = floatArrayOf(-1.2f, 0.6f)
        SafePcmNormalizer.normalizeInPlace(hot)
        assertTrue(hot.all { it in -0.95f..0.95f })
        assertEquals(-0.95f, hot[0], 0.0001f)
    }

    @Test
    fun `format transition finishes prior resampler and preserves both durations`() {
        val processor = StreamingPcmAccumulator(maximumSamples = 320)
        processor.updateFormat(inputSampleRate = 48_000, channelCount = 2)
        processor.process(FloatArray(480 * 2) { 0.25f })
        processor.updateFormat(inputSampleRate = 16_000, channelCount = 1)
        processor.process(FloatArray(160) { -0.5f })

        val result = processor.finish()

        assertEquals(320, result.size)
        assertTrue(result.copyOfRange(0, 160).all { it == 0.25f })
        assertTrue(result.copyOfRange(160, 320).all { it == -0.5f })
    }

    @Test
    fun `format transitions remain capped to the requested output window`() {
        val processor = StreamingPcmAccumulator(maximumSamples = 200)
        processor.updateFormat(inputSampleRate = 48_000, channelCount = 1)
        processor.process(FloatArray(480) { 0.25f })
        processor.updateFormat(inputSampleRate = 16_000, channelCount = 2)
        processor.process(FloatArray(160 * 2) { 0.5f })

        assertEquals(200, processor.finish().size)
    }
}
