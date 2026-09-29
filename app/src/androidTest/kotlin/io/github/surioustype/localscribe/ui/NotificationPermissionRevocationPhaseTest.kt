package io.github.surioustype.localscribe.ui

import android.Manifest
import android.content.Context
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
import io.github.surioustype.localscribe.core.model.JobStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host-controlled regression harness for revoking POST_NOTIFICATIONS between instrumentation
 * processes. Android terminates the target process during `adb shell pm revoke`, so revocation
 * intentionally happens outside instrumentation.
 *
 * Run exactly one phase with `-e notificationRevokePhase PREP` or `VERIFY`. With no phase this
 * class skips, keeping broad device runs from creating durable fixture jobs.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NotificationPermissionRevocationPhaseTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun showHome() {
        rule.runOnUiThread {
            rule.activity
                .getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("onboarding_complete", true)
                .commit()
        }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("Audio").assertIsDisplayed()
    }

    @Test
    fun runsHostSelectedPhase() {
        when (phase) {
            PREP -> preparePausedMissingModelJob()
            VERIFY -> verifyRevokedPermissionKeepsPreparedJobPaused()
            else -> assumeTrue("Set notificationRevokePhase to PREP or VERIFY", false)
        }
    }

    private fun preparePausedMissingModelJob() {
        assertEquals(
            "Grant POST_NOTIFICATIONS from the host before PREP.",
            PackageManager.PERMISSION_GRANTED,
            notificationPermission(),
        )
        val jobId = createPausedMissingModelJob(graph)
        assertEquals(JobStatus.PAUSED, jobStatus(jobId))
        preferences.edit().putString(PREPARED_JOB_ID, jobId).commit()
    }

    private fun verifyRevokedPermissionKeepsPreparedJobPaused() {
        assertEquals(
            "Revoke POST_NOTIFICATIONS from the host between PREP and VERIFY.",
            PackageManager.PERMISSION_DENIED,
            notificationPermission(),
        )
        val jobId = preferences.getString(PREPARED_JOB_ID, null)
        assertNotNull("PREP did not persist a job id.", jobId)
        val preparedJobId = requireNotNull(jobId)
        assertEquals(JobStatus.PAUSED, jobStatus(preparedJobId))

        tapNewestResume()
        clickPermissionOption { text -> text.normalized().contains("don't allow") }

        rule
            .onNodeWithText(
                "Notification permission is needed for visible transcription controls. Allow it and resume again.",
            ).assertIsDisplayed()
        assertEquals(JobStatus.PAUSED, jobStatus(preparedJobId))
    }

    private fun notificationPermission(): Int =
        rule.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)

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
                    node.isClickable && matches(node.text?.toString().orEmpty())
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

    private val phase get() = InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)

    private val preferences get() =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val graph get() = (rule.activity.application as LocalScribeApplication).graph

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private companion object {
        const val PHASE_ARGUMENT = "notificationRevokePhase"
        const val PREP = "PREP"
        const val VERIFY = "VERIFY"
        const val PREFERENCES = "notification_permission_revocation_test"
        const val PREPARED_JOB_ID = "prepared_job_id"
    }
}
