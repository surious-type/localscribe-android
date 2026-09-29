package io.github.surioustype.localscribe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.LocalScribeApplication
import io.github.surioustype.localscribe.MainActivity
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.data.RoomAudioSourceStore
import io.github.surioustype.localscribe.di.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises Android 13's notification dialog from the actual Resume control. The granted case
 * deliberately uses no installed model, so the service pauses with a rendered, user-safe
 * dependency failure before inference or a model download can begin.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationResumePermissionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun showHome() {
        rule.runOnUiThread {
            rule.activity
                .getSharedPreferences("ui_preferences", 0)
                .edit()
                .putBoolean("onboarding_complete", true)
                .commit()
        }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Audio").assertIsDisplayed()
    }

    @Test
    fun deniedResumeKeepsPausedJobAndShowsPermissionGuidance() {
        val jobId = createPausedJob()
        assertNotificationPermissionDenied()

        tapNewestResume()
        clickPermissionOption { text ->
            text.normalized().contains("allow") &&
                text.normalized() != "allow"
        }

        rule
            .onNodeWithText(
                "Notification permission is needed for visible transcription controls. Allow it and resume again.",
            ).assertIsDisplayed()
        assertEquals(JobStatus.PAUSED, jobStatus(jobId))
    }

    @Test
    fun grantedResumeRunsWithoutInferenceAndRendersSanitizedFailure() {
        val jobId = createPausedJob()
        assertNotificationPermissionDenied()

        tapNewestResume()
        clickPermissionOption { text -> text.normalized() == "allow" }

        rule.waitUntil(10_000) {
            jobStatus(jobId) == JobStatus.PAUSED &&
                jobFailureCode(jobId) == FailureCode.MODEL_CORRUPTED
        }
        rule.onNodeWithText("The selected model needs to be downloaded again.").assertIsDisplayed()
        assertEquals(JobStatus.PAUSED, jobStatus(jobId))
    }

    private fun createPausedJob(): String {
        val id = createPausedMissingModelJob(graph)
        rule.waitUntil(5_000) { jobStatus(id) == JobStatus.PAUSED }
        return id
    }

    private fun assertNotificationPermissionDenied() {
        assertEquals(
            "Run this class from Android's default denied notification state. " +
                "case has separate host-controlled phases.",
            PackageManager.PERMISSION_DENIED,
            rule.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun tapNewestResume() {
        rule.waitUntil(
            5_000,
        ) { rule.onAllNodesWithText("Resume").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Resume").onFirst().performClick()
    }

    private fun clickPermissionOption(matches: (String) -> Boolean) {
        repeat(50) {
            automation.rootInActiveWindow
                ?.findDescendant { node ->
                    node.isClickable &&
                        matches(node.text?.toString().orEmpty())
                }?.let { node ->
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            SystemClock.sleep(100)
        }
        throw AssertionError("Notification permission dialog option was not displayed")
    }

    private fun AccessibilityNodeInfo.findDescendant(
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (matches(this)) return this
        for (index in 0 until childCount) {
            getChild(index)?.findDescendant(matches)?.let { return it }
        }
        return null
    }

    private fun String.normalized(): String = lowercase().replace('’', '\'')

    private fun jobStatus(jobId: String): JobStatus? =
        runBlocking { graph.transcriptionRepository.getJob(jobId)?.status }

    private fun jobFailureCode(jobId: String): FailureCode? =
        runBlocking {
            graph.transcriptionRepository
                .getJob(jobId)
                ?.failure
                ?.code
        }

    private val graph get() = (rule.activity.application as LocalScribeApplication).graph

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
}

internal fun createPausedMissingModelJob(graph: AppGraph): String =
    runBlocking {
        val now = System.currentTimeMillis()
        val sourceId = "notification-permission-source-${UUID.randomUUID()}"
        val jobId = "notification-permission-job-${UUID.randomUUID()}"
        RoomAudioSourceStore(graph.database).upsert(
            AudioSourceRecord(
                source =
                    AudioSource(
                        id = sourceId,
                        uri = "content://notification-permission-test/$sourceId",
                        displayName = "Notification permission test audio",
                        durationMs = 1_000,
                        mimeType = "audio/wav",
                    ),
                accessStatus = SourceAccessStatus.AVAILABLE,
                hasPersistedPermission = true,
            ),
        )
        graph.transcriptionRepository.createJob(
            TranscriptionJob(
                id = jobId,
                sourceId = sourceId,
                config =
                    TranscriptionConfig(
                        modelId = "tiny",
                        inference = InferenceConfig(threadCount = 1),
                    ),
                modelHash = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
                status = JobStatus.PENDING,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
            chunks =
                listOf(
                    TranscriptionChunk(
                        id = "$jobId:chunk",
                        jobId = jobId,
                        modelId = "tiny",
                        startMs = 0,
                        endMs = 1_000,
                        status = ChunkStatus.PENDING,
                        attempt = 0,
                        createdAtEpochMs = now,
                    ),
                ),
        )
        graph.transcriptionRepository.resumeJob(jobId, now)
        graph.transcriptionRepository.pauseJob(jobId, now)
        jobId
    }
