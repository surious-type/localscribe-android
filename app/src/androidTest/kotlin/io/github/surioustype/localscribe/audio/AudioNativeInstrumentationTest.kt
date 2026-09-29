package io.github.surioustype.localscribe.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import io.github.surioustype.localscribe.engine.WhisperTranscriptionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AudioNativeInstrumentationTest {
    @Test
    fun decodesGeneratedStereoWavWindowToBoundedMono16k() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = File(context.cacheDir, "pipeline-stereo-48k.wav")
            writeStereoWav(fixture, sampleRate = 48_000, frameCount = 48_000)
            val source =
                AudioSource(
                    id = "fixture",
                    uri = Uri.fromFile(fixture).toString(),
                    displayName = "fixture.wav",
                    durationMs = 1_000,
                    mimeType = "audio/wav",
                )

            val pcm = AndroidAudioPipeline(context).readWindow(source, 250, 750)

            assertEquals(8_000, pcm.size)
            assertTrue(pcm.all { it in -1f..1f })
            assertEquals(0.2f, pcm[pcm.size / 2], 0.02f)
        }

    @Test
    fun nativeWhisperSmokeWithExternalVerifiedTinyModel() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val path = arguments.getString(MODEL_PATH_ARGUMENT)
            val expectedHash = arguments.getString(MODEL_HASH_ARGUMENT)
            assumeTrue(
                "Skipped: supply -e $MODEL_PATH_ARGUMENT <app-readable-path> and " +
                    "-e $MODEL_HASH_ARGUMENT <sha256>",
                !path.isNullOrBlank() && !expectedHash.isNullOrBlank(),
            )
            val modelFile = File(requireNotNull(path))
            assumeTrue(
                "Skipped: external model is not app-readable",
                modelFile.isFile && modelFile.canRead(),
            )
            val actualHash = sha256(modelFile)
            assertEquals(requireNotNull(expectedHash).lowercase(), actualHash)
            val model =
                InstalledModel(
                    id = "instrumentation-tiny",
                    descriptorId = "instrumentation-tiny",
                    displayName = "Instrumentation tiny model",
                    filePath = modelFile.path,
                    sha256 = actualHash,
                    bytes = modelFile.length(),
                    installedAtEpochMs = 0,
                    verifiedAtEpochMs = 0,
                )
            val engine = WhisperTranscriptionEngine()
            try {
                engine.loadModel(model)
                val result =
                    engine.transcribe(
                        pcm = FloatArray(WHISPER_SAMPLE_COUNT),
                        config = InferenceConfig(threadCount = 2, language = "en"),
                        prompt = null,
                        cancellationSignal = CancellationSignal { false },
                    )
                assertTrue(result.segments.all { it.startMs >= 0 && it.endMs >= it.startMs })
            } finally {
                engine.unloadModel()
            }
        }

    private fun writeStereoWav(file: File, sampleRate: Int, frameCount: Int) {
        val channelCount = 2
        val bitsPerSample = 16
        val bytesPerFrame = channelCount * bitsPerSample / 8
        val dataBytes = frameCount * bytesPerFrame
        FileOutputStream(file).use { output ->
            output.write("RIFF".toByteArray())
            output.writeLittleEndianInt(36 + dataBytes)
            output.write("WAVEfmt ".toByteArray())
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(channelCount)
            output.writeLittleEndianInt(sampleRate)
            output.writeLittleEndianInt(sampleRate * bytesPerFrame)
            output.writeLittleEndianShort(bytesPerFrame)
            output.writeLittleEndianShort(bitsPerSample)
            output.write("data".toByteArray())
            output.writeLittleEndianInt(dataBytes)
            repeat(frameCount) {
                output.writeLittleEndianShort((0.4f * Short.MAX_VALUE).toInt())
                output.writeLittleEndianShort((-0.2f * Short.MAX_VALUE).toInt())
            }
        }
    }

    private fun FileOutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun FileOutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MODEL_PATH_ARGUMENT = "whisperModelPath"
        const val MODEL_HASH_ARGUMENT = "whisperModelSha256"
        const val WHISPER_SAMPLE_COUNT = 16_000
    }
}
