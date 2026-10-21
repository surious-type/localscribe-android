package io.github.surioustype.localscribe.ui.playback

import android.content.Context

/** Durable per-recording resume position and device-local reader speed. */
class TranscriptPlaybackPreferences(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("transcript_playback", Context.MODE_PRIVATE)

    fun position(jobId: String): Long = prefs.getLong("position:$jobId", 0L).coerceAtLeast(0L)

    fun savePosition(jobId: String, positionMs: Long) {
        prefs.edit().putLong("position:$jobId", positionMs.coerceAtLeast(0L)).apply()
    }

    var speed: Float
        get() = prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)
        set(value) {
            prefs.edit().putFloat("speed", value.coerceIn(0.5f, 2f)).apply()
        }
}
