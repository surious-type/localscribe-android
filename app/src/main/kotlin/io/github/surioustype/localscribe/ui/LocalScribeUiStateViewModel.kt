package io.github.surioustype.localscribe.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retains navigation and transient screen choices through activity recreation. */
class LocalScribeUiStateViewModel(
    private val state: SavedStateHandle,
) : ViewModel() {
    val benchmarkController = BenchmarkRunController(viewModelScope)

    // Back from rerun setup returns to the already saved transcript.
    private var returnTranscriptAfterRerun: String? = state[RETURN_TRANSCRIPT_AFTER_RERUN]

    // A tab change must not show the download action again before the repository
    // publishes its first durable download state.
    var pendingModelDownloads by mutableStateOf(emptySet<String>())
        private set

    fun requestModelDownload(modelId: String) {
        pendingModelDownloads = pendingModelDownloads + modelId
    }

    fun clearModelDownloadRequest(modelId: String) {
        pendingModelDownloads = pendingModelDownloads - modelId
    }

    fun reconcileModelDownloadRequests(acknowledgedIds: Set<String>) {
        pendingModelDownloads = pendingModelDownloads - acknowledgedIds
    }

    private val destinationHistory =
        (state.get<ArrayList<String>>(DESTINATION_HISTORY) ?: arrayListOf()).toMutableList()
    private val mutable =
        MutableStateFlow(
            LocalScribeUiState(
                destination = state[DESTINATION] ?: "HOME",
                selectedSourceId = state[SELECTED_SOURCE],
                transcriptJobId = state[TRANSCRIPT_JOB],
                processingVisible = state[PROCESSING_VISIBLE] ?: false,
                libraryQuery = state[LIBRARY_QUERY] ?: "",
                transcriptSeekToMs = state[TRANSCRIPT_SEEK],
                transcriptQuery = state[TRANSCRIPT_QUERY] ?: "",
                transcriptReadingMode = state[TRANSCRIPT_READING_MODE] ?: "TIMECODES",
                exportFormat = state[EXPORT_FORMAT],
                statusMessage = state[STATUS_MESSAGE],
                sourceModelId = state[SOURCE_MODEL_ID],
                sourceVadModelId = state[SOURCE_VAD_MODEL_ID],
                sourceLanguage = state[SOURCE_LANGUAGE] ?: "auto",
                sourceMessage = state[SOURCE_MESSAGE],
                selectedBenchmarkModels =
                    (state[SELECTED_BENCHMARK_MODELS] ?: emptyList<String>())
                        .toSet(),
                benchmarkStatus = state[BENCHMARK_STATUS],
                qualityResult = qualityResult(state),
            ),
        )
    val uiState: StateFlow<LocalScribeUiState> = mutable.asStateFlow()

    fun selectDestination(value: String) {
        val current = mutable.value.destination
        if (value == current) return
        destinationHistory.add(current)
        state[DESTINATION_HISTORY] = ArrayList(destinationHistory)
        update { it.copy(destination = value, processingVisible = false) }
    }

    /** Android Back returns through visited tabs, then the recordings root. */
    fun navigateBack() {
        val current = mutable.value
        when {
            current.processingVisible -> showProcessing(false)
            current.transcriptJobId != null -> showTranscript(null)
            current.selectedSourceId != null && returnTranscriptAfterRerun != null -> {
                val previous = returnTranscriptAfterRerun
                returnTranscriptAfterRerun = null
                state[RETURN_TRANSCRIPT_AFTER_RERUN] = null
                update { it.copy(selectedSourceId = null, transcriptJobId = previous) }
            }
            current.selectedSourceId != null -> selectSource(null)
            destinationHistory.isNotEmpty() -> {
                val previous = destinationHistory.removeAt(destinationHistory.lastIndex)
                state[DESTINATION_HISTORY] = ArrayList(destinationHistory)
                update { it.copy(destination = previous) }
            }
            current.destination != "HOME" -> update { it.copy(destination = "HOME") }
        }
    }

    fun canNavigateBack(): Boolean =
        mutable.value.processingVisible ||
            mutable.value.transcriptJobId != null ||
            mutable.value.selectedSourceId != null ||
            destinationHistory.isNotEmpty() ||
            mutable.value.destination != "HOME"

    fun showProcessing(visible: Boolean) = update { it.copy(processingVisible = visible) }

    fun setLibraryQuery(query: String) = update { it.copy(libraryQuery = query) }

    fun openTranscriptAt(jobId: String, startMs: Long, query: String) =
        update {
            it.copy(
                transcriptJobId = jobId,
                selectedSourceId = null,
                processingVisible = false,
                transcriptSeekToMs = startMs,
                transcriptQuery = query,
            )
        }

    fun selectSource(value: String?) {
        if (value == null) {
            returnTranscriptAfterRerun = null
            state[RETURN_TRANSCRIPT_AFTER_RERUN] = null
        }
        update { it.copy(selectedSourceId = value) }
    }

    /**
     * Tapping a recording opens its existing transcript when one is available.
     * Otherwise it opens the transcription setup. Never starts a job implicitly.
     */
    fun openRecording(sourceId: String, completedJobId: String?) {
        returnTranscriptAfterRerun = null
        state[RETURN_TRANSCRIPT_AFTER_RERUN] = null
        update {
            it.copy(
                selectedSourceId = if (completedJobId == null) sourceId else null,
                transcriptJobId = completedJobId,
                transcriptSeekToMs = null,
            )
        }
    }

    /** Explicit secondary action. Previous completed jobs and transcripts remain intact. */
    fun retranscribe(sourceId: String) {
        returnTranscriptAfterRerun = mutable.value.transcriptJobId
        state[RETURN_TRANSCRIPT_AFTER_RERUN] = returnTranscriptAfterRerun
        update {
            it.copy(
                selectedSourceId = sourceId,
                transcriptJobId = null,
                sourceMessage = null,
            )
        }
    }

    fun showTranscript(value: String?) =
        update { it.copy(transcriptJobId = value, transcriptSeekToMs = null) }

    fun setTranscriptQuery(value: String) = update { it.copy(transcriptQuery = value) }

    fun setTranscriptReadingMode(value: TranscriptReadingMode) =
        update { it.copy(transcriptReadingMode = value.name) }

    fun setExportFormat(value: String?) = update { it.copy(exportFormat = value) }

    fun completeTranscriptExport(succeeded: Boolean) =
        update {
            it.copy(
                exportFormat = null,
                statusMessage =
                    if (succeeded) "Transcript exported" else "Transcript export failed",
            )
        }

    fun setStatusMessage(value: String?) = update { it.copy(statusMessage = value) }

    /** Returns true exactly once for an inbound URI across activity recreation. */
    fun consumeInboundAudio(uri: String): Boolean {
        if (state.get<String>(CONSUMED_INBOUND_AUDIO) == uri) return false
        state[CONSUMED_INBOUND_AUDIO] = uri
        return true
    }

    fun setSourceSetup(
        modelId: String?,
        language: String,
        message: String?,
        vadModelId: String? = mutable.value.sourceVadModelId,
    ) =
        update {
            it.copy(
                sourceModelId = modelId,
                sourceVadModelId = vadModelId,
                sourceLanguage = language,
                sourceMessage = message,
            )
        }

    fun setSelectedBenchmarkModels(value: Set<String>) =
        update { it.copy(selectedBenchmarkModels = value) }

    fun setBenchmarkStatus(value: String?) = update { it.copy(benchmarkStatus = value) }

    fun setQualityResult(value: QualityResultPresentation?) =
        update {
            it.copy(
                qualityResult = value,
            )
        }

    private fun update(transform: (LocalScribeUiState) -> LocalScribeUiState) {
        val next = transform(mutable.value)
        mutable.value = next
        state[DESTINATION] = next.destination
        state[SELECTED_SOURCE] = next.selectedSourceId
        state[TRANSCRIPT_JOB] = next.transcriptJobId
        state[PROCESSING_VISIBLE] = next.processingVisible
        state[LIBRARY_QUERY] = next.libraryQuery
        state[TRANSCRIPT_SEEK] = next.transcriptSeekToMs
        state[TRANSCRIPT_QUERY] = next.transcriptQuery
        state[TRANSCRIPT_READING_MODE] = next.transcriptReadingMode
        state[EXPORT_FORMAT] = next.exportFormat
        state[STATUS_MESSAGE] = next.statusMessage
        state[SOURCE_MODEL_ID] = next.sourceModelId
        state[SOURCE_VAD_MODEL_ID] = next.sourceVadModelId
        state[SOURCE_LANGUAGE] = next.sourceLanguage
        state[SOURCE_MESSAGE] = next.sourceMessage
        state[SELECTED_BENCHMARK_MODELS] = ArrayList(next.selectedBenchmarkModels)
        state[BENCHMARK_STATUS] = next.benchmarkStatus
        state[QUALITY_REFERENCE_TEXT] = next.qualityResult?.referenceText
        state[QUALITY_RECOGNIZED_TEXT] = next.qualityResult?.recognizedText
        state[QUALITY_WORD_ERRORS] = next.qualityResult?.wordErrors
        state[QUALITY_REFERENCE_WORD_COUNT] = next.qualityResult?.referenceWordCount
        state[QUALITY_DIFFERENCES] = next.qualityResult?.differences
        state[QUALITY_FIXTURE] = next.qualityResult?.fixture
    }

    private fun qualityResult(state: SavedStateHandle): QualityResultPresentation? {
        val reference = state.get<String>(QUALITY_REFERENCE_TEXT) ?: return null
        return QualityResultPresentation(
            referenceText = reference,
            recognizedText = state[QUALITY_RECOGNIZED_TEXT] ?: "",
            wordErrors = state[QUALITY_WORD_ERRORS] ?: 0,
            referenceWordCount = state[QUALITY_REFERENCE_WORD_COUNT] ?: 0,
            differences = state[QUALITY_DIFFERENCES] ?: "",
            fixture = state[QUALITY_FIXTURE] ?: "",
        )
    }

    private companion object {
        const val DESTINATION = "destination"
        const val DESTINATION_HISTORY = "destination_history"
        const val SELECTED_SOURCE = "selected_source"
        const val RETURN_TRANSCRIPT_AFTER_RERUN = "return_transcript_after_rerun"
        const val TRANSCRIPT_JOB = "transcript_job"
        const val PROCESSING_VISIBLE = "processing_visible"
        const val LIBRARY_QUERY = "library_query"
        const val TRANSCRIPT_SEEK = "transcript_seek"
        const val TRANSCRIPT_QUERY = "transcript_query"
        const val TRANSCRIPT_READING_MODE = "transcript_reading_mode"
        const val EXPORT_FORMAT = "export_format"
        const val STATUS_MESSAGE = "status_message"
        const val CONSUMED_INBOUND_AUDIO = "consumed_inbound_audio"
        const val SOURCE_MODEL_ID = "source_model_id"
        const val SOURCE_VAD_MODEL_ID = "source_vad_model_id"
        const val SOURCE_LANGUAGE = "source_language"
        const val SOURCE_MESSAGE = "source_message"
        const val SELECTED_BENCHMARK_MODELS = "selected_benchmark_models"
        const val BENCHMARK_STATUS = "benchmark_status"
        const val QUALITY_REFERENCE_TEXT = "quality_reference_text"
        const val QUALITY_RECOGNIZED_TEXT = "quality_recognized_text"
        const val QUALITY_WORD_ERRORS = "quality_word_errors"
        const val QUALITY_REFERENCE_WORD_COUNT = "quality_reference_word_count"
        const val QUALITY_DIFFERENCES = "quality_differences"
        const val QUALITY_FIXTURE = "quality_fixture"
    }
}

data class LocalScribeUiState(
    val destination: String,
    val selectedSourceId: String?,
    val transcriptJobId: String?,
    val processingVisible: Boolean,
    val libraryQuery: String,
    val transcriptSeekToMs: Long?,
    val transcriptQuery: String,
    val transcriptReadingMode: String,
    val exportFormat: String?,
    val statusMessage: String?,
    val sourceModelId: String?,
    val sourceVadModelId: String?,
    val sourceLanguage: String,
    val sourceMessage: String?,
    val selectedBenchmarkModels: Set<String>,
    val benchmarkStatus: String?,
    val qualityResult: QualityResultPresentation?,
)

data class QualityResultPresentation(
    val referenceText: String,
    val recognizedText: String,
    val wordErrors: Int,
    val referenceWordCount: Int,
    val differences: String,
    val fixture: String,
)
