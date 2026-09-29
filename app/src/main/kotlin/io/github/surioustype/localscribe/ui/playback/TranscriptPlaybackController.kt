package io.github.surioustype.localscribe.ui.playback

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaybackState(
    val sourceUri: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Small abstraction around platform playback so controller behavior remains JVM-testable.
 */
interface AudioPlayerBackend : AutoCloseable {
    suspend fun prepare(sourceUri: String)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun currentPositionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean

    fun setOnCompletionListener(listener: () -> Unit)

    override fun close()
}

/**
 * Owns exactly one player for a transcript screen. It is intended to be retained with Compose
 * [androidx.compose.runtime.remember] and closed only when that screen leaves composition.
 */
class TranscriptPlaybackController(
    private val scope: CoroutineScope,
    private val backendFactory: () -> AudioPlayerBackend,
    private val pollIntervalMs: Long = POSITION_POLL_INTERVAL_MS,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(PlaybackState())

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var backend: AudioPlayerBackend? = null
    private var preparationJob: Job? = null
    private var pollingJob: Job? = null
    private var sourceUri: String? = null
    private var requestedPositionMs = 0L
    private var shouldPlayWhenReady = false
    private var generation = 0L
    private var closed = false

    fun play(sourceUri: String) {
        if (closed) return
        shouldPlayWhenReady = true
        ensurePrepared(sourceUri)
        backend?.takeIf { mutableState.value.isReady }?.let { start(it) }
    }

    fun pause() {
        if (closed) return
        shouldPlayWhenReady = false
        backend?.takeIf { mutableState.value.isReady }?.let {
            runCatching { it.pause() }
            stopPolling()
            mutableState.value = mutableState.value.copy(isPlaying = false)
        }
    }

    /** Prepares the source when necessary, allowing transcript segments to seek before Play. */
    fun seekTo(sourceUri: String, positionMs: Long) {
        if (closed) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        mutableState.value =
            mutableState.value.copy(positionMs = requestedPositionMs, errorMessage = null)
        ensurePrepared(sourceUri)
        backend?.takeIf { mutableState.value.isReady }?.let { seek(it, requestedPositionMs) }
    }

    /** Releases player and coroutine resources when the screen stops; a later play/seek recreates them. */
    fun onStop() {
        if (closed) return
        val position = mutableState.value.positionMs
        requestedPositionMs = position
        shouldPlayWhenReady = false
        releaseCurrentBackend()
        mutableState.value = PlaybackState(sourceUri = sourceUri, positionMs = position)
    }

    override fun close() {
        if (closed) return
        closed = true
        generation += 1
        preparationJob?.cancel()
        preparationJob = null
        stopPolling()
        backend?.closeSafely()
        backend = null
        mutableState.value =
            mutableState.value.copy(isPreparing = false, isReady = false, isPlaying = false)
    }

    private fun ensurePrepared(nextSourceUri: String) {
        if (nextSourceUri.isBlank()) {
            shouldPlayWhenReady = false
            mutableState.value = mutableState.value.copy(errorMessage = PREPARATION_ERROR)
            return
        }
        if (sourceUri == nextSourceUri && backend != null) return

        releaseCurrentBackend()
        sourceUri = nextSourceUri
        requestedPositionMs = requestedPositionMs.coerceAtLeast(0L)
        val token = generation
        val createdBackend = backendFactory()
        backend = createdBackend
        mutableState.value =
            PlaybackState(
                sourceUri = nextSourceUri,
                positionMs = requestedPositionMs,
                isPreparing = true,
            )
        preparationJob =
            scope.launch {
                try {
                    createdBackend.prepare(nextSourceUri)
                    if (closed || token != generation || backend !== createdBackend) {
                        createdBackend.closeSafely()
                        return@launch
                    }
                    createdBackend.setOnCompletionListener { onCompletion(token, createdBackend) }
                    val durationMs =
                        runCatching { createdBackend.durationMs() }
                            .getOrDefault(
                                0L,
                            ).coerceAtLeast(0L)
                    mutableState.value =
                        mutableState.value.copy(
                            durationMs = durationMs,
                            isPreparing = false,
                            isReady = true,
                        )
                    seek(createdBackend, requestedPositionMs)
                    if (shouldPlayWhenReady) start(createdBackend)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    if (!closed && token == generation && backend === createdBackend) {
                        shouldPlayWhenReady = false
                        createdBackend.closeSafely()
                        backend = null
                        mutableState.value =
                            mutableState.value.copy(
                                isPreparing = false,
                                isReady = false,
                                isPlaying = false,
                                errorMessage = PREPARATION_ERROR,
                            )
                    }
                }
            }
    }

    private fun start(activeBackend: AudioPlayerBackend) {
        runCatching { activeBackend.play() }
            .onFailure {
                shouldPlayWhenReady = false
                mutableState.value =
                    mutableState.value.copy(isPlaying = false, errorMessage = PLAYBACK_ERROR)
                return
            }
        mutableState.value =
            mutableState.value.copy(isPlaying = activeBackend.isPlaying(), errorMessage = null)
        if (mutableState.value.isPlaying) startPolling(activeBackend, generation)
    }

    private fun seek(activeBackend: AudioPlayerBackend, positionMs: Long) {
        runCatching { activeBackend.seekTo(positionMs) }
            .onFailure {
                mutableState.value = mutableState.value.copy(errorMessage = PLAYBACK_ERROR)
                return
            }
        mutableState.value = mutableState.value.copy(positionMs = positionMs)
    }

    private fun startPolling(activeBackend: AudioPlayerBackend, token: Long) {
        if (pollingJob?.isActive == true) return
        pollingJob =
            scope.launch {
                while (!closed &&
                    token == generation &&
                    backend === activeBackend &&
                    activeBackend.isPlaying()
                ) {
                    val positionMs =
                        runCatching { activeBackend.currentPositionMs() }
                            .getOrDefault(mutableState.value.positionMs)
                    mutableState.value =
                        mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0L))
                    delay(pollIntervalMs)
                }
                if (!closed && token == generation && backend === activeBackend) {
                    mutableState.value = mutableState.value.copy(isPlaying = false)
                }
            }
    }

    private fun onCompletion(token: Long, completedBackend: AudioPlayerBackend) {
        if (closed || token != generation || backend !== completedBackend) return
        shouldPlayWhenReady = false
        stopPolling()
        mutableState.value =
            mutableState.value.copy(
                positionMs = mutableState.value.durationMs,
                isPlaying = false,
            )
    }

    private fun releaseCurrentBackend() {
        generation += 1
        preparationJob?.cancel()
        preparationJob = null
        stopPolling()
        backend?.closeSafely()
        backend = null
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun AudioPlayerBackend.closeSafely() {
        runCatching { close() }
    }

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 200L
        const val PREPARATION_ERROR = "Audio could not be prepared."
        const val PLAYBACK_ERROR = "Audio playback could not continue."
    }
}

