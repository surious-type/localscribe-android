package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptionJob

/**
 * Completed attempts are independent versions of the same recording.
 * Running, paused, failed or cancelled reruns must not replace the
 * previous readable transcript.
 */
internal fun completedTranscriptionVersions(
    jobs: List<TranscriptionJob>,
    sourceId: String,
): List<TranscriptionJob> =
    jobs
        .asSequence()
        .filter { it.sourceId == sourceId && it.status == JobStatus.COMPLETED }
        .sortedWith(
            compareByDescending<TranscriptionJob> { it.completedAtEpochMs ?: it.updatedAtEpochMs }
                .thenByDescending { it.createdAtEpochMs }
                .thenByDescending { it.id },
        ).toList()

/** Opens the most recently completed attempt rather than the latest launched rerun. */
internal fun latestCompletedTranscriptionJob(
    jobs: List<TranscriptionJob>,
    sourceId: String,
): TranscriptionJob? = completedTranscriptionVersions(jobs, sourceId).firstOrNull()
