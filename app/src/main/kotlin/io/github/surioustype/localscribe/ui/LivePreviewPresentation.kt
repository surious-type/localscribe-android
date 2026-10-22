package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment

/**
 * Only committed segments survive pauses, cancellations and failed attempts.
 * Preliminary native callbacks must never masquerade as stored transcript text.
 */
internal fun visibleTranscriptSegments(
    committed: List<TranscriptSegment>,
    preview: List<TranscriptSegment>,
    jobStatus: JobStatus?,
): List<TranscriptSegment> =
    committed + if (jobStatus == JobStatus.RUNNING) preview else emptyList()
