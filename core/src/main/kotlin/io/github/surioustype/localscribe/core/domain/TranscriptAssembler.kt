package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import java.text.Normalizer
import java.util.Locale

class TranscriptAssembler {
    fun assemble(
        chunks: List<TranscriptionChunk>,
        rawSegments: List<TranscriptSegment>,
    ): List<TranscriptSegment> {
        val chunkIds = chunks.mapTo(mutableSetOf()) { it.id }
        require(
            rawSegments.all {
                it.chunkId in chunkIds
            },
        ) { "Every segment must belong to a supplied chunk" }

        val output = mutableListOf<TranscriptSegment>()
        val orderedChunks =
            chunks.sortedWith(
                compareBy<TranscriptionChunk> {
                    it.startMs
                }.thenBy { it.endMs }.thenBy { it.id },
            )
        orderedChunks.forEachIndexed { index, chunk ->
            require(
                chunk.startMs >= 0 && chunk.endMs > chunk.startMs,
            ) { "Invalid chunk timestamps" }
            val currentSegments =
                rawSegments
                    .asSequence()
                    .filter { it.chunkId == chunk.id }
                    .sortedWith(segmentComparator)
                    .toList()
            currentSegments.forEach { requireValidSegment(it) }

            val previousChunk =
                orderedChunks
                    .subList(0, index)
                    .lastOrNull { previous ->
                        previous.startMs < chunk.endMs &&
                            chunk.startMs < previous.endMs
                    }
            if (previousChunk == null) {
                output += currentSegments
                return@forEachIndexed
            }

            val overlapStart = maxOf(previousChunk.startMs, chunk.startMs)
            val overlapEnd = minOf(previousChunk.endMs, chunk.endMs)
            val previousOverlap =
                output.filter { segment ->
                    segment.chunkId == previousChunk.id &&
                        segment.overlaps(overlapStart, overlapEnd)
                }
            val currentOverlap = currentSegments.filter { it.overlaps(overlapStart, overlapEnd) }
            val duplicateWords =
                matchingSuffixPrefixWordCount(
                    previousOverlap.flatMap(::timedWords),
                    currentOverlap.flatMap(::timedWords),
                )
            output += trimLeadingWords(currentSegments, duplicateWords)
        }
        return output.sortedWith(segmentComparator)
    }

    private fun matchingSuffixPrefixWordCount(
        previous: List<TimedWord>,
        current: List<TimedWord>,
    ): Int {
        val maximum = minOf(previous.size, current.size)
        for (length in maximum downTo 1) {
            val suffix = previous.takeLast(length)
            val prefix = current.take(length)
            if (!hasTemporalCorrespondence(suffix, prefix)) continue
            val suffixText = suffix.map(TimedWord::value)
            val prefixText = prefix.map(TimedWord::value)
            if (suffixText == prefixText) return length
            if (length >= MIN_FUZZY_WORDS &&
                editDistance(suffixText, prefixText) <= maxOf(1, length / 4)
            ) {
                return length
            }
        }
        return 0
    }

    private fun trimLeadingWords(
        segments: List<TranscriptSegment>,
        wordCount: Int,
    ): List<TranscriptSegment> {
        var remaining = wordCount
        return buildList {
            segments.forEach { segment ->
                if (remaining == 0) {
                    add(segment)
                } else {
                    val tokens =
                        segment.text
                            .trim()
                            .split(WHITESPACE)
                            .filter(String::isNotEmpty)
                    var consumedTokens = 0
                    while (consumedTokens < tokens.size && remaining > 0) {
                        if (normalizeWord(tokens[consumedTokens]).isNotEmpty()) remaining--
                        consumedTokens++
                    }
                    val retained = tokens.drop(consumedTokens).joinToString(" ")
                    if (retained.isNotEmpty()) add(segment.copy(text = retained))
                }
            }
        }
    }

    private fun timedWords(segment: TranscriptSegment): List<TimedWord> =
        segment.text
            .split(WHITESPACE)
            .map(::normalizeWord)
            .filter(String::isNotEmpty)
            .map { word -> TimedWord(word, segment) }

    private fun hasTemporalCorrespondence(
        previous: List<TimedWord>,
        current: List<TimedWord>,
    ): Boolean =
        previous.zip(current).all { (left, right) ->
            left.segment.correspondsTo(right.segment)
        }

    /** Allows minor Whisper timestamp jitter without merging clearly separate utterances. */
    private fun TranscriptSegment.correspondsTo(other: TranscriptSegment): Boolean =
        absoluteStartMs <= other.absoluteEndMs.plusClamped(TIMESTAMP_TOLERANCE_MS) &&
            other.absoluteStartMs <= absoluteEndMs.plusClamped(TIMESTAMP_TOLERANCE_MS)

    private fun Long.plusClamped(increment: Long): Long =
        if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

    private fun normalizeWord(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = StringBuilder()
        normalized.codePoints().forEach { codePoint ->
            if (Character.isLetterOrDigit(codePoint)) result.appendCodePoint(codePoint)
        }
        return result.toString()
    }

    private fun requireValidSegment(segment: TranscriptSegment) {
        require(segment.absoluteStartMs >= 0 && segment.absoluteEndMs > segment.absoluteStartMs) {
            "Invalid segment timestamps"
        }
    }

    private fun TranscriptSegment.overlaps(startMs: Long, endMs: Long): Boolean =
        absoluteStartMs < endMs && startMs < absoluteEndMs

    private fun <T> editDistance(left: List<T>, right: List<T>): Int {
        var previous = IntArray(right.size + 1) { it }
        left.forEachIndexed { leftIndex, leftValue ->
            val current = IntArray(right.size + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightValue ->
                current[rightIndex + 1] =
                    minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + if (leftValue == rightValue) 0 else 1,
                    )
            }
            previous = current
        }
        return previous[right.size]
    }

    private companion object {
        const val MIN_FUZZY_WORDS = 3
        const val TIMESTAMP_TOLERANCE_MS = 250L
        val WHITESPACE = Regex("\\s+")
        val segmentComparator =
            compareBy<TranscriptSegment> { it.absoluteStartMs }
                .thenBy { it.absoluteEndMs }
                .thenBy { it.id }
    }

    private data class TimedWord(
        val value: String,
        val segment: TranscriptSegment,
    )
}
