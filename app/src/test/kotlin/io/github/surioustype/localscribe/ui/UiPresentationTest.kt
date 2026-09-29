package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.AppRelease
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.ReleaseAsset
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPresentationTest {
    @Test
    fun `theme selection honors explicit dark preference and dynamic support`() {
        assertEquals(ThemeSelection.DYNAMIC_DARK, selectTheme(true, true, false, true))
        assertEquals(ThemeSelection.DARK, selectTheme(true, true, false, false))
        assertEquals(ThemeSelection.LIGHT, selectTheme(false, false, false, true))
    }

    @Test
    fun `appearance mode distinguishes system light and dark choices`() {
        assertEquals(ThemeSelection.DARK, selectTheme(AppearanceMode.SYSTEM, true, false, true))
        assertEquals(ThemeSelection.LIGHT, selectTheme(AppearanceMode.LIGHT, true, false, true))
        assertEquals(ThemeSelection.DARK, selectTheme(AppearanceMode.DARK, false, false, true))
    }

    @Test
    fun `github update state exposes release notes and download`() {
        val state =
            UpdateState(
                availability = UpdateAvailability.AVAILABLE,
                release =
                    AppRelease(
                        version = AppVersion("1.2.0"),
                        releaseNotes = "Notes",
                        publishedAtEpochMs = 1,
                        apk =
                            ReleaseAsset(
                                fileName = "app.apk",
                                downloadUrl = "https://example.invalid/app.apk",
                                bytes = 1,
                                sha256 = "sha",
                            ),
                    ),
            )

        val presentation = updatePresentation(state)

        assertEquals("available", presentation.status)
        assertEquals("Notes", presentation.releaseNotes)
        assertTrue(presentation.canDownload)
    }

    @Test
    fun `play update state is rendered as store managed without download`() {
        val presentation = updatePresentation(UpdateState(UpdateAvailability.DISABLED))

        assertEquals("store-managed", presentation.status)
        assertFalse(presentation.canDownload)
    }

    @Test fun `benchmark comparison uses latest compatible DAO records`() {
        val oldIncompatible = benchmark("old", "obsolete", "old-hash", 1)
        val currentLatest = benchmark("current-new", "tiny", "current-hash", 3)
        val currentEarlier = benchmark("current-earlier", "tiny", "current-hash", 2)

        val comparison =
            latestBenchmarkComparison(
                listOf(currentLatest, currentEarlier, oldIncompatible),
            )

        assertEquals(currentLatest, comparison.reference)
        assertEquals(listOf(currentLatest, currentEarlier), comparison.compatible)
    }

    private fun benchmark(id: String, modelId: String, hash: String, createdAt: Long) =
        BenchmarkRecord(
            id = id,
            deviceId = "device",
            modelId = modelId,
            modelHash = hash,
            config = InferenceConfig(4, "en"),
            audioDurationMs = 37_000,
            processingDurationMs = 1_000,
            realTimeFactor = 0.03,
            realTimeMultiplier = 37.0,
            createdAtEpochMs = createdAt,
            sampleId = "english_jfk_37s",
        )
}
