package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.JobStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTransitionsTest {
    @Test
    fun `job lifecycle permits start pause resume completion and cancellation`() {
        assertTrue(JobTransitions.canTransition(JobStatus.PENDING, JobStatus.RUNNING))
        assertTrue(JobTransitions.canTransition(JobStatus.RUNNING, JobStatus.PAUSED))
        assertTrue(JobTransitions.canTransition(JobStatus.PAUSED, JobStatus.RUNNING))
        assertTrue(JobTransitions.canTransition(JobStatus.RUNNING, JobStatus.COMPLETED))
        assertTrue(JobTransitions.canTransition(JobStatus.PAUSED, JobStatus.CANCELLED))
        assertFalse(JobTransitions.canTransition(JobStatus.PAUSED, JobStatus.COMPLETED))
    }

    @Test
    fun `terminal job states cannot transition`() {
        listOf(JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.COMPLETED).forEach { terminal ->
            JobStatus.entries.forEach { target ->
                assertFalse("$terminal -> $target", JobTransitions.canTransition(terminal, target))
            }
        }
    }

    @Test
    fun `chunk lifecycle protects paused cancelled and completed work`() {
        assertTrue(JobTransitions.canTransition(ChunkStatus.PENDING, ChunkStatus.PROCESSING))
        assertTrue(JobTransitions.canTransition(ChunkStatus.PROCESSING, ChunkStatus.PAUSED))
        assertTrue(JobTransitions.canTransition(ChunkStatus.PAUSED, ChunkStatus.PENDING))
        assertTrue(JobTransitions.canTransition(ChunkStatus.PROCESSING, ChunkStatus.COMPLETED))
        assertTrue(JobTransitions.canTransition(ChunkStatus.PROCESSING, ChunkStatus.CANCELLED))
        assertFalse(JobTransitions.canTransition(ChunkStatus.PAUSED, ChunkStatus.COMPLETED))
        assertFalse(JobTransitions.canTransition(ChunkStatus.CANCELLED, ChunkStatus.COMPLETED))
        assertFalse(JobTransitions.canTransition(ChunkStatus.COMPLETED, ChunkStatus.PROCESSING))
    }
}
