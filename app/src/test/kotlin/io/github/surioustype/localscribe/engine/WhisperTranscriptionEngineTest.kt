package io.github.surioustype.localscribe.engine

import io.github.surioustype.localscribe.core.model.EngineSegment
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.VadConfig
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class WhisperTranscriptionEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `loads verified model once and returns relative engine timestamps`() =
        runTest {
            val model =
                installedModel(temporaryFolder.newFile("model.bin").apply { writeText("model") })
            val bridge = FakeWhisperNativeBridge()
            val engine = WhisperTranscriptionEngine(Dispatchers.Unconfined, bridge)

            engine.loadModel(model)
            engine.loadModel(model)
            val result =
                engine.transcribe(
                    pcm = floatArrayOf(0f),
                    config = InferenceConfig(threadCount = 2, language = "en"),
                    prompt = "context",
                    cancellationSignal = CancellationSignal { false },
                )
            engine.unloadModel()

            assertEquals(1, bridge.loadCount)
            assertEquals(listOf(EngineSegment(120, 430, " hello")), result.segments)
            assertEquals("en", result.detectedLanguage)
            assertEquals(1, bridge.unloadCount)
        }

    @Test
    fun `rejects configured VAD when no verified VAD model was loaded`() =
        runTest {
            val model =
                installedModel(temporaryFolder.newFile("model.bin").apply { writeText("model") })
            val engine =
                WhisperTranscriptionEngine(Dispatchers.Unconfined, FakeWhisperNativeBridge())
            engine.loadModel(model)

            val failure =
                runCatching {
                    engine.transcribe(
                        floatArrayOf(0f),
                        InferenceConfig(2, vad = VadConfig("silero", "abc")),
                        null,
                        CancellationSignal { false },
                    )
                }.exceptionOrNull()

            assertTrue(failure is WhisperEngineException)
            assertEquals("vad_model_not_loaded", failure?.message)
        }

    @Test
    fun `coroutine cancellation aborts native work before unload`() =
        runTest {
            val model =
                installedModel(temporaryFolder.newFile("model.bin").apply { writeText("model") })
            val bridge = FakeWhisperNativeBridge(blockUntilCancelled = true)
            val engine = WhisperTranscriptionEngine(Dispatchers.Default, bridge)
            engine.loadModel(model)

            val transcription =
                async(Dispatchers.Default) {
                    engine.transcribe(
                        floatArrayOf(0f),
                        InferenceConfig(2),
                        null,
                        CancellationSignal { false },
                    )
                }
            while (!bridge.transcriptionStarted) {
                Thread.yield()
            }
            transcription.cancelAndJoin()
            engine.unloadModel()

            assertTrue(bridge.abortCalled)
            assertFalse(bridge.transcriptionRunning)
            assertEquals(1, bridge.unloadCount)
        }

    @Test
    fun `external cancellation after final native poll discards successful result`() =
        runTest {
            val model =
                installedModel(temporaryFolder.newFile("model.bin").apply { writeText("model") })
            var externalCancellation = false
            val bridge =
                FakeWhisperNativeBridge(
                    onAfterFinalCancellationPoll = { externalCancellation = true },
                )
            val engine = WhisperTranscriptionEngine(Dispatchers.Unconfined, bridge)
            engine.loadModel(model)

            val failure =
                runCatching {
                    engine.transcribe(
                        floatArrayOf(0f),
                        InferenceConfig(2),
                        null,
                        CancellationSignal { externalCancellation },
                    )
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        }

    @Test
    fun `typed native load failures retain actionable domain codes`() =
        runTest {
            val expectedCodes =
                listOf(
                    NativeErrorCode.OUT_OF_MEMORY to FailureCode.INSUFFICIENT_MEMORY,
                    NativeErrorCode.INVALID_MODEL to FailureCode.MODEL_CORRUPTED,
                    NativeErrorCode.FAILURE to FailureCode.NATIVE_FAILURE,
                )

            expectedCodes.forEach { (nativeCode, domainCode) ->
                val model =
                    installedModel(
                        temporaryFolder.newFile("model-$nativeCode.bin").apply {
                            writeText("model")
                        },
                    )
                val engine =
                    WhisperTranscriptionEngine(
                        Dispatchers.Unconfined,
                        FakeWhisperNativeBridge(loadFailure = NativeBridgeException(nativeCode)),
                    )

                val failure = runCatching { engine.loadModel(model) }.exceptionOrNull()

                assertTrue(failure is WhisperEngineException)
                assertEquals(domainCode, (failure as WhisperEngineException).failure.code)
            }
        }

    @Test
    fun `typed native cancellation remains coroutine cancellation`() =
        runTest {
            val model =
                installedModel(temporaryFolder.newFile("model.bin").apply { writeText("model") })
            val bridge =
                FakeWhisperNativeBridge(
                    transcribeFailure = NativeBridgeException(NativeErrorCode.CANCELLED),
                )
            val engine = WhisperTranscriptionEngine(Dispatchers.Unconfined, bridge)
            engine.loadModel(model)

            val failure =
                runCatching {
                    engine.transcribe(
                        floatArrayOf(0f),
                        InferenceConfig(2),
                        null,
                        CancellationSignal { false },
                    )
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        }

    private fun installedModel(file: File): InstalledModel {
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        return InstalledModel("installed", "tiny", "Tiny", file.path, hash, file.length(), 1, 1)
    }

    private class FakeWhisperNativeBridge(
        private val blockUntilCancelled: Boolean = false,
        private val onAfterFinalCancellationPoll: () -> Unit = {},
        private val loadFailure: Throwable? = null,
        private val transcribeFailure: Throwable? = null,
    ) : WhisperNativeBridge {
        var loadCount = 0
        var unloadCount = 0

        @Volatile var abortCalled = false

        @Volatile var transcriptionStarted = false

        @Volatile var transcriptionRunning = false

        override fun load(modelPath: String): Long {
            loadCount++
            loadFailure?.let { throw it }
            return 42
        }

        override fun transcribe(
            handle: Long,
            pcm: FloatArray,
            request: NativeTranscriptionRequest,
            cancellationSignal: NativeCancellationSignal,
        ): NativeTranscriptionResult {
            transcribeFailure?.let { throw it }
            transcriptionStarted = true
            transcriptionRunning = true
            try {
                while (blockUntilCancelled && !cancellationSignal.isCancelled()) {
                    Thread.yield()
                }
                if (cancellationSignal.isCancelled()) throw CancellationException()
                onAfterFinalCancellationPoll()
                return NativeTranscriptionResult(
                    listOf(NativeSegment(120, 430, " hello")),
                    "en",
                )
            } finally {
                transcriptionRunning = false
            }
        }

        override fun abort(handle: Long) {
            abortCalled = true
        }

        override fun unload(handle: Long) {
            check(!transcriptionRunning)
            unloadCount++
        }
    }
}
