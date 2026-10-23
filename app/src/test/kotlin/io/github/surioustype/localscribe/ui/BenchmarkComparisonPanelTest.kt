package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkComparisonPanelTest {
    private fun installed(name: String, hash: String) =
        InstalledModel(name, name, name, "/private/$name", hash, 100L, 1L, 1L)

    private fun result(
        id: String,
        model: String,
        hash: String,
        created: Long = 1L,
    ) = BenchmarkRecord(
        id = id,
        deviceId = "phone",
        modelId = model,
        modelHash = hash,
        config = comparisonInferenceConfig(),
        audioDurationMs = 37_000L,
        processingDurationMs = 18_500L,
        realTimeFactor = 0.5,
        realTimeMultiplier = 2.0,
        approximatePeakMemoryBytes = 123L,
        createdAtEpochMs = created,
        sampleId = "english_jfk_37s",
        wordErrorRate = 0.25,
        characterErrorRate = 0.10,
    )

    @Test
    fun comparisonKeepsNewestPerInstalledArtifactUnderExactMatchingConditions() {
        val records =
            listOf(
                result("old", "tiny", "hash1"),
                result("latest", "tiny", "hash1", created = 5L),
                result("other", "small", "hash2"),
                result("removed", "tiny", "oldhash", created = 10L),
                result("foreign", "medium", "hash3"),
                result("other-device", "small", "hash2").copy(deviceId = "other"),
                result("wrong-language", "small", "hash2")
                    .copy(config = InferenceConfig(threadCount = 4, language = "ru")),
                result("wrong-sample", "small", "hash2")
                    .copy(sampleId = "different"),
            )
        val chosen =
            comparableBenchmarkHistory(
                records,
                listOf(installed("tiny", "hash1"), installed("small", "hash2")),
                "phone",
            )

        assertEquals(listOf("latest", "other"), chosen.map { it.id }.sorted())
    }

    @Test
    fun noComparisonAcrossDifferentHardwareOrMissingArtifact() {
        val records = listOf(result("sample", "tiny", "hash1"))
        assertEquals(
            emptyList<BenchmarkRecord>(),
            comparableBenchmarkHistory(records, emptyList(), "phone"),
        )
        assertEquals(
            emptyList<BenchmarkRecord>(),
            comparableBenchmarkHistory(
                records,
                listOf(installed("tiny", "hash1")),
                "different",
            ),
        )
    }
}
