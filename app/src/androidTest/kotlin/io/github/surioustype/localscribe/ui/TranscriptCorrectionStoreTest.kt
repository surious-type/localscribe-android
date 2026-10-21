package io.github.surioustype.localscribe.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranscriptCorrectionStoreTest {
    @Test
    fun changesSurviveStoreRecreationAndNeverModifyOriginalAudioTranscript() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val jobId = "edit-test-" + UUID.randomUUID()
            val otherJobId = "edit-test-" + UUID.randomUUID()
            val original = TranscriptSegment("segment-one", "chunk", 100, 200, "Whisper original")
            val first = TranscriptCorrectionStore(context)

            first.save(jobId, original, "Edited text\nAnother paragraph")
            assertEquals("Whisper original", original.text)
            assertEquals(
                "Edited text\nAnother paragraph",
                applyTranscriptCorrections(
                    listOf(original),
                    first.observe(jobId).value,
                ).single().text,
            )
            assertTrue(first.observe(otherJobId).value.isEmpty())

            val reopened = TranscriptCorrectionStore(context)
            assertEquals(
                "Edited text\nAnother paragraph",
                applyTranscriptCorrections(
                    listOf(original),
                    reopened.observe(jobId).value,
                ).single().text,
            )
            reopened.restore(jobId, original.id)
            assertFalse(reopened.observe(jobId).value.containsKey(original.id))
            assertEquals(
                "Whisper original",
                applyTranscriptCorrections(
                    listOf(original),
                    reopened.observe(jobId).value,
                ).single().text,
            )
        }

    @Test
    fun savingTheOriginalTextRemovesOnlyThatCorrection() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val jobId = "edit-test-" + UUID.randomUUID()
            val first = TranscriptSegment("a", "chunk", 0, 1_000, "first")
            val second = TranscriptSegment("b", "chunk", 1_000, 2_000, "second")
            val store = TranscriptCorrectionStore(context)

            store.save(jobId, first, "edited first")
            store.save(jobId, second, "edited second")
            store.save(jobId, first, "first")
            assertFalse(store.observe(jobId).value.containsKey("a"))
            assertEquals("edited second", store.observe(jobId).value["b"]?.editedText)
        }
}
