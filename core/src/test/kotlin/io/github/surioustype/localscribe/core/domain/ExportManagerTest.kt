package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.ExportFormat
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportManagerTest {
    private val manager = ExportManager()
    private val source = AudioSource("source", "opaque", "Meeting.wav", 4_000_000, "audio/wav")

    @Test
    fun `SRT sorts source timestamps numbers cues and supports times over an hour`() {
        val document =
            manager.export(
                source,
                listOf(
                    segment("b", 3_600_123, 3_665_123, "After an hour"),
                    segment("a", 1_000, 2_500, "Opening"),
                ),
                ExportFormat.SRT,
            )

        assertEquals("Meeting.srt", document.suggestedFileName)
        assertEquals("application/x-subrip", document.mimeType)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,500\nOpening\n\n" +
                "2\n01:00:00,123 --> 01:01:05,123\nAfter an hour\n",
            document.content.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `VTT uses source timestamps with period milliseconds`() {
        val text =
            manager
                .export(source, listOf(segment("a", 1_000, 2_500, "Opening")), ExportFormat.VTT)
                .content
                .toString(Charsets.UTF_8)

        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nOpening\n", text)
    }

    @Test
    fun `JSON escapes text and retains source timestamps`() {
        val document =
            manager.export(
                source,
                listOf(segment("a", 12, 34, "quote \" and\nline 😀")),
                ExportFormat.JSON,
            )
        val root = Json.parseToJsonElement(document.content.toString(Charsets.UTF_8)).jsonObject
        val item =
            root
                .getValue("segments")
                .jsonArray
                .single()
                .jsonObject

        assertEquals("quote \" and\nline 😀", item.getValue("text").jsonPrimitive.content)
        assertEquals(
            12L,
            item
                .getValue("startMs")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            34L,
            item
                .getValue("endMs")
                .jsonPrimitive.content
                .toLong(),
        )
        assertTrue(document.content.toString(Charsets.UTF_8).contains("\\\""))
    }

    @Test
    fun `markdown escapes formatting characters`() {
        val text =
            manager
                .export(
                    source,
                    listOf(segment("a", 0, 1, "# Heading *literal*")),
                    ExportFormat.MARKDOWN,
                ).content
                .toString(Charsets.UTF_8)

        assertEquals("# Meeting.wav\n\n\\# Heading \\*literal\\*\n", text)
    }

    @Test
    fun `invalid source timestamps are rejected`() {
        assertFails {
            manager.export(
                source,
                listOf(segment("negative", -1, 2, "x")),
                ExportFormat.TXT,
            )
        }
        assertFails {
            manager.export(
                source,
                listOf(segment("reversed", 2, 1, "x")),
                ExportFormat.TXT,
            )
        }
        assertFails {
            manager.export(
                source,
                listOf(segment("empty", 2, 2, "x")),
                ExportFormat.TXT,
            )
        }
        assertFails {
            manager.export(
                source,
                listOf(segment("past-source", 1, 4_000_001, "x")),
                ExportFormat.TXT,
            )
        }
    }

    private fun segment(id: String, start: Long, end: Long, text: String) =
        TranscriptSegment(
            id = id,
            chunkId = "chunk",
            absoluteStartMs = start,
            absoluteEndMs = end,
            text = text,
        )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
