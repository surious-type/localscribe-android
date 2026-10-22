@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.surioustype.localscribe.core.domain.TranscriptAssembler
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.di.AppGraph
import java.text.DateFormat
import java.util.Date

/** Lists completed attempts only; never changes or deletes an existing transcript. */
@Composable
internal fun TranscriptVersionsSheet(
    graph: AppGraph,
    current: TranscriptionJob,
    versions: List<TranscriptionJob>,
    primaryId: String?,
    onChooseVersion: (String) -> Unit,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit,
) {
    var comparison by remember { mutableStateOf<TranscriptionJob?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("История распознавания", style = MaterialTheme.typography.titleLarge)
            Text(
                "Все завершённые версии сохранены. Основная открывается из списка записей.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 510.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(versions, key = { it.id }) { version ->
                    val isPrimary = preferredCompletedTranscript(versions, current.sourceId, primaryId)
                        ?.id == version.id
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                version.config.modelId +
                                    if (isPrimary) " · Основная" else "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Создано: " +
                                    DateFormat.getDateTimeInstance().format(
                                        Date(version.createdAtEpochMs),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val config = version.config.inference
                            Text(
                                "Язык: " + (config.language ?: "авто") +
                                    " · Потоки: " + config.threadCount +
                                    " · VAD: " + if (config.vad != null) "включён" else "выключен",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Перевод: " +
                                    if (config.translateToEnglish) "на английский" else "нет",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Хеш модели: " + version.modelHash.take(12) +
                                    " · Окно: " + version.config.chunkDurationMs / 1_000L + " с",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = {
                                    onDismiss()
                                    onChooseVersion(version.id)
                                }) { Text(if (version.id == current.id) "Открыта" else "Открыть") }
                                if (!isPrimary) {
                                    TextButton(onClick = {
                                        runCatching {
                                            graph.transcriptVersions.setPrimary(
                                                current.sourceId,
                                                version.id,
                                            )
                                        }.onFailure {
                                            onStatus("Не удалось сохранить основную версию")
                                        }
                                    }) { Text("Сделать основной") }
                                }
                            }
                            if (version.id != current.id) {
                                OutlinedButton(onClick = { comparison = version }) {
                                    Text("Сравнить с открытой")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    comparison?.let { older ->
        TranscriptVersionComparisonDialog(
            graph = graph,
            left = older,
            right = current,
            onDismiss = { comparison = null },
        )
    }
}

/** Time-window diff is bounded in memory and handles unequal ASR segment boundaries. */
@Composable
private fun TranscriptVersionComparisonDialog(
    graph: AppGraph,
    left: TranscriptionJob,
    right: TranscriptionJob,
    onDismiss: () -> Unit,
) {
    val leftChunks by remember(left.id) {
        graph.transcriptionRepository.observeChunks(left.id)
    }.collectAsStateWithLifecycle(emptyList())
    val leftSegments by remember(left.id) {
        graph.transcriptionRepository.observeSegments(left.id)
    }.collectAsStateWithLifecycle(emptyList())
    val rightChunks by remember(right.id) {
        graph.transcriptionRepository.observeChunks(right.id)
    }.collectAsStateWithLifecycle(emptyList())
    val rightSegments by remember(right.id) {
        graph.transcriptionRepository.observeSegments(right.id)
    }.collectAsStateWithLifecycle(emptyList())
    val changes = remember(leftChunks, leftSegments, rightChunks, rightSegments) {
        val before = TranscriptAssembler().assemble(leftChunks, leftSegments)
        val after = TranscriptAssembler().assemble(rightChunks, rightSegments)
        compareTranscriptVersions(before, after)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сравнение версий") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    left.config.modelId + " → " + right.config.modelId +
                        " · Отличающихся участков: " + changes.size,
                    style = MaterialTheme.typography.bodyMedium,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 410.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (changes.isEmpty()) {
                        item {
                            Text("Различий по тексту нет. Сегменты могут иметь разные границы.")
                        }
                    }
                    items(changes) { change ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "От " + (change.startMs / 60_000L) + ":" +
                                    "%02d".format((change.startMs / 1_000L) % 60L),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Было: " + change.before.ifBlank { "—" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Стало: " + change.after.ifBlank { "—" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
