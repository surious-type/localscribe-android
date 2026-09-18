package io.github.surioustype.localscribe.core.domain

import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.ExportDocument
import io.github.surioustype.localscribe.core.model.ExportFormat
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

class ExportManager {
    fun export(
        source: AudioSource,
        segments: List<TranscriptSegment>,
        format: ExportFormat,
    ): ExportDocument {
        require(source.durationMs >= 0) { "Source duration must not be negative" }
        val ordered =
            segments.sortedWith(
                compareBy<TranscriptSegment> {
                    it.absoluteStartMs
                }.thenBy { it.absoluteEndMs }.thenBy { it.id },
            )
        ordered.forEach { segment ->
            require(
                segment.absoluteStartMs >= 0 &&
                    segment.absoluteEndMs > segment.absoluteStartMs &&
                    segment.absoluteEndMs <= source.durationMs,
            ) { "Segment timestamps must be inside the source" }
        }

        val specification =
            when (format) {
                ExportFormat.TXT -> ExportSpecification("txt", "text/plain", plainText(ordered))
                ExportFormat.MARKDOWN ->
                    ExportSpecification(
                        "md",
                        "text/markdown",
                        markdown(source, ordered),
                    )
                ExportFormat.SRT -> ExportSpecification("srt", "application/x-subrip", srt(ordered))
                ExportFormat.VTT -> ExportSpecification("vtt", "text/vtt", vtt(ordered))
                ExportFormat.JSON ->
                    ExportSpecification(
                        "json",
                        "application/json",
                        json(source, ordered),
                    )
            }
        return ExportDocument(
            suggestedFileName = "${baseName(source.displayName)}.${specification.extension}",
            mimeType = specification.mimeType,
            content = specification.text.toByteArray(Charsets.UTF_8),
        )
    }

    private fun plainText(segments: List<TranscriptSegment>): String =
        segments.joinToString(
            separator = "\n",
            postfix = if (segments.isEmpty()) "" else "\n",
        ) { it.text }

    private fun markdown(source: AudioSource, segments: List<TranscriptSegment>): String =
        "# ${escapeMarkdown(source.displayName)}\n\n" +
            segments.joinToString(
                separator = "\n",
                postfix = if (segments.isEmpty()) "" else "\n",
            ) {
                escapeMarkdown(it.text)
            }

    private fun srt(segments: List<TranscriptSegment>): String =
        segments
            .mapIndexed { index, segment ->
                "${index + 1}\n" +
                    "${timestamp(
                        segment.absoluteStartMs,
                        ',',
                    )} --> ${timestamp(segment.absoluteEndMs, ',')}\n" +
                    "${segment.text}\n"
            }.joinToString("\n")

    private fun vtt(segments: List<TranscriptSegment>): String =
        "WEBVTT\n\n" +
            segments.joinToString(
                separator = "\n\n",
                postfix = if (segments.isEmpty()) "" else "\n",
            ) { segment ->
                "${timestamp(
                    segment.absoluteStartMs,
                    '.',
                )} --> ${timestamp(segment.absoluteEndMs, '.')}\n${segment.text}"
            }

    private fun json(source: AudioSource, segments: List<TranscriptSegment>): String {
        val root =
            buildJsonObject {
                put("sourceId", source.id)
                put("displayName", source.displayName)
                put("durationMs", source.durationMs)
                put("mimeType", source.mimeType)
                put(
                    "segments",
                    buildJsonArray {
                        segments.forEach { segment ->
                            add(
                                buildJsonObject {
                                    put("id", segment.id)
                                    put("chunkId", segment.chunkId)
                                    put("startMs", segment.absoluteStartMs)
                                    put("endMs", segment.absoluteEndMs)
                                    put("text", segment.text)
                                },
                            )
                        }
                    },
                )
            }
        return JSON.encodeToString(root) + "\n"
    }

    private fun timestamp(milliseconds: Long, millisecondSeparator: Char): String {
        val hours = milliseconds / MILLIS_PER_HOUR
        val afterHours = milliseconds % MILLIS_PER_HOUR
        val minutes = afterHours / MILLIS_PER_MINUTE
        val afterMinutes = afterHours % MILLIS_PER_MINUTE
        val seconds = afterMinutes / MILLIS_PER_SECOND
        val millis = afterMinutes % MILLIS_PER_SECOND
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d%c%03d",
            hours,
            minutes,
            seconds,
            millisecondSeparator,
            millis,
        )
    }

    private fun baseName(displayName: String): String {
        val trimmed = displayName.trim().ifEmpty { "transcript" }
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }

    private fun escapeMarkdown(text: String): String =
        buildString {
            text.forEach { character ->
                if (character in MARKDOWN_SPECIAL_CHARACTERS) append('\\')
                append(character)
            }
        }

    private data class ExportSpecification(
        val extension: String,
        val mimeType: String,
        val text: String,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
        const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
        val MARKDOWN_SPECIAL_CHARACTERS =
            setOf('\\', '`', '*', '_', '{', '}', '[', ']', '<', '>', '#', '+', '-', '!', '|')
        val JSON = Json { prettyPrint = true }
    }
}
