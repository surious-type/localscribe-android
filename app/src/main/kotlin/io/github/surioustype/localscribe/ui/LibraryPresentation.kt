package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptionJob

/** A single, predictable status for the recording library, independent of UI colors. */
enum class LibraryRecordState {
    READY,
    RUNNING,
    QUEUED,
    PAUSED,
    FAILED,
    CANCELLED,
    SOURCE_UNAVAILABLE,
    NO_TRANSCRIPT,
}

data class LibraryRecordPresentation(
    val state: LibraryRecordState,
    val status: String,
    val detail: String,
)

/**
 * An active attempt wins over old history; a completed version wins over a failed
 * newer attempt because it is still safe to read. Source problems are explicit
 * even when an older transcript remains available offline.
 */
fun libraryRecordPresentation(
    source: AudioSourceRecord,
    allJobs: List<TranscriptionJob>,
): LibraryRecordPresentation {
    val jobs = allJobs.filter { it.sourceId == source.source.id }
    val active =
        jobs.filter {
            it.status == JobStatus.RUNNING ||
                it.status == JobStatus.PENDING ||
                it.status == JobStatus.PAUSED
        }.maxByOrNull { it.createdAtEpochMs }
    val completed = jobs.any { it.status == JobStatus.COMPLETED }
    val latest = jobs.maxByOrNull { it.createdAtEpochMs }

    if (source.accessStatus != SourceAccessStatus.AVAILABLE) {
        val missing = source.accessStatus == SourceAccessStatus.MISSING
        return LibraryRecordPresentation(
            LibraryRecordState.SOURCE_UNAVAILABLE,
            if (missing) "Исходный файл недоступен" else "Нужен доступ к аудио",
            if (completed) {
                "Готовый текст сохранён. " +
                    if (missing) {
                        "Укажите исходный файл для прослушивания."
                    } else {
                        "Восстановите доступ к файлу для прослушивания."
                    }
            } else {
                if (missing) {
                    "Укажите исходный файл, чтобы продолжить обработку."
                } else {
                    "Предоставьте доступ к исходному файлу, чтобы продолжить."
                }
            },
        )
    }

    return when (active?.status) {
        JobStatus.RUNNING ->
            LibraryRecordPresentation(
                LibraryRecordState.RUNNING,
                "Распознаётся",
                if (completed) {
                    "Предыдущий текст доступен; новая версия обрабатывается"
                } else {
                    "Нажмите, чтобы открыть очередь обработки"
                },
            )
        JobStatus.PENDING ->
            LibraryRecordPresentation(
                LibraryRecordState.QUEUED,
                "В очереди",
                "Ожидает запуска распознавания",
            )
        JobStatus.PAUSED ->
            LibraryRecordPresentation(
                LibraryRecordState.PAUSED,
                "На паузе",
                "Обработку можно продолжить из очереди",
            )
        else ->
            when {
                completed ->
                    LibraryRecordPresentation(
                        LibraryRecordState.READY,
                        "Расшифровка готова",
                        "Нажмите, чтобы прочитать текст",
                    )
                latest?.status == JobStatus.FAILED ->
                    LibraryRecordPresentation(
                        LibraryRecordState.FAILED,
                        "Ошибка распознавания",
                        "Откройте запись для повторной попытки",
                    )
                latest?.status == JobStatus.CANCELLED ->
                    LibraryRecordPresentation(
                        LibraryRecordState.CANCELLED,
                        "Обработка отменена",
                        "Можно запустить распознавание заново",
                    )
                else ->
                    LibraryRecordPresentation(
                        LibraryRecordState.NO_TRANSCRIPT,
                        "Без расшифровки",
                        "Нажмите, чтобы настроить распознавание",
                    )
            }
    }
}
