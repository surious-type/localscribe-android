package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptReadingTest {
    private fun job(
        id: String,
        status: JobStatus,
        sourceId: String = "source",
        created: Long = 100,
    ) = TranscriptionJob(
        id = id,
        sourceId = sourceId,
        config = TranscriptionConfig("tiny", InferenceConfig(2, "ru")),
        modelHash = "sha",
        status = status,
        createdAtEpochMs = created,
        updatedAtEpochMs = created,
    )

    private fun segment(id: String, start: Long, text: String) =
        TranscriptSegment(id, "chunk", start, start + 1_000, text)

    @Test
    fun primaryStaysReadableDuringRerunsAndErrors() {
        val old = job("old", JobStatus.COMPLETED, created = 100)
        val running = job("running", JobStatus.RUNNING, created = 200)
        val failed = job("failed", JobStatus.FAILED, created = 300)
        val newer = job("new", JobStatus.COMPLETED, created = 400)

        assertEquals("old", preferredCompletedTranscript(listOf(old, running), "source", null)?.id)
        assertEquals("old", preferredCompletedTranscript(listOf(old, failed), "source", "failed")?.id)
        assertEquals("new", preferredCompletedTranscript(listOf(old, newer), "source", null)?.id)
        assertEquals("old", preferredCompletedTranscript(listOf(old, newer), "source", "old")?.id)
        assertNull(preferredCompletedTranscript(listOf(running, failed), "source", null))
    }

    @Test
    fun compareByAudioWindowIgnoresDifferentSegmentSplits() {
        val old = listOf(segment("a", 0, "Первое"), segment("b", 1_000, "предложение"))
        val split = listOf(segment("one", 0, "Первое предложение"))
        assertEquals(emptyList<TranscriptVersionChange>(), compareTranscriptVersions(old, split))
        val updated = split + segment("two", 31_000, "Добавлено")
        assertEquals(1, compareTranscriptVersions(old, updated).size)
        assertEquals(30_000L, compareTranscriptVersions(old, updated).single().startMs)
        assertEquals("", compareTranscriptVersions(old, updated).single().before)
    }

    @Test
    fun comparisonShowsDeletionsWithoutChangingEitherVersion() {
        val original = listOf(segment("a", 1_000, "Сохранённый старый текст"))
        val new = listOf(segment("b", 1_000, "Новый текст"))
        val diff = compareTranscriptVersions(original, new)
        assertEquals("Сохранённый старый текст", diff.single().before)
        assertEquals("Новый текст", diff.single().after)
        assertEquals("Сохранённый старый текст", original.single().text)
    }

    @Test
    fun searchDoesNotLoseTimestampsOrDependOnReaderMode() {
        val original = listOf(segment("a", 1_000, "Первый"), segment("b", 9_000, "Второй"))
        val selected = searchableTranscriptSegments(original, "втор")
        assertEquals(9_000L, selected.single().absoluteStartMs)
        assertEquals(listOf("a", "b"), searchableTranscriptSegments(original, "").map { it.id })
    }
}
