package io.github.surioustype.localscribe.service

import android.content.Context
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

enum class QueuePriority(
    val label: String,
    val rank: Int,
) {
    HIGH("Высокий", 2),
    NORMAL("Обычный", 1),
    LOW("Низкий", 0),
}

/**
 * Only pending jobs are scheduled automatically. Paused jobs require a deliberate resume.
 * Job and chunk states remain in Room; priorities are small, local-only durable preferences.
 */
fun pendingTranscriptionQueue(
    jobs: List<TranscriptionJob>,
    priorities: Map<String, QueuePriority>,
): List<TranscriptionJob> =
    jobs
        .asSequence()
        .filter { it.status == JobStatus.PENDING }
        .sortedWith(
            compareByDescending<TranscriptionJob> {
                (priorities[it.id] ?: QueuePriority.NORMAL).rank
            }.thenBy { it.createdAtEpochMs }
                .thenBy { it.id },
        ).toList()

class DurableQueuePriorities(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "transcription_queue_priorities",
            Context.MODE_PRIVATE,
        )
    private val mutablePriorities =
        MutableStateFlow(
            preferences.all
                .mapNotNull { (jobId, raw) ->
                    val priority =
                        (raw as? String)?.let { name ->
                            QueuePriority.entries.firstOrNull { it.name == name }
                        }
                    if (priority != null) jobId to priority else null
                }.toMap(),
        )
    val priorities: StateFlow<Map<String, QueuePriority>> = mutablePriorities.asStateFlow()

    /** Synchronous commit ensures the service sees the choice immediately after a UI tap. */
    fun set(jobId: String, priority: QueuePriority) {
        require(jobId.isNotBlank())
        val editor = preferences.edit()
        if (priority == QueuePriority.NORMAL) {
            editor.remove(jobId)
        } else {
            editor.putString(jobId, priority.name)
        }
        if (!editor.commit()) throw IOException("Could not save transcription queue priority")
        mutablePriorities.value =
            if (priority == QueuePriority.NORMAL) {
                mutablePriorities.value - jobId
            } else {
                mutablePriorities.value + (jobId to priority)
            }
    }
}
