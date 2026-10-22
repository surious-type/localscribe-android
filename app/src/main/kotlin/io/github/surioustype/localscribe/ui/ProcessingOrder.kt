package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionJob

/**
 * Chronological, status-independent history keeps completed and cancelled attempts visible.
 * Their committed fragments remain accessible even after the worker stops.
 */
internal fun visibleProcessingJobs(jobs: List<TranscriptionJob>): List<TranscriptionJob> =
    jobs
        .sortedWith(
            compareByDescending<TranscriptionJob> { it.createdAtEpochMs }
                .thenBy { it.id },
        )
