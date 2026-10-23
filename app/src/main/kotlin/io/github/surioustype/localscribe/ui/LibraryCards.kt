package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.surioustype.localscribe.core.model.AudioSourceRecord

@Composable
internal fun LibraryRecordingCard(
    record: AudioSourceRecord,
    presentation: LibraryRecordPresentation,
    hasTranscript: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    val tone =
        when (presentation.state) {
            LibraryRecordState.FAILED, LibraryRecordState.SOURCE_UNAVAILABLE ->
                MaterialTheme.colorScheme.error
            LibraryRecordState.READY -> MaterialTheme.colorScheme.primary
            LibraryRecordState.RUNNING, LibraryRecordState.QUEUED ->
                MaterialTheme.colorScheme.primary
            LibraryRecordState.PAUSED, LibraryRecordState.CANCELLED ->
                MaterialTheme.colorScheme.secondary
            LibraryRecordState.NO_TRANSCRIPT -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val icon =
        when (presentation.state) {
            LibraryRecordState.READY -> Icons.Default.Description
            LibraryRecordState.RUNNING, LibraryRecordState.QUEUED -> Icons.Default.Refresh
            LibraryRecordState.PAUSED -> Icons.Default.Pause
            LibraryRecordState.FAILED, LibraryRecordState.SOURCE_UNAVAILABLE ->
                Icons.Default.Warning
            LibraryRecordState.CANCELLED -> Icons.Default.Close
            LibraryRecordState.NO_TRANSCRIPT -> Icons.Default.Mic
        }
    Card(
        modifier =
            Modifier.fillMaxWidth().clickable(onClickLabel = "Открыть запись") { onOpen() },
        colors = localScribeCardColors(),
        shape = localScribeShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(
                            tone.copy(alpha = 0.12f),
                            RoundedCornerShape(14.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tone)
            }
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    libraryTitle(record),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val dateDescription =
                    record.recordedAtEpochMs?.let {
                        "Записано: " + formatLocalDate(it)
                    } ?: record.addedAtEpochMs?.let {
                        "Добавлено: " + formatLocalDate(it)
                    } ?: "Дата неизвестна"
                Text(
                    formatAudioDuration(record.source.durationMs) + " · " + dateDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    color = tone.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(50.dp),
                ) {
                    Text(
                        presentation.status,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = tone,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                val addedAt = record.addedAtEpochMs
                if (record.recordedAtEpochMs != null && addedAt != null) {
                    Text(
                        "Добавлено: " + formatLocalDate(addedAt),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (presentation.state != LibraryRecordState.READY &&
                    presentation.state != LibraryRecordState.NO_TRANSCRIPT
                ) {
                    Text(
                        presentation.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Действия с записью " + libraryTitle(record)
                        },
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    if (hasTranscript) {
                        DropdownMenuItem(
                            text = { Text("Распознать заново") },
                            onClick = {
                                menuOpen = false
                                onRetranscribe()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibrarySearchResultCard(
    hit: LibrarySearchHit,
    onOpen: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Открыть совпадение") {
                    onOpen()
                },
        colors = localScribeCardColors(),
        shape = localScribeShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (hit.startMs == null) Icons.Default.Folder else Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    libraryTitle(hit.record),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    hit.startMs?.let { "В тексте · " + formatAudioDuration(it) }
                        ?: "Совпадение в названии",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
                hit.snippet?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LibraryEmptyState(
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = localScribeCardColors(),
        shape = localScribeShape,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(16.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun LibraryFeedbackMessage(
    message: String,
    onDismiss: () -> Unit,
) {
    val failure =
        message.startsWith("Не удалось") ||
            message.startsWith("Доступ к библиотеке не предоставлен")
    val tone =
        if (failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        color = tone.copy(alpha = 0.10f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (failure) Icons.Default.Warning else Icons.Default.Description,
                contentDescription = null,
                tint = tone,
            )
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Скрыть уведомление")
            }
        }
    }
}
