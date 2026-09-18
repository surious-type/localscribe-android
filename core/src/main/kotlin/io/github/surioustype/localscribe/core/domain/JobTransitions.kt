package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.JobStatus

object JobTransitions {
    fun canTransition(from: JobStatus, to: JobStatus): Boolean =
        to in JOB_TRANSITIONS.getValue(from)

    fun canTransition(from: ChunkStatus, to: ChunkStatus): Boolean =
        to in CHUNK_TRANSITIONS.getValue(from)

    private val JOB_TRANSITIONS =
        mapOf(
            JobStatus.PENDING to setOf(JobStatus.RUNNING, JobStatus.CANCELLED),
            JobStatus.RUNNING to
                setOf(JobStatus.PAUSED, JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.COMPLETED),
            JobStatus.PAUSED to setOf(JobStatus.RUNNING, JobStatus.CANCELLED),
            JobStatus.CANCELLED to emptySet(),
            JobStatus.FAILED to emptySet(),
            JobStatus.COMPLETED to emptySet(),
        )

    private val CHUNK_TRANSITIONS =
        mapOf(
            ChunkStatus.PENDING to
                setOf(ChunkStatus.PROCESSING, ChunkStatus.PAUSED, ChunkStatus.CANCELLED),
            ChunkStatus.PROCESSING to
                setOf(
                    ChunkStatus.PAUSED,
                    ChunkStatus.CANCELLED,
                    ChunkStatus.FAILED,
                    ChunkStatus.COMPLETED,
                ),
            ChunkStatus.PAUSED to setOf(ChunkStatus.PENDING, ChunkStatus.CANCELLED),
            ChunkStatus.CANCELLED to emptySet(),
            ChunkStatus.FAILED to emptySet(),
            ChunkStatus.COMPLETED to emptySet(),
        )
}
