package io.github.surioustype.localscribe.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retains navigation and transient screen choices through activity recreation. */
class LocalScribeUiStateViewModel(
    private val state: SavedStateHandle,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            LocalScribeUiState(
                destination = state[DESTINATION] ?: "HOME",
                selectedSourceId = state[SELECTED_SOURCE],
                transcriptJobId = state[TRANSCRIPT_JOB],
                transcriptQuery = state[TRANSCRIPT_QUERY] ?: "",
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

    fun selectDestination(value: String) = update { it.copy(destination = value) }

    fun selectSource(value: String?) = update { it.copy(selectedSourceId = value) }

    fun showTranscript(value: String?) = update { it.copy(transcriptJobId = value) }

    fun setTranscriptQuery(value: String) = update { it.copy(transcriptQuery = value) }

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
        state[TRANSCRIPT_QUERY] = next.transcriptQuery
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
        const val SELECTED_SOURCE = "selected_source"
        const val TRANSCRIPT_JOB = "transcript_job"
        const val TRANSCRIPT_QUERY = "transcript_query"
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
    val transcriptQuery: String,
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
