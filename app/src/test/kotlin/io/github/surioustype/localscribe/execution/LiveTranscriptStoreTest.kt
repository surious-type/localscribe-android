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
    private fun chunk(id: String = "c") =
        TranscriptionChunk(
        id = id,
        jobId = "job",
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
    fun `clearing an unfinished chunk discards only provisional text`() =
        runTest {
        val store = LiveTranscriptStore()
        store.publish("a", chunk(), EngineSegment(0L, 500L, "hello"))
        store.publish("b", chunk(), EngineSegment(0L, 500L, "world"))
        store.clear("a")
        assertTrue(store.observe("a").first().isEmpty())
        assertEquals("world", store.observe("b").first().single().text)
    }
}
