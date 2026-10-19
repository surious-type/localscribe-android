package io.github.surioustype.localscribe.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkWindowComparisonTest {
    @Test
    fun `candidate windows retain total audio coverage`() {
        val results = ChunkWindowProfiles.compare(90_000L)

        assertEquals(listOf(10, 15, 20, 30), results.map { it.windowSeconds })
        assertTrue(results.all { it.processedAudioMs >= 90_000L })
        assertTrue(results.all { it.chunkCount > 0 })
        assertTrue(results.all { it.extraOverlapMs == it.processedAudioMs - 90_000L })
    }

    @Test
    fun `short windows incur more overlap checkpoints than long windows`() {
        val results = ChunkWindowProfiles.compare(180_000L)

        assertTrue(results.first().chunkCount > results.last().chunkCount)
        assertTrue(results.first().extraOverlapMs > results.last().extraOverlapMs)
    }

    @Test
    fun `empty source produces zero windows and no overhead`() {
        assertTrue(
            ChunkWindowProfiles.compare(0L).all {
                it.chunkCount == 0 &&
                    it.processedAudioMs == 0L &&
                    it.extraOverlapMs == 0L
            },
        )
    }
}
