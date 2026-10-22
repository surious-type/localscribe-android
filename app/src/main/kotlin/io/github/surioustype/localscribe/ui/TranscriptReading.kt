package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionJob

enum class TranscriptReadingMode {
    CONTINUOUS,
    TIMECODES,
}

/**
 * The primary choice never points at an incomplete or failed rerun.
 * With no user override, the latest completed attempt becomes primary automatically.
 */
fun preferredCompletedTranscript(
    jobs: List<TranscriptionJob>,
    sourceId: String,
    preferredJobId: String?,
): TranscriptionJob? {
    val completed = completedTranscriptionVersions(jobs, sourceId)
    return completed.firstOrNull { it.id == preferredJobId } ?: completed.firstOrNull()
}

data class TranscriptVersionChange(
    val startMs: Long,
    val before: String,
    val after: String,
)

/**
 * Align independently generated timestamps in 30-second audio windows.
 * Unlike segment-by-segment comparisons, this remains useful when models
 * split the same sentence into different segment boundaries. Only changed
 * windows are returned; no old transcript is ever modified.
 */
fun compareTranscriptVersions(
    before: List<TranscriptSegment>,
    after: List<TranscriptSegment>,
    windowMs: Long = 30_000L,
): List<TranscriptVersionChange> {
    require(windowMs > 0L)
    fun windows(segments: List<TranscriptSegment>): Map<Long, String> =
        segments
            .filter { it.absoluteStartMs >= 0 && !it.id.startsWith("preview-") }
            .groupBy { it.absoluteStartMs / windowMs }
            .mapValues { (_, rows) ->
                rows.sortedWith(compareBy<TranscriptSegment> { it.absoluteStartMs }.thenBy { it.id })
                    .joinToString(" ") { it.text.trim() }
                    .trim()
                    .replace(Regex("\\s+"), " ")
            }
    val earlier = windows(before)
    val later = windows(after)
    return (earlier.keys + later.keys)
        .sorted()
        .mapNotNull { window ->
            val oldText = earlier[window].orEmpty()
            val newText = later[window].orEmpty()
            if (oldText == newText) null else {
                TranscriptVersionChange(window * windowMs, oldText, newText)
            }
        }
}

/** Both reading modes share the same search selection and immutable timestamps. */
fun searchableTranscriptSegments(
    segments: List<TranscriptSegment>,
    query: String,
): List<TranscriptSegment> =
    if (query.isBlank()) segments else segments.filter {
        it.text.contains(query.trim(), ignoreCase = true)
    }
