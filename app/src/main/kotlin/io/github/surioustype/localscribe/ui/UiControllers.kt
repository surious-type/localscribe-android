package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

enum class ThemeSelection { LIGHT, DARK, DYNAMIC_LIGHT, DYNAMIC_DARK }

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

fun selectTheme(
    appearanceMode: AppearanceMode,
    systemDark: Boolean,
    dynamicColors: Boolean,
    dynamicColorsSupported: Boolean,
): ThemeSelection =
    selectTheme(
        darkPreference = appearanceMode == AppearanceMode.DARK,
        dynamicColors = dynamicColors,
        systemDark = if (appearanceMode == AppearanceMode.SYSTEM) systemDark else false,
        dynamicColorsSupported = dynamicColorsSupported,
    )

fun selectTheme(
    darkPreference: Boolean,
    dynamicColors: Boolean,
    systemDark: Boolean,
    dynamicColorsSupported: Boolean,
): ThemeSelection {
    val dark = darkPreference || systemDark
    return when {
        dynamicColors && dynamicColorsSupported && dark -> ThemeSelection.DYNAMIC_DARK
        dynamicColors && dynamicColorsSupported -> ThemeSelection.DYNAMIC_LIGHT
        dark -> ThemeSelection.DARK
        else -> ThemeSelection.LIGHT
    }
}

data class UpdatePresentation(
    val status: String,
    val releaseNotes: String?,
    val canDownload: Boolean,
)

fun updatePresentation(state: UpdateState): UpdatePresentation =
    UpdatePresentation(
        status =
            when (state.availability) {
                UpdateAvailability.DISABLED -> "store-managed"
                else -> state.availability.name.lowercase()
            },
        releaseNotes = state.release?.releaseNotes,
        canDownload = state.availability == UpdateAvailability.AVAILABLE,
    )

/** One foreground-safe owner for either a quality or batch benchmark operation. */
class BenchmarkRunController(
    private val scope: CoroutineScope,
) {
    var isBusy: Boolean by mutableStateOf(false)
        private set
    private var activeJob: Job? = null

    fun start(block: suspend () -> Unit): Boolean {
        if (isBusy) return false
        isBusy = true
        val launchedJob =
            scope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    block()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // The caller owns user-visible failure state; retain controller ownership cleanup.
                } finally {
                    if (activeJob === owner) {
                        isBusy = false
                        activeJob = null
                    }
                }
            }
        activeJob = launchedJob
        launchedJob.start()
        return true
    }

    fun onStop() {
        activeJob?.cancel()
    }
}

enum class TranscriptionStartResult {
    STARTED,
    NOTIFICATION_PERMISSION_REQUIRED,
    SERVICE_START_FAILED,
}

object TranscriptionStartController {
    suspend fun start(
        notificationGranted: Boolean,
        createJob: suspend () -> Unit,
        launchForegroundService: () -> Unit,
        cancelJob: suspend () -> Unit,
    ): TranscriptionStartResult {
        if (!notificationGranted) return TranscriptionStartResult.NOTIFICATION_PERMISSION_REQUIRED
        return try {
            createJob()
            launchForegroundService()
            TranscriptionStartResult.STARTED
        } catch (_: Throwable) {
            runCatching { cancelJob() }
            TranscriptionStartResult.SERVICE_START_FAILED
        }
    }

    fun resume(
        notificationGranted: Boolean,
        launchForegroundService: () -> Unit,
    ): TranscriptionStartResult {
        if (!notificationGranted) return TranscriptionStartResult.NOTIFICATION_PERMISSION_REQUIRED
        return try {
            launchForegroundService()
            TranscriptionStartResult.STARTED
        } catch (_: Throwable) {
            TranscriptionStartResult.SERVICE_START_FAILED
        }
    }
}

/** Vertical actions remain reachable at phone widths and with large accessibility fonts. */
@Composable
fun ResponsiveActionButtons(
    labels: List<String>,
    enabled: (String) -> Boolean = { true },
    onAction: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { label ->
            OutlinedButton(
                onClick = { onAction(label) },
                enabled = enabled(label),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            ) { Text(label) }
        }
    }
}
