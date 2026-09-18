package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private val planner = ChunkPlanner()

    @Test
    fun `plan covers source with configured overlap and no gaps`() {
        val chunks = planner.plan("job", "small", 180_000, config(), 123)

        assertEquals(
            listOf(0L to 90_000L, 87_000L to 177_000L, 174_000L to 180_000L),
            chunks.map {
                it.startMs to
                    it.endMs
            },
        )
        assertTrue(chunks.zipWithNext().all { (left, right) -> right.startMs <= left.endMs })
        assertTrue(chunks.all { it.endMs > it.startMs })
        assertEquals(chunks.size, chunks.map { it.id }.toSet().size)
        assertTrue(chunks.all { it.jobId == "job" && it.modelId == "small" })
        assertTrue(
            chunks.all {
                it.status == ChunkStatus.PENDING &&
                    it.attempt == 0 &&
                    it.createdAtEpochMs == 123L
            },
        )
    }

    @Test
    fun `plan does not add a zero length chunk at an exact boundary`() {
        val chunks = planner.plan("job", "small", 177_000, config(), 0)

        assertEquals(
            listOf(0L to 90_000L, 87_000L to 177_000L),
            chunks.map { it.startMs to it.endMs },
        )
    }

    @Test
    fun `zero duration source has no chunks`() {
        assertTrue(planner.plan("job", "small", 0, config(), 0).isEmpty())
    }

    @Test
    fun `large durations do not overflow window end`() {
        val huge = config(chunkDurationMs = Long.MAX_VALUE, overlapMs = 0)

        val chunks = planner.plan("job", "small", Long.MAX_VALUE, huge, 0)

        assertEquals(listOf(0L to Long.MAX_VALUE), chunks.map { it.startMs to it.endMs })
    }

    @Test
    fun `invalid durations and overlap are rejected`() {
        assertFails { planner.plan("job", "small", -1, config(), 0) }
        assertFails { planner.plan("job", "small", 1, config(chunkDurationMs = 0), 0) }
        assertFails { planner.plan("job", "small", 1, config(overlapMs = -1), 0) }
        assertFails {
            planner.plan(
                "job",
                "small",
                1,
                config(chunkDurationMs = 10, overlapMs = 10),
                0,
            )
        }
    }

    private fun config(
        chunkDurationMs: Long = 90_000,
        overlapMs: Long = 3_000,
    ) = TranscriptionConfig(
        modelId = "small",
        inference = InferenceConfig(threadCount = 4),
        chunkDurationMs = chunkDurationMs,
        overlapMs = overlapMs,
    )

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
