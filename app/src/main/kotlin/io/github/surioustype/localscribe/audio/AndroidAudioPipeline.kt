package io.github.surioustype.localscribe.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.WHISPER_SAMPLE_RATE_HZ
import io.github.surioustype.localscribe.core.ports.AudioPipeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

class AndroidAudioPipeline(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maximumWindowDurationMs: Long = 120_000,
) : AudioPipeline {
    private val applicationContext = context.applicationContext

    override suspend fun readWindow(source: AudioSource, startMs: Long, endMs: Long): FloatArray =
        withContext(ioDispatcher) {
            require(startMs >= 0) { "startMs must be non-negative" }
            require(endMs > startMs) { "endMs must be greater than startMs" }
            require(
                endMs - startMs <= maximumWindowDurationMs,
            ) { "requested window exceeds duration limit" }
            val maximumSamples = ceil((endMs - startMs) * WHISPER_SAMPLE_RATE_HZ / 1_000.0).toInt()
            try {
                decode(Uri.parse(source.uri), startMs * 1_000, endMs * 1_000, maximumSamples)
            } catch (error: AudioSourceException) {
                throw error
            } catch (error: SecurityException) {
                throw failure(
                    FailureCode.SOURCE_PERMISSION_REQUIRED,
                    "source_permission_required",
                    error,
                )
            } catch (error: FileNotFoundException) {
                throw failure(FailureCode.SOURCE_MISSING, "source_missing", error)
            } catch (error: OutOfMemoryError) {
                throw failure(FailureCode.INSUFFICIENT_MEMORY, "audio_decode_out_of_memory", error)
            } catch (error: IOException) {
                throw failure(FailureCode.SOURCE_MISSING, "audio_source_unreadable", error)
            } catch (error: RuntimeException) {
                throw failure(FailureCode.UNSUPPORTED_CODEC, "audio_decode_failed", error)
            }
        }

    private fun decode(uri: Uri, startUs: Long, endUs: Long, maximumSamples: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(applicationContext, uri, null)
            val trackIndex =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor
                        .getTrackFormat(
                            index,
                        ).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") ==
                        true
                } ?: throw failure(FailureCode.UNSUPPORTED_CODEC, "audio_track_missing")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime =
                inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw failure(FailureCode.UNSUPPORTED_CODEC, "audio_mime_missing")
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            return decodeLoop(extractor, codec, startUs, endUs, maximumSamples)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun decodeLoop(
        extractor: MediaExtractor,
        codec: MediaCodec,
        startUs: Long,
        endUs: Long,
        maximumSamples: Int,
    ): FloatArray {
        var inputEnded = false
        var outputEnded = false
        var outputFormat: PcmFormat? = null
        val output = StreamingPcmAccumulator(maximumSamples)
        val bufferInfo = MediaCodec.BufferInfo()

        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer =
                        codec.getInputBuffer(inputIndex)
                            ?: throw failure(
                                FailureCode.UNSUPPORTED_CODEC,
                                "decoder_input_buffer_missing",
                            )
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val changed = PcmFormat.from(codec.outputFormat)
                    outputFormat = changed
                    output.updateFormat(changed.sampleRate, changed.channelCount)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else ->
                    if (outputIndex >= 0) {
                        val format =
                            outputFormat ?: PcmFormat.from(codec.outputFormat).also {
                                outputFormat = it
                                output.updateFormat(it.sampleRate, it.channelCount)
                            }
                        val isCodecConfig =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isCodecConfig && bufferInfo.size > 0) {
                            val buffer =
                                codec.getOutputBuffer(outputIndex)
                                    ?: throw failure(
                                        FailureCode.UNSUPPORTED_CODEC,
                                        "decoder_output_buffer_missing",
                                    )
                            val selected = selectWindow(buffer, bufferInfo, format, startUs, endUs)
                            if (selected.isNotEmpty()) {
                                output.process(selected)
                            }
                        }
                        outputEnded =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 ||
                            bufferEndUs(bufferInfo, format) >= endUs
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
            }
        }
        val result = output.finish()
        SafePcmNormalizer.normalizeInPlace(result)
        return result
    }

    private fun selectWindow(
        source: ByteBuffer,
        info: MediaCodec.BufferInfo,
        format: PcmFormat,
        startUs: Long,
        endUs: Long,
    ): FloatArray {
        val bytesPerFrame = format.bytesPerSample * format.channelCount
        val frameCount = info.size / bytesPerFrame
        val bufferStartUs = info.presentationTimeUs
        val startFrame =
            ceilFrames(
                startUs - bufferStartUs,
                format.sampleRate,
            ).coerceIn(0, frameCount)
        val endFrame =
            ceilFrames(
                endUs - bufferStartUs,
                format.sampleRate,
            ).coerceIn(startFrame, frameCount)
        if (endFrame <= startFrame) return FloatArray(0)
        val buffer = source.duplicate().order(ByteOrder.nativeOrder())
        buffer.position(info.offset + startFrame * bytesPerFrame)
        val samples = FloatArray((endFrame - startFrame) * format.channelCount)
        for (index in samples.indices) {
            samples[index] =
                when (format.encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                    else -> throw failure(FailureCode.UNSUPPORTED_CODEC, "unsupported_pcm_encoding")
                }
        }
        return samples
    }

    private fun bufferEndUs(info: MediaCodec.BufferInfo, format: PcmFormat): Long {
        val frames = info.size / (format.bytesPerSample * format.channelCount)
        return info.presentationTimeUs + (frames * 1_000_000L / format.sampleRate)
    }

    private fun ceilFrames(deltaUs: Long, sampleRate: Int): Int {
        if (deltaUs <= 0) return 0
        return ((deltaUs * sampleRate + 999_999L) / 1_000_000L)
            .coerceAtMost(
                Int.MAX_VALUE.toLong(),
            ).toInt()
    }

    private fun failure(code: FailureCode, diagnostic: String, cause: Throwable? = null) =
        AudioSourceException(DomainFailure(code, diagnostic), diagnostic, cause)

    private data class PcmFormat(val sampleRate: Int, val channelCount: Int, val encoding: Int) {
        val bytesPerSample: Int
            get() =
                when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
                    AudioFormat.ENCODING_PCM_FLOAT -> Float.SIZE_BYTES
                    else -> throw IllegalArgumentException("unsupported PCM encoding")
                }

        companion object {
            fun from(format: MediaFormat): PcmFormat =
                PcmFormat(
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    encoding =
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        },
                )
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
