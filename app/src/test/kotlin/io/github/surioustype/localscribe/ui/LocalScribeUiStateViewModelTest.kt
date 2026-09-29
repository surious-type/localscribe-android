package io.github.surioustype.localscribe.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScribeUiStateViewModelTest {
    @Test
    fun `inbound shared audio is consumed once after state recreation`() {
        val state = SavedStateHandle()
        val first = LocalScribeUiStateViewModel(state)

        assertTrue(first.consumeInboundAudio("content://shared/audio"))
        assertFalse(
            LocalScribeUiStateViewModel(state).consumeInboundAudio("content://shared/audio"),
        )
        assertTrue(LocalScribeUiStateViewModel(state).consumeInboundAudio("content://shared/next"))
    }

    @Test fun selectionsAndSearchRestoreFromSavedState() {
        val handle = SavedStateHandle()
        val first = LocalScribeUiStateViewModel(handle)
        first.selectDestination("MODELS")
        first.selectSource("audio-1")
        first.showTranscript("job-1")
        first.setTranscriptQuery("boundary")
        first.setExportFormat("VTT")
        first.setSourceSetup(
            modelId = "small",
            language = "ru",
            message = "Model is unavailable",
            vadModelId = "silero-v6.2.0",
        )
        first.setSelectedBenchmarkModels(setOf("tiny", "small"))
        first.setBenchmarkStatus("Benchmark saved")
        first.setQualityResult(
            QualityResultPresentation(
                referenceText = "Ask not what your country can do for you",
                recognizedText = "Ask what your country can do for you",
                wordErrors = 1,
                referenceWordCount = 10,
                differences = "deletion:not",
                fixture = "JFK · Public domain · https://example.invalid/jfk",
            ),
        )

        val restoredModel = LocalScribeUiStateViewModel(handle)
        val restored = restoredModel.uiState.value
        assertEquals("silero-v6.2.0", restored.sourceVadModelId)
        assertEquals("MODELS", restored.destination)
        assertEquals("audio-1", restored.selectedSourceId)
        assertEquals("job-1", restored.transcriptJobId)
        assertEquals("boundary", restored.transcriptQuery)
        assertEquals("VTT", restored.exportFormat)
        assertEquals(null, restored.statusMessage)
        assertEquals("small", restored.sourceModelId)
        assertEquals("ru", restored.sourceLanguage)
        assertEquals("Model is unavailable", restored.sourceMessage)
        assertEquals(setOf("tiny", "small"), restored.selectedBenchmarkModels)
        assertEquals("Benchmark saved", restored.benchmarkStatus)
        assertEquals("Ask what your country can do for you", restored.qualityResult?.recognizedText)

        restoredModel.completeTranscriptExport(true)
        val afterExportResult = LocalScribeUiStateViewModel(handle).uiState.value
        assertEquals(null, afterExportResult.exportFormat)
        assertEquals("Transcript exported", afterExportResult.statusMessage)
    }
}
