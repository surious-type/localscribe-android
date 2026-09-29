package io.github.surioustype.localscribe.ui.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptPlaybackControllerTest {
    @Test
    fun `play creates prepares and starts a backend`() =
        runTest {
            val backend = FakeBackend()
            val controller = TranscriptPlaybackController(backgroundScope, { backend })

            controller.play("content://audio/one")
            runCurrent()

            assertEquals(listOf("content://audio/one"), backend.preparedSources)
            assertEquals(1, backend.playCalls)
            assertTrue(controller.state.value.isPlaying)
            assertTrue(controller.state.value.isReady)
        }

    @Test
    fun `recomposition retaining the controller does not close the owned backend`() =
        runTest {
            val backend = FakeBackend()
            val controller = TranscriptPlaybackController(backgroundScope, { backend })

            controller.play("content://audio/one")
            runCurrent()
            controller.pause()
            controller.play("content://audio/one")
            runCurrent()

            assertEquals(0, backend.closeCalls)
            assertEquals(2, backend.playCalls)
        }

    @Test
    fun `seek before play prepares the source and retains the requested position`() =
        runTest {
            val backend = FakeBackend()
            val controller = TranscriptPlaybackController(backgroundScope, { backend })

            controller.seekTo("content://audio/one", 4_200)
            runCurrent()

            assertEquals(listOf(4_200L), backend.seekPositions)
            assertEquals(4_200L, controller.state.value.positionMs)
            assertFalse(controller.state.value.isPlaying)
        }

    @Test
    fun `changing sources releases the old backend before preparing the next one`() =
        runTest {
            val first = FakeBackend()
            val second = FakeBackend()
            val backends = ArrayDeque(listOf(first, second))
            val controller =
                TranscriptPlaybackController(backgroundScope, { backends.removeFirst() })

            controller.play("content://audio/one")
            runCurrent()
            controller.play("content://audio/two")
            runCurrent()

            assertEquals(1, first.closeCalls)
            assertEquals(listOf("content://audio/two"), second.preparedSources)
            assertTrue(controller.state.value.isPlaying)
        }

    @Test
    fun `stop releases active playback and later play recreates it`() =
        runTest {
            val backend = FakeBackend()
            val recreated = FakeBackend()
            val backends = ArrayDeque(listOf(backend, recreated))
            val controller =
                TranscriptPlaybackController(backgroundScope, { backends.removeFirst() })
            controller.play("content://audio/one")
            runCurrent()

            controller.onStop()
            controller.play("content://audio/one")
            runCurrent()

            assertEquals(1, backend.closeCalls)
            assertEquals(listOf("content://audio/one"), recreated.preparedSources)
            assertEquals(listOf(3_200L), recreated.seekPositions)
            assertTrue(controller.state.value.isPlaying)
        }

    @Test
    fun `dispose closes the backend once and ignores later calls`() =
        runTest {
            val backend = FakeBackend()
            val controller = TranscriptPlaybackController(backgroundScope, { backend })
            controller.play("content://audio/one")
            runCurrent()

            controller.close()
            controller.close()
            controller.play("content://audio/two")
            runCurrent()

            assertEquals(1, backend.closeCalls)
            assertEquals(listOf("content://audio/one"), backend.preparedSources)
        }

    @Test
    fun `preparation failure exposes a sanitized error`() =
        runTest {
            val backend = FakeBackend(preparationFailure = IllegalStateException("provider detail"))
            val controller = TranscriptPlaybackController(backgroundScope, { backend })

            controller.play("content://audio/one")
            runCurrent()

            assertEquals("Audio could not be prepared.", controller.state.value.errorMessage)
            assertFalse(controller.state.value.isPlaying)
            assertEquals(1, backend.closeCalls)
        }

    private class FakeBackend(
        private val preparationFailure: Throwable? = null,
    ) : AudioPlayerBackend {
        val preparedSources = mutableListOf<String>()
        val seekPositions = mutableListOf<Long>()
        var playCalls = 0
        var pauseCalls = 0
        var closeCalls = 0
        private var playing = false
        var currentPosition = 3_200L
        private var completionListener: (() -> Unit)? = null

        override suspend fun prepare(sourceUri: String) {
            preparedSources += sourceUri
            preparationFailure?.let { throw it }
        }

        override fun play() {
            playCalls += 1
            playing = true
        }

        override fun pause() {
            pauseCalls += 1
            playing = false
        }

        override fun seekTo(positionMs: Long) {
            seekPositions += positionMs
        }

        override fun currentPositionMs(): Long = currentPosition

        override fun durationMs(): Long = 8_000L

        override fun isPlaying(): Boolean = playing

        override fun setOnCompletionListener(listener: () -> Unit) {
            completionListener = listener
        }

        override fun close() {
            closeCalls += 1
            playing = false
        }
    }
}
