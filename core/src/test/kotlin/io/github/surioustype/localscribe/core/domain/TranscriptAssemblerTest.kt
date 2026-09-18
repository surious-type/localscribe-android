package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAssemblerTest {
    private val assembler = TranscriptAssembler()

    @Test
    fun `exact overlap is emitted once and output is chronological`() {
        val chunks = listOf(chunk("second", 8_000, 18_000), chunk("first", 0, 10_000))
        val raw =
            listOf(
                segment("later", "second", 11_000, 13_000, "Again"),
                segment("duplicate", "second", 8_000, 10_000, "hello world"),
                segment("original", "first", 7_000, 10_000, "Hello world"),
            )

        val result = assembler.assemble(chunks, raw)

        assertEquals(listOf("original", "later"), result.map { it.id })
        assertEquals(listOf("Hello world", "Again"), result.map { it.text })
    }

    @Test
    fun `partial suffix prefix overlap trims only duplicated prefix words`() {
        val result =
            assembler.assemble(
                chunks = listOf(chunk("one", 0, 10_000), chunk("two", 8_000, 18_000)),
                rawSegments =
                    listOf(
                        segment("a", "one", 7_000, 10_000, "we are testing the overlap"),
                        segment("b", "two", 8_000, 12_000, "the overlap continues now"),
                    ),
            )

        assertEquals(listOf("we are testing the overlap", "continues now"), result.map { it.text })
        assertEquals(8_000L, result.last().absoluteStartMs)
    }

    @Test
    fun `punctuation and case differences still deduplicate`() {
        val result =
            assembler.assemble(
                listOf(chunk("one", 0, 10_000), chunk("two", 8_000, 18_000)),
                listOf(
                    segment("a", "one", 7_000, 10_000, "Hello, WORLD!"),
                    segment("b", "two", 8_000, 11_000, "hello world... Next"),
                ),
            )

        assertEquals(listOf("Hello, WORLD!", "Next"), result.map { it.text })
    }

    @Test
    fun `small ASR variation in a multiword overlap is deduplicated`() {
        val result =
            assembler.assemble(
                listOf(chunk("one", 0, 10_000), chunk("two", 8_000, 18_000)),
                listOf(
                    segment("a", "one", 7_000, 10_000, "the colour blue"),
                    segment("b", "two", 8_000, 12_000, "the color blue today"),
                ),
            )

        assertEquals(listOf("the colour blue", "today"), result.map { it.text })
    }

    @Test
    fun `repeated phrase at distinct times is retained`() {
        val result =
            assembler.assemble(
                listOf(chunk("one", 0, 10_000), chunk("two", 10_000, 20_000)),
                listOf(
                    segment("a", "one", 1_000, 2_000, "thank you"),
                    segment("b", "two", 11_000, 12_000, "thank you"),
                ),
            )

        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `repeated phrase at distinct times inside chunk overlap is retained`() {
        val result =
            assembler.assemble(
                listOf(chunk("one", 0, 10_000), chunk("two", 8_000, 18_000)),
                listOf(
                    segment("a", "one", 8_000, 8_500, "yes"),
                    segment("b", "two", 9_500, 10_000, "yes"),
                ),
            )

        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    private fun chunk(id: String, start: Long, end: Long) =
        TranscriptionChunk(
            id = id,
            jobId = "job",
            modelId = "model",
            startMs = start,
            endMs = end,
            status = ChunkStatus.COMPLETED,
            attempt = 1,
            createdAtEpochMs = 0,
        )

    private fun segment(id: String, chunkId: String, start: Long, end: Long, text: String) =
        TranscriptSegment(
            id = id,
            chunkId = chunkId,
            absoluteStartMs = start,
            absoluteEndMs = end,
            text = text,
        )
}
