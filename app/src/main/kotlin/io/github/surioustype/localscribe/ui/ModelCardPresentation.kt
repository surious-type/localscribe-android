package io.github.surioustype.localscribe.ui

/**
 * One rendering contract for Whisper models and Silero VAD.
 * Installed state always wins over a stale download event.
 */
data class ModelCardPresentation(
    val statusLabel: String,
    val busy: Boolean,
    val canDownload: Boolean,
    val canDelete: Boolean,
)

fun modelCardPresentation(
    installed: Boolean,
    downloadStatus: String?,
    requestPending: Boolean = false,
): ModelCardPresentation {
    if (installed) {
        return ModelCardPresentation("Установлена", false, false, true)
    }
    val downloading = downloadStatus in setOf("QUEUED", "DOWNLOADING", "VERIFYING")
    return ModelCardPresentation(
        statusLabel =
            when {
                requestPending && !downloading -> "Подготовка скачивания…"
                downloadStatus == "QUEUED" -> "Ожидает скачивания"
                downloadStatus == "DOWNLOADING" -> "Скачивается…"
                downloadStatus == "VERIFYING" -> "Проверка целостности…"
                downloadStatus == "FAILED" -> "Не удалось скачать"
                downloadStatus == "CANCELLED" -> "Загрузка отменена"
                else -> "Не установлена"
            },
        busy = downloading || requestPending,
        canDownload = !downloading && !requestPending,
        canDelete = false,
    )
}
