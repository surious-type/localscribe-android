package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptContextTest {
    private val context = TranscriptContext()

    @Test
    fun `context keeps the chronological tail within a code point limit`() {
        val result =
            context.build(
                listOf(segment("later", 2, "😀BC"), segment("first", 1, "A")),
                maxCharacters = 3,
            )

        assertEquals("😀BC", result)
        assertEquals(3, result!!.codePointCount(0, result.length))
    }

    @Test
    fun `empty or disabled context returns null`() {
        assertNull(context.build(listOf(segment("blank", 0, "   ")), 10))
        assertNull(context.build(listOf(segment("text", 0, "hello")), 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative context limit is rejected`() {
        context.build(emptyList(), -1)
    }

    private fun segment(id: String, start: Long, text: String) =
        TranscriptSegment(
            id = id,
            chunkId = "chunk",
            absoluteStartMs = start,
            absoluteEndMs = start + 1,
            text = text,
        )
}
