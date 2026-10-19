package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionJob

/**
 * Chronological, status-independent ordering prevents a completed job from
 * jumping to a different group while the user is watching its progress.
 */
internal fun visibleProcessingJobs(jobs: List<TranscriptionJob>): List<TranscriptionJob> =
    jobs.filter { it.status != JobStatus.CANCELLED }
        .sortedWith(
            compareByDescending<TranscriptionJob> { it.createdAtEpochMs }
                .thenBy { it.id },
        )
