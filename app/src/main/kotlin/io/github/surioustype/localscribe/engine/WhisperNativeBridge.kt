package io.github.surioustype.localscribe.engine

object NativeErrorCode {
    const val OUT_OF_MEMORY = 1
    const val INVALID_MODEL = 2
    const val CANCELLED = 3
    const val FAILURE = 4
}

class NativeBridgeException(val errorCode: Int) : RuntimeException()

data class NativeSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class NativeTranscriptionResult(
    val segments: List<NativeSegment>,
    val detectedLanguage: String?,
)

data class NativeTranscriptionRequest(
    val threadCount: Int,
    val language: String?,
    val translateToEnglish: Boolean,
    val temperature: Float,
    val prompt: String?,
    val vadModelPath: String?,
    val vadThreshold: Float,
    val minimumSpeechDurationMs: Int,
    val minimumSilenceDurationMs: Int,
)

fun interface NativeCancellationSignal {
    fun isCancelled(): Boolean
}

/** JNI may call this from a native worker thread; implementations must be thread-safe. */
fun interface NativeSegmentListener {
    fun onSegment(startMs: Long, endMs: Long, text: String)
}

interface WhisperNativeBridge {
    fun load(modelPath: String): Long

    fun transcribe(
        handle: Long,
        pcm: FloatArray,
        request: NativeTranscriptionRequest,
        cancellationSignal: NativeCancellationSignal,
    ): NativeTranscriptionResult

    fun transcribeStreaming(
        handle: Long,
        pcm: FloatArray,
        request: NativeTranscriptionRequest,
        cancellationSignal: NativeCancellationSignal,
        listener: NativeSegmentListener,
    ): NativeTranscriptionResult =
        transcribe(handle, pcm, request, cancellationSignal).also { result ->
            result.segments.forEach { listener.onSegment(it.startMs, it.endMs, it.text) }
        }

    fun abort(handle: Long)

    fun unload(handle: Long)
}

object JniWhisperNativeBridge : WhisperNativeBridge {
    init {
        System.loadLibrary("localscribe_whisper")
    }

    external override fun load(modelPath: String): Long

    external override fun transcribe(
        handle: Long,
        pcm: FloatArray,
        request: NativeTranscriptionRequest,
        cancellationSignal: NativeCancellationSignal,
    ): NativeTranscriptionResult

    external override fun transcribeStreaming(
        handle: Long,
        pcm: FloatArray,
        request: NativeTranscriptionRequest,
        cancellationSignal: NativeCancellationSignal,
        listener: NativeSegmentListener,
    ): NativeTranscriptionResult

    external override fun abort(handle: Long)

    external override fun unload(handle: Long)
}
