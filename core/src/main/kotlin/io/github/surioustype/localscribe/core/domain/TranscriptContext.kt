package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.TranscriptSegment

class TranscriptContext {
    fun build(previousSegments: List<TranscriptSegment>, maxCharacters: Int): String? {
        require(maxCharacters >= 0) { "Context limit must not be negative" }
        if (maxCharacters == 0) return null
        val joined =
            previousSegments
                .sortedWith(
                    compareBy<TranscriptSegment> {
                        it.absoluteStartMs
                    }.thenBy { it.absoluteEndMs }.thenBy { it.id },
                ).map { it.text.trim() }
                .filter(String::isNotEmpty)
                .joinToString(" ")
        if (joined.isEmpty()) return null
        val codePointCount = joined.codePointCount(0, joined.length)
        if (codePointCount <= maxCharacters) return joined
        val startIndex = joined.offsetByCodePoints(0, codePointCount - maxCharacters)
        return joined.substring(startIndex)
    }
}
