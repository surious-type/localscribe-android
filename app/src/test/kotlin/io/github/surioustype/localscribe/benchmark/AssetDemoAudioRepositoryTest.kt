package io.github.surioustype.localscribe.benchmark

import io.github.surioustype.localscribe.core.model.WHISPER_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssetDemoAudioRepositoryTest {
    @Test
    fun `real fixture manifest decodes verified mono 16 kilohertz pcm and reference`() =
        runTest {
            val assets = FileAssetReader(File("src/main/assets"))
            val repository = AssetDemoAudioRepository(assets)

            val sample = requireNotNull(repository.getSample("english_jfk_37s"))
            val pcm = repository.readPcm(sample.id)

            assertEquals(37_250, sample.durationMs)
            assertEquals(WHISPER_SAMPLE_RATE_HZ * 37.25f, pcm.size.toFloat(), 0f)
            assertTrue(sample.referenceTranscript.startsWith("The world is very different now."))
            assertEquals("Public domain: U.S. federal government official work", sample.licenseName)
        }

    @Test
    fun `unavailable languages are not represented as runnable samples`() =
        runTest {
            val repository = AssetDemoAudioRepository(FileAssetReader(File("src/main/assets")))

            assertEquals(null, repository.getSample("russian"))
            var unavailable = false
            try {
                repository.readPcm("mixed")
            } catch (
                _: IllegalArgumentException,
            ) {
                unavailable = true
            }
            assertTrue(unavailable)
        }

    @Test
    fun `checksum mismatch rejects an asset before decoding`() =
        runTest {
            val manifest =
                """
                {
                  "schemaVersion": 1,
                  "fixtures": [{
                    "id": "one", "title": "one", "language": "en", "audioAsset": "one.wav",
                    "referenceAsset": "one.txt", "durationMs": 1000, "sampleRateHz": 16000,
                    "channels": 1, "encoding": "PCM_S16LE", "sha256": "deadbeef", "rights": "PD",
                    "sourceUrl": "https://example.test"
                  }],
                  "unavailable": []
                }
                """.trimIndent().toByteArray()
            val assets =
                MapAssetReader(
                    mapOf(
                        "demo/manifest.json" to manifest,
                        "one.wav" to wav(16_000),
                        "one.txt" to "reference".toByteArray(),
                    ),
                )
            val repository = AssetDemoAudioRepository(assets)

            var rejected = false
            try {
                repository.readPcm("one")
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
        }

    private fun wav(sampleCount: Int): ByteArray {
        val dataLength = sampleCount * 2
        return ByteArray(44 + dataLength).also { bytes ->
            fun putInt(offset: Int, value: Int) {
                (0 until 4).forEach {
                    bytes[offset + it] =
                        (value ushr (it * 8)).toByte()
                }
            }

            fun putShort(offset: Int, value: Int) {
                (0 until 2).forEach {
                    bytes[offset + it] =
                        (value ushr (it * 8)).toByte()
                }
            }
            "RIFF".encodeToByteArray().copyInto(bytes, 0)
            putInt(4, 36 + dataLength)
            "WAVEfmt ".encodeToByteArray().copyInto(bytes, 8)
            putInt(16, 16)
            putShort(20, 1)
            putShort(22, 1)
            putInt(24, 16_000)
            putInt(28, 32_000)
            putShort(32, 2)
            putShort(34, 16)
            "data".encodeToByteArray().copyInto(bytes, 36)
            putInt(40, dataLength)
        }
    }
}
