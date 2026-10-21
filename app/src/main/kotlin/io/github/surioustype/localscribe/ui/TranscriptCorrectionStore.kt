package io.github.surioustype.localscribe.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Local, per-transcription corrections. Original Whisper segments are never modified.
 * A separate job id is created for each re-recognition, so earlier edits survive reruns.
 */
class TranscriptCorrectionStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("transcript_corrections", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val corrections: StateFlow<Map<String, String>> = mutable

    fun load(jobId: String) {
        val json = runCatching { JSONObject(preferences.getString(jobId, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        mutable.value = buildMap {
            json.keys().forEach { key -> put(key, json.optString(key)) }
        }
    }

    fun save(jobId: String, segmentId: String, original: String, revised: String) {
        val next = mutable.value.toMutableMap()
        if (revised == original) next.remove(segmentId) else next[segmentId] = revised
        val json = JSONObject()
        next.forEach { (key, value) -> json.put(key, value) }
        check(preferences.edit().putString(jobId, json.toString()).commit()) {
            "Не удалось сохранить исправления"
        }
        mutable.value = next
    }
}

/** Exact Whisper timestamps determine which segment is active, not list order. */
fun activeTranscriptSegmentId(
    segments: List<io.github.surioustype.localscribe.core.model.TranscriptSegment>,
    positionMs: Long,
): String? = segments.lastOrNull {
    positionMs >= it.absoluteStartMs && positionMs < it.absoluteEndMs
}?.id
