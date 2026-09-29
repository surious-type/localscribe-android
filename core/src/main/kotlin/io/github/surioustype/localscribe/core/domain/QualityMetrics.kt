package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.QualityScore
import java.text.Normalizer
import java.util.Locale

object QualityMetrics {
    fun calculate(reference: String, hypothesis: String): QualityScore {
        val normalizedReference = normalize(reference)
        val normalizedHypothesis = normalize(hypothesis)
        val referenceWords = normalizedWords(reference)
        val hypothesisWords = normalizedWords(hypothesis)
        val wordErrors = editDistance(referenceWords, hypothesisWords)
        val referenceCharacters = normalizedReference.nonSpaceCodePoints()
        val hypothesisCharacters = normalizedHypothesis.nonSpaceCodePoints()
        val characterErrors = editDistance(referenceCharacters, hypothesisCharacters)
        return QualityScore(
            wordErrorRate = normalizedRate(wordErrors, referenceWords.size),
            characterErrorRate = normalizedRate(characterErrors, referenceCharacters.size),
            wordErrors = wordErrors,
            referenceWordCount = referenceWords.size,
        )
    }

    /** Token stream used by word-error scoring and UI edit alignment. */
    fun normalizedWords(text: String): List<String> = normalize(text).words()

    private fun normalize(text: String): String {
        val source = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val normalized = StringBuilder()
        var pendingSpace = false
        source.codePoints().forEach { codePoint ->
            if (Character.isLetterOrDigit(codePoint)) {
                if (pendingSpace && normalized.isNotEmpty()) normalized.append(' ')
                normalized.appendCodePoint(codePoint)
                pendingSpace = false
            } else {
                pendingSpace = normalized.isNotEmpty()
            }
        }
        return normalized.toString()
    }

    private fun String.words(): List<String> = if (isEmpty()) emptyList() else split(' ')

    private fun String.nonSpaceCodePoints(): List<Int> =
        codePoints()
            .filter {
                it != ' '.code
            }.boxed()
            .toList()

    private fun normalizedRate(errors: Int, referenceCount: Int): Double =
        when {
            referenceCount > 0 -> errors.toDouble() / referenceCount
            errors == 0 -> 0.0
            else -> 1.0
        }

    private fun <T> editDistance(reference: List<T>, hypothesis: List<T>): Int {
        var previous = IntArray(hypothesis.size + 1) { it }
        reference.forEachIndexed { referenceIndex, referenceValue ->
            val current = IntArray(hypothesis.size + 1)
            current[0] = referenceIndex + 1
            hypothesis.forEachIndexed { hypothesisIndex, hypothesisValue ->
                current[hypothesisIndex + 1] =
                    minOf(
                        current[hypothesisIndex] + 1,
                        previous[hypothesisIndex + 1] + 1,
                        previous[hypothesisIndex] + if (referenceValue == hypothesisValue) 0 else 1,
                    )
            }
            previous = current
        }
        return previous[hypothesis.size]
    }
}
