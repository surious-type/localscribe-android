package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.surioustype.localscribe.benchmark.BenchmarkComparisons
import io.github.surioustype.localscribe.benchmark.BenchmarkRunState
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val COMPARISON_SAMPLE = "english_jfk_37s"
private const val COMPARISON_LANGUAGE = "en"
private const val COMPARISON_THREADS = 4

internal fun comparisonInferenceConfig(): InferenceConfig =
    InferenceConfig(threadCount = COMPARISON_THREADS, language = COMPARISON_LANGUAGE)

internal fun comparableBenchmarkHistory(
    records: List<BenchmarkRecord>,
    installed: List<InstalledModel>,
    deviceId: String,
): List<BenchmarkRecord> {
    val validHashes = installed.map { it.descriptorId to it.sha256.lowercase() }.toSet()
    val candidates =
        records.filter {
            it.deviceId == deviceId &&
                it.sampleId == COMPARISON_SAMPLE &&
                it.config == comparisonInferenceConfig() &&
                (it.modelId to it.modelHash.lowercase()) in validHashes &&
                it.realTimeFactor.isFinite() &&
                it.realTimeFactor >= 0.0
        }
    val anchor =
        candidates.maxWithOrNull(
            compareBy<BenchmarkRecord> { it.createdAtEpochMs }.thenBy { it.id },
        ) ?: return emptyList()
    return BenchmarkComparisons.comparableModels(anchor, candidates)
}

private fun metric(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

@Composable
private fun MetricBar(
    label: String,
    value: Double?,
    maximum: Double,
    unit: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label + ": " + (value?.let { metric(it) + unit } ?: "не измерено"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (value != null && value.isFinite() && value >= 0.0) {
            LocalScribeProgress(
                (value / maximum.coerceAtLeast(0.01)).toFloat().coerceIn(0f, 1f),
            )
        }
    }
}

/** Same English reference audio, device, four threads and inference settings in every row. */
@Composable
fun BenchmarkComparisonPanel(
    records: List<BenchmarkRecord>,
    installed: List<InstalledModel>,
    deviceId: String,
    selectedIds: Set<String>,
    onSelected: (Set<String>) -> Unit,
    busy: Boolean,
    runState: BenchmarkRunState,
    elapsedMs: Long,
    onCompare: () -> Unit,
    onCancel: () -> Unit,
) {
    val models = installed.distinctBy { it.descriptorId }.sortedBy { it.displayName }
    val comparable = comparableBenchmarkHistory(records, installed, deviceId)
    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Сравнение моделей", style = MaterialTheme.typography.titleMedium)
            Text(
                "Одинаковая тестовая речь JFK (английский, 37 с), 4 потока, без VAD. " +
                    "Один проход модели измеряет скорость, ошибки и пиковую память. " +
                    "На другом языке результаты могут отличаться.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (models.size < 2) {
                Text("Для сравнения установите как минимум две модели.")
            } else {
                Text("Выберите модели", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(models, key = { it.descriptorId }) { model ->
                        FilterChip(
                            selected = model.descriptorId in selectedIds,
                            onClick = {
                                onSelected(
                                    if (model.descriptorId in selectedIds) {
                                        selectedIds - model.descriptorId
                                    } else {
                                        selectedIds + model.descriptorId
                                    },
                                )
                            },
                            label = { Text(model.displayName) },
                            enabled = !busy,
                        )
                    }
                }
                val selectedCount =
                    selectedIds.count { id -> models.any { it.descriptorId == id } }
                Button(
                    onClick = onCompare,
                    enabled = !busy && selectedCount >= 2,
                ) {
                    Text("Тестировать выбранные: скорость и качество")
                }
            }
            if (busy && runState.totalModels > 1) {
                Text(
                    "Модель " + (runState.completedModels + 1).coerceAtMost(runState.totalModels) +
                        " из " + runState.totalModels + " · " +
                        (runState.modelId ?: "подготовка"),
                )
                ModelBenchmarkRunningStatus(runState.step, elapsedMs, onCancel)
            }
            if (comparable.isEmpty()) {
                Text(
                    "Сопоставимых измерений пока нет. Старые тесты без WER отображаются в истории.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Результаты при одинаковых условиях",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "RTF — длительность обработки / длительность аудио: меньше — быстрее. " +
                        "WER — доля ошибок в словах: меньше — точнее. " +
                        "Память — приблизительный пик процесса.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val maxRtf = comparable.maxOf { it.realTimeFactor }.coerceAtLeast(0.01)
                val maxWer = comparable.mapNotNull { it.wordErrorRate }.maxOrNull() ?: 1.0
                val maxMemory =
                    comparable
                        .mapNotNull { it.approximatePeakMemoryBytes }
                        .maxOrNull()
                        ?.toDouble()
                        ?.div(1_048_576.0) ?: 1.0
                comparable.forEach { record ->
                    Text(record.modelId, style = MaterialTheme.typography.labelLarge)
                    MetricBar("RTF ↓", record.realTimeFactor, maxRtf, "")
                    MetricBar(
                        "WER ↓",
                        record.wordErrorRate?.times(100.0),
                        maxWer * 100.0,
                        "%",
                    )
                    MetricBar(
                        "Память ↓",
                        record.approximatePeakMemoryBytes?.div(1_048_576.0),
                        maxMemory,
                        " МБ",
                    )
                }
            }
            if (records.isNotEmpty()) {
                Text("История измерений", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Архив включает тесты с разными настройками; " +
                        "сравнивайте напрямую только графики выше.",
                    style = MaterialTheme.typography.bodySmall,
                )
                records.take(8).forEach { record ->
                    val date =
                        DateFormat
                            .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(record.createdAtEpochMs))
                    Text(
                        date + " · " + record.modelId + " · " + record.sampleId,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    val werText =
                        record.wordErrorRate?.let { metric(it * 100) + "%" } ?: "—"
                    val memoryText =
                        record.approximatePeakMemoryBytes?.let {
                            (it / 1_048_576).toString() + " МБ"
                        } ?: "—"
                    Text(
                        "RTF " + metric(record.realTimeFactor) +
                            " · WER " + werText +
                            " · Память " + memoryText +
                            " · Потоки " + record.config.threadCount,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
