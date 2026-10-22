package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePreviewPresentationTest {
    private val saved = TranscriptSegment("saved", "chunk-1", 0, 100, "Сохранено")
    private val live = TranscriptSegment("preview-2", "chunk-2", 100, 200, "Черновик")

    @Test
    fun preliminaryTextIsVisibleOnlyDuringRunningInference() {
        assertEquals(
            listOf(saved, live),
            visibleTranscriptSegments(listOf(saved), listOf(live), JobStatus.RUNNING),
        )
        listOf(
            JobStatus.PENDING,
            JobStatus.PAUSED,
            JobStatus.CANCELLED,
            JobStatus.FAILED,
            JobStatus.COMPLETED,
        ).forEach { status ->
            assertEquals(
                listOf(saved),
                visibleTranscriptSegments(listOf(saved), listOf(live), status),
            )
        }
    }
}
