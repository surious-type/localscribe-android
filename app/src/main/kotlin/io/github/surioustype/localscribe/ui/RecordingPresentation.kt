package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionJob

/**
 * A recording can have several transcription attempts. Only a finished attempt
 * opens as the default result; prefer the most recently finished attempt,
 * regardless of the repository's current sort order.
 */
internal fun latestCompletedTranscriptionJob(
    jobs: List<TranscriptionJob>,
    sourceId: String,
): TranscriptionJob? =
    jobs
        .asSequence()
        .filter { it.sourceId == sourceId && it.status == JobStatus.COMPLETED }
        .maxWithOrNull(
            compareBy<TranscriptionJob> { it.completedAtEpochMs ?: it.updatedAtEpochMs }
                .thenBy { it.createdAtEpochMs }
                .thenBy { it.id },
        )