/** Android [MediaPlayer] implementation. Its data-source and asynchronous preparation run on IO. */
class AndroidMediaPlayerBackend(
    private val context: Context,
) : AudioPlayerBackend {
    private val mediaPlayer = MediaPlayer()
    private var closed = false

    override suspend fun prepare(sourceUri: String) =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                mediaPlayer.setOnPreparedListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Media preparation failed"),
                        )
                    }
                    true
                }
                try {
                    mediaPlayer.setDataSource(context, Uri.parse(sourceUri))
                    mediaPlayer.prepareAsync()
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    override fun play() {
        if (!closed) mediaPlayer.start()
    }

    override fun pause() {
        if (!closed && mediaPlayer.isPlaying) mediaPlayer.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (!closed) mediaPlayer.seekTo(positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    override fun currentPositionMs(): Long =
        if (closed) {
            0L
        } else {
            mediaPlayer.currentPosition
                .toLong()
        }

    override fun durationMs(): Long = if (closed) 0L else mediaPlayer.duration.toLong()

    override fun isPlaying(): Boolean = !closed && mediaPlayer.isPlaying

    override fun setOnCompletionListener(listener: () -> Unit) {
        if (!closed) mediaPlayer.setOnCompletionListener { listener() }
    }

    override fun close() {
        if (closed) return
        closed = true
        mediaPlayer.release()
    }
}
