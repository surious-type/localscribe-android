package io.github.surioustype.localscribe.ui

import android.content.Context
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Per-job durable, private edits. The authoritative transcript in Room is never modified.
 * A single SharedPreferences commit atomically publishes a job's set of corrections.
 * No network, backup export, or permissions are involved.
 */
class TranscriptCorrectionStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("transcript_corrections", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val observed = mutableMapOf<String, MutableStateFlow<Map<String, TranscriptCorrection>>>()

    fun observe(jobId: String): StateFlow<Map<String, TranscriptCorrection>> = mutable(jobId)

    private fun mutable(jobId: String): MutableStateFlow<Map<String, TranscriptCorrection>> =
        synchronized(observed) {
            observed.getOrPut(jobId) { MutableStateFlow(read(jobId)) }
        }

    suspend fun save(jobId: String, original: TranscriptSegment, editedText: String) {
        require(jobId.isNotBlank() && !original.id.startsWith("preview-"))
        require(editedText.isNotBlank() && editedText.length <= MAX_EDIT_LENGTH)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val state = mutable(jobId)
                val next = state.value.toMutableMap()
                if (editedText == original.text) {
                    next.remove(original.id)
                } else {
                    next[original.id] = TranscriptCorrection(original.text, editedText)
                }
                val json = JSONObject()
                next.forEach { (id, edit) ->
                    json.put(
                        id,
                        JSONObject().put("original", edit.originalText).put("edited", edit.editedText),
                    )
                }
                if (!preferences.edit().putString(jobId, json.toString()).commit()) {
                    throw IOException("Could not save transcript correction")
                }
                state.value = next.toMap()
            }
        }
    }

    suspend fun restore(jobId: String, segmentId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val state = mutable(jobId)
                if (segmentId !in state.value) return@withLock
                val next = state.value.toMutableMap().apply { remove(segmentId) }
                val json = JSONObject()
                next.forEach { (id, edit) ->
                    json.put(
                        id,
                        JSONObject().put("original", edit.originalText).put("edited", edit.editedText),
                    )
                }
                if (!preferences.edit().putString(jobId, json.toString()).commit()) {
                    throw IOException("Could not restore original transcript")
                }
                state.value = next.toMap()
            }
        }
    }

    private fun read(jobId: String): Map<String, TranscriptCorrection> =
        runCatching {
            val json = JSONObject(preferences.getString(jobId, "{}") ?: "{}")
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val segmentId = keys.next()
                    val edit = json.getJSONObject(segmentId)
                    put(
                        segmentId,
                        TranscriptCorrection(
                            originalText = edit.getString("original"),
                            editedText = edit.getString("edited"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_EDIT_LENGTH = 50_000
    }
}
