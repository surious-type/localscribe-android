package io.github.surioustype.localscribe.engine

import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.EngineSegment
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.TranscriptionResult
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import io.github.surioustype.localscribe.core.ports.TranscriptionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class WhisperEngineException(
    val failure: DomainFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class WhisperTranscriptionEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bridge: WhisperNativeBridge = JniWhisperNativeBridge,
    private val maximumPromptCharacters: Int = 1_000,
) : TranscriptionEngine {
    private val lifecycleMutex = Mutex()
    private var loaded: LoadedContext? = null

    override suspend fun loadModel(model: InstalledModel, vadModel: InstalledModel?) {
        lifecycleMutex.withLock {
            val requested = LoadedIdentity(model.sha256, vadModel?.sha256)
            if (loaded?.identity == requested) return
            loaded?.let { bridge.unload(it.handle) }
            loaded = null
            verifyModel(model)
            if (vadModel != null) verifyModel(vadModel)
            val handle = nativeBoundary("model_load_failed") { bridge.load(model.filePath) }
            if (handle == 0L) throw failure(FailureCode.MODEL_CORRUPTED, "model_load_failed")
            loaded = LoadedContext(handle, requested, vadModel?.filePath)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun transcribe(
        pcm: FloatArray,
        config: InferenceConfig,
        prompt: String?,
        cancellationSignal: CancellationSignal,
    ): TranscriptionResult =
        lifecycleMutex.withLock {
            require(config.threadCount > 0) { "threadCount must be positive" }
            val context =
                loaded ?: throw failure(FailureCode.INVALID_STATE_TRANSITION, "model_not_loaded")
            val vad = config.vad
            if (vad != null && context.vadModelPath == null) {
                throw failure(FailureCode.MODEL_CORRUPTED, "vad_model_not_loaded")
            }
            if (vad != null && !vad.modelHash.equals(context.identity.vadHash, ignoreCase = true)) {
                throw failure(FailureCode.MODEL_CHECKSUM_MISMATCH, "vad_model_identity_mismatch")
            }
            coroutineContext.ensureActive()
            if (cancellationSignal.isCancellationRequested()) {
                throw CancellationException(
                    "transcription_cancelled",
                )
            }

            val cancelled = AtomicBoolean(false)
            val job = coroutineContext[Job]
            val completion =
                job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) {
                        cancelled.set(true)
                        bridge.abort(context.handle)
                    }
                }
            try {
                val request =
                    NativeTranscriptionRequest(
                        threadCount = config.threadCount.coerceAtMost(MAXIMUM_THREAD_COUNT),
                        language =
                            config.language?.takeUnless {
                                it.equals(
                                    "auto",
                                    ignoreCase = true,
                                )
                            },
                        translateToEnglish = config.translateToEnglish,
                        temperature = config.temperature,
                        prompt = prompt?.take(maximumPromptCharacters),
                        vadModelPath = if (vad == null) null else context.vadModelPath,
                        vadThreshold = vad?.threshold ?: 0.5f,
                        minimumSpeechDurationMs =
                            vad?.minimumSpeechDurationMs?.toIntExact("minimumSpeechDurationMs")
                                ?: 250,
                        minimumSilenceDurationMs =
                            vad?.minimumSilenceDurationMs?.toIntExact("minimumSilenceDurationMs")
                                ?: 100,
                    )
                val nativeResult =
                    try {
                        withContext(ioDispatcher) {
                            nativeBoundary("native_transcription_failed") {
                                bridge.transcribe(
                                    context.handle,
                                    pcm,
                                    request,
                                    NativeCancellationSignal {
                                        val requestedCancellation =
                                            cancelled.get() ||
                                                cancellationSignal.isCancellationRequested()
                                        if (requestedCancellation) bridge.abort(context.handle)
                                        requestedCancellation
                                    },
                                )
                            }
                        }
                    } catch (error: WhisperEngineException) {
                        if (cancelled.get() || cancellationSignal.isCancellationRequested()) {
                            throw CancellationException(
                                "transcription_cancelled",
                            ).apply { initCause(error) }
                        }
                        throw error
                    }
                coroutineContext.ensureActive()
                if (cancellationSignal.isCancellationRequested()) {
                    throw CancellationException("transcription_cancelled")
                }
                TranscriptionResult(
                    segments =
                        nativeResult.segments.map {
                            EngineSegment(
                                it.startMs,
                                it.endMs,
                                it.text,
                            )
                        },
                    detectedLanguage = nativeResult.detectedLanguage,
                )
            } finally {
                completion?.dispose()
            }
        }

    override suspend fun unloadModel() {
        lifecycleMutex.withLock {
            val context = loaded ?: return
            bridge.abort(context.handle)
            nativeBoundary("model_unload_failed") { bridge.unload(context.handle) }
            loaded = null
        }
    }

    private suspend fun verifyModel(model: InstalledModel) =
        withContext(ioDispatcher) {
            val file = File(model.filePath)
            if (!file.isFile || file.length() != model.bytes) {
                throw failure(FailureCode.MODEL_CORRUPTED, "model_file_invalid")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                FileInputStream(file).use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            } catch (error: OutOfMemoryError) {
                throw failure(FailureCode.INSUFFICIENT_MEMORY, "model_verification_oom", error)
            }
            val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                throw failure(FailureCode.MODEL_CHECKSUM_MISMATCH, "model_checksum_mismatch")
            }
        }

    private fun Long.toIntExact(field: String): Int {
        if (this !in
            0..Int.MAX_VALUE.toLong()
        ) {
            throw IllegalArgumentException("$field is out of range")
        }
        return toInt()
    }

    private inline fun <T> nativeBoundary(diagnostic: String, block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NativeBridgeException) {
            when (error.errorCode) {
                NativeErrorCode.OUT_OF_MEMORY ->
                    throw failure(FailureCode.INSUFFICIENT_MEMORY, "native_out_of_memory", error)
                NativeErrorCode.INVALID_MODEL ->
                    throw failure(FailureCode.MODEL_CORRUPTED, "model_load_failed", error)
                NativeErrorCode.CANCELLED ->
                    throw CancellationException(
                        "transcription_cancelled",
                    ).apply { initCause(error) }
                else -> throw failure(FailureCode.NATIVE_FAILURE, diagnostic, error)
            }
        } catch (oom: OutOfMemoryError) {
            throw failure(FailureCode.INSUFFICIENT_MEMORY, "native_out_of_memory", oom)
        } catch (error: WhisperEngineException) {
            throw error
        } catch (error: Throwable) {
            throw failure(FailureCode.NATIVE_FAILURE, diagnostic, error)
        }

    private fun failure(code: FailureCode, diagnostic: String, cause: Throwable? = null) =
        WhisperEngineException(DomainFailure(code, diagnostic), diagnostic, cause)

    private data class LoadedIdentity(val modelHash: String, val vadHash: String?)

    private data class LoadedContext(
        val handle: Long,
        val identity: LoadedIdentity,
        val vadModelPath: String?,
    )

    private companion object {
        const val MAXIMUM_THREAD_COUNT = 16
    }
}
