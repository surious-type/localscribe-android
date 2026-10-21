package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.data.TranscriptSearchRow

/** Matches Unicode text in memory: SQLite's built-in LIKE is not Cyrillic-case-aware. */
data class LibrarySearchHit(
    val record: AudioSourceRecord,
    val jobId: String?,
    val startMs: Long?,
    val snippet: String?,
)

fun searchLibrary(
    sources: List<AudioSourceRecord>,
    segments: List<TranscriptSearchRow>,
    query: String,
): List<LibrarySearchHit> {
    val phrase = query.trim()
    if (phrase.isEmpty()) return emptyList()
    val sourceById = sources.associateBy { it.source.id }
    val titles =
        sources
            .filter {
                (it.customTitle ?: it.source.displayName).contains(phrase, ignoreCase = true) ||
                    it.source.displayName.contains(phrase, ignoreCase = true)
            }.map { LibrarySearchHit(it, null, null, null) }
    val matches =
        segments
            .asSequence()
            .mapNotNull { segment ->
                val source = sourceById[segment.sourceId] ?: return@mapNotNull null
                val start = segment.text.indexOf(phrase, ignoreCase = true)
                if (start < 0) return@mapNotNull null
                val from = (start - 48).coerceAtLeast(0)
                val to = (start + phrase.length + 75).coerceAtMost(segment.text.length)
                LibrarySearchHit(
                    source,
                    segment.jobId,
                    segment.startMs,
                    (if (from > 0) "…" else "") +
                        segment.text.substring(from, to).trim() +
                        (if (to < segment.text.length) "…" else ""),
                )
            }.take(100)
            .toList()
    return (titles + matches).take(100)
}

fun libraryTitle(record: AudioSourceRecord): String =
    record.customTitle ?: record.source.displayName

fun sortLibrary(sources: List<AudioSourceRecord>, order: String): List<AudioSourceRecord> =
    when (order) {
        "title" -> sources.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { libraryTitle(it) })
        "long" -> sources.sortedByDescending { it.source.durationMs }
        "short" -> sources.sortedBy { it.source.durationMs }
        "recorded" ->
            sources.sortedWith(
                compareByDescending<AudioSourceRecord> { it.recordedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { libraryTitle(it) },
            )
        else ->
            sources.sortedWith(
                compareByDescending<AudioSourceRecord> { it.addedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { libraryTitle(it) },
            )
    }
