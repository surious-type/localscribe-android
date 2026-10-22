package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.surioustype.localscribe.core.model.TranscriptSegment

/**
 * Continuous reading has no visible per-segment time labels, but retains each original
 * segment's timestamp for seeking, highlighting and restoring the reader scroll anchor.
 */
@Composable
internal fun TranscriptContinuousParagraph(
    segment: TranscriptSegment,
    query: String,
    active: Boolean,
    onSeek: () -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.secondaryContainer
    val markedText =
        remember(segment.text, query, highlight) {
            buildAnnotatedString {
                val phrase = query.trim()
                if (phrase.isEmpty()) {
                    append(segment.text)
                } else {
                    var cursor = 0
                    while (cursor < segment.text.length) {
                        val index = segment.text.indexOf(phrase, cursor, ignoreCase = true)
                        if (index == -1) {
                            append(segment.text.substring(cursor))
                            break
                        }
                        append(segment.text.substring(cursor, index))
                        withStyle(SpanStyle(background = highlight)) {
                            append(segment.text.substring(index, index + phrase.length))
                        }
                        cursor = index + phrase.length
                    }
                }
            }
        }
    SelectionContainer {
        Text(
            text = markedText,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .semantics {
                        stateDescription = "Фрагмент с " + segment.absoluteStartMs / 1_000L + " секунды"
                    }
                    .clickable(onClickLabel = "Перейти к этому месту аудио") { onSeek() }
                    .padding(horizontal = 12.dp, vertical = 3.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true,
        )
    }
}
