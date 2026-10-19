package io.github.surioustype.localscribe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private val rose = Color(0xFFE7BBB9)
private val paleRose = Color(0xFFFFDAD7)
private val charcoal = Color(0xFF171313)
private val panel = Color(0xFF2B2525)

fun localScribeDarkScheme() =
    darkColorScheme(
        primary = rose,
        onPrimary = Color(0xFF351C1C),
        secondary = paleRose,
        onSecondary = Color(0xFF351C1C),
        background = charcoal,
        onBackground = Color(0xFFF5EEEE),
        surface = panel,
        onSurface = Color(0xFFF5EEEE),
        surfaceVariant = Color(0xFF363030),
        onSurfaceVariant = Color(0xFFD8CCCC),
        outline = Color(0xFF716666),
    )

fun localScribeLightScheme() =
    lightColorScheme(
        primary = Color(0xFF985D5C),
        onPrimary = Color.White,
        secondary = Color(0xFF835D5C),
        background = Color(0xFFFFF9F8),
        onBackground = Color(0xFF221B1B),
        surface = Color(0xFFF5ECEB),
        onSurface = Color(0xFF221B1B),
        surfaceVariant = Color(0xFFEDE1DF),
        onSurfaceVariant = Color(0xFF615251),
        outline = Color(0xFF9A8987),
    )

val localScribeShape = RoundedCornerShape(20.dp)

@Composable
fun localScribeCardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)

@Composable
fun LocalScribeProgress(progress: Float?, modifier: Modifier = Modifier) {
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    } else {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 600),
            label = "LocalScribe progress",
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** Uses h:mm:ss for long recordings; mm:ss for short recordings. */
fun formatAudioDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = seconds / 3_600
    val minutes = (seconds / 60) % 60
    val remainder = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainder)
    } else {
        "%02d:%02d".format(minutes, remainder)
    }
}

fun formatLocalDate(epochMs: Long): String =
    DateFormat
        .getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
        ).format(Date(epochMs))

fun localizedJobStatus(raw: String): String =
    when (raw.uppercase()) {
        "RUNNING" -> "Распознавание"
        "PENDING" -> "В очереди"
        "PAUSED" -> "На паузе"
        "COMPLETED" -> "Завершено"
        "CANCELLED" -> "Отменено"
        "FAILED" -> "Ошибка"
        else -> raw
    }

fun localizedSourceStatus(raw: String): String =
    when (raw.uppercase()) {
        "AVAILABLE" -> "Доступно"
        "MISSING" -> "Файл недоступен"
        "PERMISSION_REQUIRED" -> "Нужен доступ"
        else -> raw
    }
