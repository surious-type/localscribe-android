package io.github.surioustype.localscribe.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.surioustype.localscribe.core.domain.ExportManager
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.ExportFormat
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptExporter(private val context: Context) {
    private val exporter = ExportManager()

    fun document(source: AudioSource, segments: List<TranscriptSegment>, format: ExportFormat) =
        exporter.export(source, segments, format)

    suspend fun write(
        uri: Uri,
        source: AudioSource,
        segments: List<TranscriptSegment>,
        format: ExportFormat,
    ) =
        withContext(Dispatchers.IO) {
            val document = document(source, segments, format)
            requireNotNull(
                context.contentResolver.openOutputStream(uri),
            ).use { it.write(document.content) }
        }

    suspend fun share(
        source: AudioSource,
        segments: List<TranscriptSegment>,
        format: ExportFormat,
    ) {
        val document =
            withContext(Dispatchers.IO) {
                document(source, segments, format).also { output ->
                    val directory = File(context.cacheDir, "exports").also(File::mkdirs)
                    File(
                        directory,
                        output.suggestedFileName,
                    ).outputStream().use { it.write(output.content) }
                }
            }
        val file = File(File(context.cacheDir, "exports"), document.suggestedFileName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent
                .createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = document.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    null,
                ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
