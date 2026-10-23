package io.github.surioustype.localscribe.execution

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.EngineSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptStoreTest {
    private fun chunk(id: String = "c", jobId: String = "job") =
        TranscriptionChunk(
            id = id,
            jobId = jobId,
            modelId = "tiny",
            startMs = 60_000L,
            endMs = 90_000L,
            status = ChunkStatus.PROCESSING,
            attempt = 1,
            createdAtEpochMs = 0L,
        )

    @Test
    fun `preview uses absolute timestamps and is scoped to the job`() =
        runTest {
            val store = LiveTranscriptStore()
            store.beginChunk("job", "c")
            store.publish("job", chunk(), EngineSegment(1_000L, 2_500L, "  Привет  "))
            val entries = store.observe("job").first()
            assertEquals(1, entries.size)
            assertEquals(61_000L, entries.single().absoluteStartMs)
            assertEquals(62_500L, entries.single().absoluteEndMs)
            assertEquals("Привет", entries.single().text)
            assertTrue(store.observe("other").first().isEmpty())
        }

    @Test
    fun `silent and invalid segments are never shown`() =
        runTest {
            val store = LiveTranscriptStore()
            store.publish("job", chunk(), EngineSegment(0L, 1_000L, "  "))
            store.publish("job", chunk(), EngineSegment(2_000L, 1_000L, "bad"))
            assertTrue(store.observe("job").first().isEmpty())
        }

    @Test
    fun `late callbacks after cancel or chunk change never resurrect previews`() =
        runTest {
            val store = LiveTranscriptStore()
            store.beginChunk("job", "first")
            store.publish("job", chunk("first"), EngineSegment(100L, 500L, "old"))
            store.beginChunk("job", "second")
            store.publish("job", chunk("first"), EngineSegment(500L, 900L, "stale"))
            assertTrue(store.observe("job").first().isEmpty())

            store.publish("job", chunk("second"), EngineSegment(100L, 500L, "new"))
            store.clear("job")
            store.publish("job", chunk("second"), EngineSegment(500L, 900L, "late"))
            assertTrue(store.observe("job").first().isEmpty())
        }

    @Test
    fun `preview caps memory without repeating stable list keys`() =
        runTest {
            val store = LiveTranscriptStore()
            store.beginChunk("job", "c")
            repeat(80) { ordinal ->
                store.publish(
                    "job",
                    chunk(),
                    EngineSegment(ordinal * 100L, ordinal * 100L + 90L, "$ordinal"),
                )
            }
            val entries = store.observe("job").first()
            assertEquals(64, entries.size)
            assertEquals(64, entries.map { it.id }.distinct().size)
            assertEquals("79", entries.last().text)
        }

    @Test
    fun `clearing an unfinished chunk discards only provisional text`() =
        runTest {
            val store = LiveTranscriptStore()
            store.beginChunk("a", "c")
            store.beginChunk("b", "c")
            store.publish("a", chunk(jobId = "a"), EngineSegment(0L, 500L, "hello"))
            store.publish("b", chunk(jobId = "b"), EngineSegment(0L, 500L, "world"))
            store.clear("a")
            assertTrue(store.observe("a").first().isEmpty())
            val remaining = store.observe("b").first().single()
            assertEquals("world", remaining.text)
        }
}
