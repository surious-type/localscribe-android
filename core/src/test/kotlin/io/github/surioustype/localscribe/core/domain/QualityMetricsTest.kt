package io.github.surioustype.localscribe.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class QualityMetricsTest {
    @Test
    fun `unicode case punctuation and spacing normalize consistently`() {
        val result = QualityMetrics.calculate("Café,   WORLD!", "cafe\u0301 world")

        assertEquals(0, result.wordErrors)
        assertEquals(0.0, result.wordErrorRate, 0.0)
        assertEquals(0.0, result.characterErrorRate, 0.0)
    }

    @Test
    fun `WER counts substitutions and insertions`() {
        val result = QualityMetrics.calculate("the cat sat", "the dog sat here")

        assertEquals(2, result.wordErrors)
        assertEquals(3, result.referenceWordCount)
        assertEquals(2.0 / 3.0, result.wordErrorRate, 1e-12)
    }

    @Test
    fun `CER uses normalized non-space characters`() {
        val result = QualityMetrics.calculate("kitten", "sitting")

        assertEquals(3.0 / 6.0, result.characterErrorRate, 1e-12)
    }

    @Test
    fun `empty reference has defined bounded rates`() {
        val bothEmpty = QualityMetrics.calculate("", "")
        val insertion = QualityMetrics.calculate("", "hello")

        assertEquals(0.0, bothEmpty.wordErrorRate, 0.0)
        assertEquals(0.0, bothEmpty.characterErrorRate, 0.0)
        assertEquals(1.0, insertion.wordErrorRate, 0.0)
        assertEquals(1.0, insertion.characterErrorRate, 0.0)
        assertEquals(1, insertion.wordErrors)
        assertEquals(0, insertion.referenceWordCount)
    }
}
