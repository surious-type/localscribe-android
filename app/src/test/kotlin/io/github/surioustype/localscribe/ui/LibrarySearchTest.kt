package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.data.TranscriptSearchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    @Test
    fun cyrillicSearchIgnoresCaseAndOpensExactTime() {
        val source = source("audio", "Лекция по математике")
        val rows = listOf(
            TranscriptSearchRow("job", "audio", "segment", 28_000L, "Сегодня изучаем МЕТОД Ньютона."),
        )
        val results = searchLibrary(listOf(source), rows, "метод")
        assertEquals(1, results.size)
        assertEquals("job", results.single().jobId)
        assertEquals(28_000L, results.single().startMs)
        assertTrue(results.single().snippet!!.contains("МЕТОД"))
    }

    @Test
    fun titleSearchIncludesLocalAliasAndOriginalName() {
        val source = source("audio", "Original.wav").copy(customTitle = "Лекция № 1")
        assertEquals("Лекция № 1", libraryTitle(source))
        assertEquals(1, searchLibrary(listOf(source), emptyList(), "лекция").size)
        assertEquals(1, searchLibrary(listOf(source), emptyList(), "original").size)
    }

    @Test
    fun unrelatedSourcesAreNotExposedInSearchResults() {
        val rows = listOf(
            TranscriptSearchRow("old", "missing", "segment", 0L, "тайная заметка"),
        )
        assertTrue(searchLibrary(listOf(source("one", "Другая")), rows, "заметка").isEmpty())
    }

    @Test
    fun sortUsesActualDatesAndKeepsUnknownAtEnd() {
        val unknown = source("old", "Неизвестно")
        val imported = source("new", "Импорт").copy(addedAtEpochMs = 30L)
        val recorded = source("recorded", "Запись").copy(
            recordedAtEpochMs = 20L,
            addedAtEpochMs = 10L,
        )
        assertEquals(
            listOf("new", "recorded", "old"),
            sortLibrary(listOf(recorded, unknown, imported), "added").map { it.source.id },
        )
        assertEquals(
            listOf("recorded", "new", "old"),
            sortLibrary(listOf(recorded, unknown, imported), "recorded").map { it.source.id }
                .let { listOf(it[0], it[1], it[2]) },
        )
    }

    private fun source(id: String, name: String): AudioSourceRecord =
        AudioSourceRecord(
            source = AudioSource(id, "content://audio/$id", name, 1_000, "audio/wav"),
            accessStatus = SourceAccessStatus.AVAILABLE,
            hasPersistedPermission = false,
        )
}
