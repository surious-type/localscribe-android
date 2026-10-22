package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.HardwareProfile
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.ModelDescriptor
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.ModelQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfilesTest {
    private val device = HardwareProfile("device", 8_000, 3_000, 4, emptyList(), 35)

    private fun descriptor(
        id: String,
        quality: ModelQuality,
        bytes: Long,
        kind: ModelKind = ModelKind.TRANSCRIPTION,
    ) = ModelDescriptor(
        id = id,
        displayName = id,
        version = "1",
        downloadUrl = "https://example.invalid/model",
        sha256 = "hash-$id",
        downloadBytes = bytes,
        installedBytes = bytes,
        languages = setOf("multilingual"),
        quality = quality,
        kind = kind,
    )

    private fun installed(id: String) =
        InstalledModel(id, id, id, "/private/$id", "hash-$id", 10L, 1L, 1L)

    private val catalog =
        listOf(
            descriptor("tiny", ModelQuality.LOW, 10),
            descriptor("small", ModelQuality.BALANCED, 100),
            descriptor("medium", ModelQuality.HIGH, 1_000),
            descriptor("silero", ModelQuality.LOW, 1, ModelKind.VAD),
        )

    @Test
    fun speedBalanceAccuracyRespectOnlyInstalledTranscriptionModels() {
        val available = listOf(installed("tiny"), installed("small"), installed("silero"))
        assertEquals(
            "tiny",
            recommendInstalledProfile(
                RecognitionProfile.SPEED,
                catalog,
                available,
                emptyList(),
                device,
            ).model?.descriptorId,
        )
        assertEquals(
            "small",
            recommendInstalledProfile(
                RecognitionProfile.BALANCE,
                catalog,
                available,
                emptyList(),
                device,
            ).model?.descriptorId,
        )
        assertEquals(
            "small",
            recommendInstalledProfile(
                RecognitionProfile.ACCURACY,
                catalog,
                available,
                emptyList(),
                device,
            ).model?.descriptorId,
        )
    }

    @Test
    fun absentModelsAreNotPretendedAvailable() {
        val choice =
            recommendInstalledProfile(
                RecognitionProfile.ACCURACY,
                catalog,
                emptyList(),
                emptyList(),
                device,
            )
        assertNull(choice.model)
        assertTrue(choice.reason.contains("Скачайте"))
    }

    @Test
    fun measuredRtfIsUsedOnlyForSameDeviceAndExactArtifact() {
        val model = installed("tiny")
        val benchmark =
            BenchmarkRecord(
                id = "b",
                deviceId = "device",
                modelId = "tiny",
                modelHash = model.sha256,
                config = InferenceConfig(4),
                audioDurationMs = 1_000,
                processingDurationMs = 400,
                realTimeFactor = 0.4,
                realTimeMultiplier = 2.5,
                createdAtEpochMs = 1,
            )
        val matching =
            recommendInstalledProfile(
                RecognitionProfile.SPEED,
                catalog,
                listOf(model),
                listOf(benchmark),
                device,
            )
        assertTrue(matching.reason.contains("0.40"))
        val differentDevice =
            recommendInstalledProfile(
                RecognitionProfile.SPEED,
                catalog,
                listOf(model),
                listOf(benchmark),
                device.copy(deviceId = "another"),
            )
        assertTrue(differentDevice.reason.contains("ещё не измерена"))
    }
}
