package io.github.surioustype.localscribe.benchmark

import android.content.res.AssetManager
import io.github.surioustype.localscribe.core.model.DemoSample
import io.github.surioustype.localscribe.core.model.WHISPER_SAMPLE_RATE_HZ
import io.github.surioustype.localscribe.core.ports.DemoAudioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

fun interface DemoAssetReader {
    fun open(path: String): InputStream
}

class AndroidAssetReader(assetManager: AssetManager) : DemoAssetReader {
    private val assets = assetManager

    override fun open(path: String): InputStream = assets.open(path)
}

class FileAssetReader(private val root: File) : DemoAssetReader {
    override fun open(path: String): InputStream = File(root, path).inputStream()
}

class MapAssetReader(private val entries: Map<String, ByteArray>) : DemoAssetReader {
    override fun open(path: String): InputStream = ByteArrayInputStream(entries.getValue(path))
}

class AssetDemoAudioRepository(
    private val assets: DemoAssetReader,
    private val manifestPath: String = "demo/manifest.json",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : DemoAudioRepository {
    private var manifest: Manifest? = null

    override fun observeSamples(): Flow<List<DemoSample>> =
        flowOf(
            loadManifest().fixtures.map(::toSample),
        )

    override suspend fun getSample(sampleId: String): DemoSample? =
        withContext(Dispatchers.IO) {
            loadManifest().fixtures.firstOrNull { it.id == sampleId }?.let(::toSample)
        }

    override suspend fun readPcm(sampleId: String): FloatArray =
        withContext(Dispatchers.IO) {
            val fixture =
                loadManifest().fixtures.firstOrNull { it.id == sampleId }
                    ?: throw IllegalArgumentException(
                        "Unknown or unavailable demo sample: $sampleId",
                    )
            require(
                fixture.sampleRateHz == WHISPER_SAMPLE_RATE_HZ &&
                    fixture.channels == 1 &&
                    fixture.encoding == "PCM_S16LE",
            ) {
                "Demo fixture $sampleId is not mono PCM16 at 16 kHz"
            }
            val wav = assets.open(fixture.audioAsset).use(InputStream::readBytes)
            check(sha256(wav).equals(fixture.sha256, ignoreCase = true)) {
                "Demo fixture checksum mismatch: $sampleId"
            }
            val decoded = decodeWav(wav)
            check(
                decoded.sampleRateHz == fixture.sampleRateHz &&
                    decoded.channels == fixture.channels &&
                    decoded.bitsPerSample == 16,
            ) {
                "Demo fixture format mismatch: $sampleId"
            }
            val durationMs = decoded.samples.size.toLong() * 1_000 / decoded.sampleRateHz
            check(durationMs == fixture.durationMs) { "Demo fixture duration mismatch: $sampleId" }
            decoded.samples
        }

    private fun loadManifest(): Manifest =
        manifest ?: assets.open(manifestPath).bufferedReader().use { reader ->
            parseManifest(reader.readText()).also {
                require(it.schemaVersion == 1) { "Unsupported demo manifest schema" }
                manifest = it
            }
        }

    private fun parseManifest(text: String): Manifest {
        val root = json.parseToJsonElement(text).jsonObject
        return Manifest(
            schemaVersion = root.int("schemaVersion"),
            fixtures =
                root.array("fixtures").map { entry ->
                    val fixture = entry.jsonObject
                    Fixture(
                        fixture.string("id"),
                        fixture.string("title"),
                        fixture.string("language"),
                        fixture.string("audioAsset"),
                        fixture.string("referenceAsset"),
                        fixture.long("durationMs"),
                        fixture.int("sampleRateHz"),
                        fixture.int("channels"),
                        fixture.string("encoding"),
                        fixture.string("sha256"),
                        fixture.string("rights"),
                        fixture.string("sourceUrl"),
                    )
                },
            unavailable =
                root.array("unavailable").map { entry ->
                    entry.jsonObject.let { Unavailable(it.string("language"), it.string("reason")) }
                },
        )
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = string(name).toInt()

    private fun JsonObject.long(name: String): Long = string(name).toLong()

    private fun JsonObject.array(name: String) = getValue(name).jsonArray

    private fun toSample(fixture: Fixture): DemoSample =
        DemoSample(
            id = fixture.id,
            displayName = fixture.title,
            uri = "asset://${fixture.audioAsset}",
            durationMs = fixture.durationMs,
            languageTags = setOf(fixture.language),
            referenceTranscript =
                assets.open(fixture.referenceAsset).bufferedReader().use {
                    it.readText().trim()
                },
            licenseName = fixture.rights,
            sourceUrl = fixture.sourceUrl,
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun decodeWav(bytes: ByteArray): DecodedWav {
        require(
            bytes.size >= 44 &&
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WAVE",
        ) {
            "Demo audio is not a WAV file"
        }
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var pcm: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val chunk = bytes.copyOfRange(offset, offset + 4).decodeToString()
            val size = littleEndianInt(bytes, offset + 4)
            val dataStart = offset + 8
            require(size >= 0 && dataStart + size <= bytes.size) { "Invalid WAV chunk" }
            when (chunk) {
                "fmt " -> {
                    require(
                        size >= 16 && littleEndianShort(bytes, dataStart) == 1,
                    ) { "WAV must be PCM" }
                    channels = littleEndianShort(bytes, dataStart + 2)
                    sampleRate = littleEndianInt(bytes, dataStart + 4)
                    bits = littleEndianShort(bytes, dataStart + 14)
                }
                "data" -> pcm = bytes.copyOfRange(dataStart, dataStart + size)
            }
            offset = dataStart + size + (size and 1)
        }
        val audio = requireNotNull(pcm) { "WAV data chunk missing" }
        require(channels == 1 && bits == 16 && audio.size % 2 == 0) { "WAV must be mono PCM16" }
        return DecodedWav(
            sampleRate,
            channels,
            bits,
            FloatArray(audio.size / 2) { index ->
                littleEndianShort(audio, index * 2).toFloat() / Short.MAX_VALUE
            },
        )
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or (bytes[offset + 3].toInt() shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()

    private data class DecodedWav(
        val sampleRateHz: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val samples: FloatArray,
    )

    private data class Manifest(
        val schemaVersion: Int,
        val fixtures: List<Fixture>,
        val unavailable: List<Unavailable>,
    )

    private data class Fixture(
        val id: String,
        val title: String,
        val language: String,
        val audioAsset: String,
        val referenceAsset: String,
        val durationMs: Long,
        val sampleRateHz: Int,
        val channels: Int,
        val encoding: String,
        val sha256: String,
        val rights: String,
        val sourceUrl: String,
    )

    private data class Unavailable(val language: String, val reason: String)
}
