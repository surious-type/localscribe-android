package io.github.surioustype.localscribe.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScribeUiStateViewModelTest {
    @Test
    fun pendingDownloadRemainsLatchedWhenSwitchingTabs() {
        val model = LocalScribeUiStateViewModel(SavedStateHandle())
        model.requestModelDownload("tiny")
        model.selectDestination("MODELS")
        model.selectDestination("HOME")
        assertEquals(setOf("tiny"), model.pendingModelDownloads)
        model.reconcileModelDownloadRequests(setOf("tiny"))
        assertTrue(model.pendingModelDownloads.isEmpty())
    }

    @Test
    fun downloadFailureClearsOnlyAffectedModel() {
        val model = LocalScribeUiStateViewModel(SavedStateHandle())
        model.requestModelDownload("small")
        model.requestModelDownload("silero-v6.2.0")
        model.clearModelDownloadRequest("small")
        assertEquals(setOf("silero-v6.2.0"), model.pendingModelDownloads)
    }

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

    @Test
    fun systemBackReturnsToPreviousTabAndClosesDetailsFirst() {
        val handle = SavedStateHandle()
        val model = LocalScribeUiStateViewModel(handle)
        assertFalse(model.canNavigateBack())
        model.selectDestination("MODELS")
        model.selectDestination("PROCESSING")
        model.showTranscript("job-123")

        model.navigateBack()
        assertEquals(null, model.uiState.value.transcriptJobId)
        assertEquals("PROCESSING", model.uiState.value.destination)

        model.navigateBack()
        assertEquals("MODELS", model.uiState.value.destination)
        val recreated = LocalScribeUiStateViewModel(handle)
        recreated.navigateBack()
        assertEquals("HOME", recreated.uiState.value.destination)
        assertFalse(recreated.canNavigateBack())
    }

    @Test
    fun backFromTranscriptRestoresRecordingDetailsAfterRecreation() {
        val handle = SavedStateHandle()
        val first = LocalScribeUiStateViewModel(handle)
        first.selectSource("audio-1")
        first.showTranscript("job-1")

        val recreated = LocalScribeUiStateViewModel(handle)
        assertTrue(recreated.canNavigateBack())
        recreated.navigateBack()
        assertEquals(null, recreated.uiState.value.transcriptJobId)
        assertEquals("audio-1", recreated.uiState.value.selectedSourceId)

        recreated.navigateBack()
        assertEquals(null, recreated.uiState.value.selectedSourceId)
        assertFalse(recreated.canNavigateBack())
    }

    @Test
    fun openingCompletedRecordingGoesDirectlyToTranscriptAndBackToRecordings() {
        val model = LocalScribeUiStateViewModel(SavedStateHandle())
        model.openRecording("audio-1", "job-completed")

        assertEquals("job-completed", model.uiState.value.transcriptJobId)
        assertEquals(null, model.uiState.value.selectedSourceId)

        model.navigateBack()
        assertEquals(null, model.uiState.value.transcriptJobId)
        assertEquals(null, model.uiState.value.selectedSourceId)
        assertEquals("HOME", model.uiState.value.destination)
    }

    @Test
    fun recordingWithoutTranscriptAndExplicitRerunOpenOnlySetup() {
        val model = LocalScribeUiStateViewModel(SavedStateHandle())
        model.openRecording("audio-1", null)
        assertEquals("audio-1", model.uiState.value.selectedSourceId)
        assertEquals(null, model.uiState.value.transcriptJobId)

        model.openRecording("audio-1", "job-old")
        model.retranscribe("audio-1")
        assertEquals("audio-1", model.uiState.value.selectedSourceId)
        assertEquals(null, model.uiState.value.transcriptJobId)

        model.navigateBack()
        assertEquals(null, model.uiState.value.selectedSourceId)
    }

    @Test
    fun backFromRerunSetupRestoresSavedTranscriptEvenAfterRecreation() {
        val handle = SavedStateHandle()
        val original = LocalScribeUiStateViewModel(handle)
        original.openRecording("audio-1", "transcript-old")
        original.retranscribe("audio-1")

        val recreated = LocalScribeUiStateViewModel(handle)
        assertEquals("audio-1", recreated.uiState.value.selectedSourceId)
        assertEquals(null, recreated.uiState.value.transcriptJobId)
        recreated.navigateBack()

        assertEquals(null, recreated.uiState.value.selectedSourceId)
        assertEquals("transcript-old", recreated.uiState.value.transcriptJobId)
        recreated.navigateBack()
        assertEquals(null, recreated.uiState.value.transcriptJobId)
        assertEquals("HOME", recreated.uiState.value.destination)
    }

    @Test
    fun startingRerunClearsReturnRouteWithoutRemovingOldJob() {
        val model = LocalScribeUiStateViewModel(SavedStateHandle())
        model.openRecording("audio-1", "transcript-old")
        model.retranscribe("audio-1")
        model.selectSource(null)
        model.selectDestination("PROCESSING")
        model.navigateBack()
        assertEquals("HOME", model.uiState.value.destination)
        assertEquals(null, model.uiState.value.transcriptJobId)
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

    @Test
    fun transcriptReadingModePersistsThroughNavigationAndVersionSelection() {
        val handle = SavedStateHandle()
        val first = LocalScribeUiStateViewModel(handle)
        first.openRecording("source", "old-version")
        first.setTranscriptReadingMode(TranscriptReadingMode.CONTINUOUS)
        first.setTranscriptQuery("лекция")
        first.showTranscript("new-version")

        val restored = LocalScribeUiStateViewModel(handle).uiState.value
        assertEquals(TranscriptReadingMode.CONTINUOUS.name, restored.transcriptReadingMode)
        assertEquals("new-version", restored.transcriptJobId)
        assertEquals("лекция", restored.transcriptQuery)
    }

    @Test
    fun librarySearchTargetRetainsTimestampAfterRecreation() {
        val handle = SavedStateHandle()
        val model = LocalScribeUiStateViewModel(handle)
        model.setLibraryQuery("Ньютон")
        model.openTranscriptAt("job-123", 42_000L, "Ньютон")

        val restored = LocalScribeUiStateViewModel(handle).uiState.value
        assertEquals("Ньютон", restored.libraryQuery)
        assertEquals("job-123", restored.transcriptJobId)
        assertEquals(42_000L, restored.transcriptSeekToMs)
        assertEquals("Ньютон", restored.transcriptQuery)
        assertFalse(restored.processingVisible)
    }

    @Test
    fun processingOverlayClosesBeforeNavigatingBackToPreviousTab() {
        val handle = SavedStateHandle()
        val model = LocalScribeUiStateViewModel(handle)
        model.selectDestination("MODELS")
        model.showProcessing(true)

        val restored = LocalScribeUiStateViewModel(handle)
        assertTrue(restored.uiState.value.processingVisible)
        restored.navigateBack()
        assertFalse(restored.uiState.value.processingVisible)
        assertEquals("MODELS", restored.uiState.value.destination)
        restored.navigateBack()
        assertEquals("HOME", restored.uiState.value.destination)
    }
}
