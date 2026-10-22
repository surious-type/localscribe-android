package io.github.surioustype.localscribe.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Private, durable user-selected primary transcript version per audio recording. */
class TranscriptionVersionPreferences(context: Context) {
    private val storage =
        context.applicationContext.getSharedPreferences(
            "transcription_primary_versions",
            Context.MODE_PRIVATE,
        )

    private val selected = MutableStateFlow(
        storage.all.mapNotNull { (key, value) ->
            if (key.startsWith(PREFIX) && value is String) {
                key.removePrefix(PREFIX) to value
            } else {
                null
            }
        }.toMap(),
    )

    val selectedVersions: StateFlow<Map<String, String>> = selected.asStateFlow()

    fun setPrimary(sourceId: String, completedJobId: String) {
        require(sourceId.isNotBlank() && completedJobId.isNotBlank())
        if (!storage.edit().putString(PREFIX + sourceId, completedJobId).commit()) {
            throw IOException("Could not save the primary transcription version")
        }
        selected.value = selected.value + (sourceId to completedJobId)
    }

    private companion object {
        const val PREFIX = "primary:"
    }
}
