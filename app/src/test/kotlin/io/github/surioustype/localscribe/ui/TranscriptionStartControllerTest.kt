package io.github.surioustype.localscribe.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionStartControllerTest {
    @Test
    fun `notification denial creates no job`() =
        runTest {
            var created = false
            val result =
                TranscriptionStartController.start(
                    notificationGranted = false,
                    createJob = { created = true },
                    launchForegroundService = {},
                    cancelJob = {},
                )

            assertEquals(TranscriptionStartResult.NOTIFICATION_PERMISSION_REQUIRED, result)
            assertEquals(false, created)
        }

    @Test
    fun `notification approval creates then launches job`() =
        runTest {
            val events = mutableListOf<String>()

            val result =
                TranscriptionStartController.start(
                    notificationGranted = true,
                    createJob = { events += "create" },
                    launchForegroundService = { events += "launch" },
                    cancelJob = { events += "cancel" },
                )

            assertEquals(TranscriptionStartResult.STARTED, result)
            assertEquals(listOf("create", "launch"), events)
        }

    @Test
    fun `foreground launch failure cancels created job`() =
        runTest {
            val events = mutableListOf<String>()

            val result =
                TranscriptionStartController.start(
                    notificationGranted = true,
                    createJob = { events += "create" },
                    launchForegroundService = { error("background launch rejected") },
                    cancelJob = { events += "cancel" },
                )

            assertEquals(TranscriptionStartResult.SERVICE_START_FAILED, result)
            assertEquals(listOf("create", "cancel"), events)
        }

    @Test
    fun `resume launch failure preserves paused job`() {
        val result =
            TranscriptionStartController.resume(
                notificationGranted = true,
                launchForegroundService = { error("background launch rejected") },
            )

        assertEquals(TranscriptionStartResult.SERVICE_START_FAILED, result)
    }

    @Test
    fun `resume requires notification permission before foreground launch`() {
        var launched = false

        val result =
            TranscriptionStartController.resume(
                notificationGranted = false,
                launchForegroundService = { launched = true },
            )

        assertEquals(TranscriptionStartResult.NOTIFICATION_PERMISSION_REQUIRED, result)
        assertEquals(false, launched)
    }
}
