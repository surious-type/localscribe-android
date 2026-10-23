package io.github.surioustype.localscribe.ui

import android.content.Context

/** A stable segment anchor is more reliable than a raw LazyColumn index after headers change. */
data class TranscriptReadingPosition(
    val segmentId: String?,
    val offsetPx: Int,
)

/** Private, per-version reading progress; independent of playback position. */
class TranscriptReadingPositionStore(context: Context) {
    private val storage =
        context.applicationContext.getSharedPreferences(
            "transcript_reading_positions",
            Context.MODE_PRIVATE,
        )

    fun read(jobId: String): TranscriptReadingPosition? {
        val segmentKey = "$jobId:segment"
        if (!storage.contains(segmentKey)) return null
        return TranscriptReadingPosition(
            segmentId = storage.getString(segmentKey, null)?.takeIf { it.isNotEmpty() },
            offsetPx = storage.getInt("$jobId:offset", 0).coerceAtLeast(0),
        )
    }

    fun save(
        jobId: String,
        segmentId: String?,
        offsetPx: Int,
    ) {
        require(jobId.isNotBlank())
        storage
            .edit()
            .putString("$jobId:segment", segmentId.orEmpty())
            .putInt("$jobId:offset", offsetPx.coerceAtLeast(0))
            .apply()
    }
}

/** Do not unexpectedly jump to the first timestamp when the player is paused. */
internal fun shouldAutoFollowTranscript(
    followPlayback: Boolean,
    isPlaying: Boolean,
    activeSegmentIndex: Int?,
): Boolean = followPlayback && isPlaying && activeSegmentIndex != null
