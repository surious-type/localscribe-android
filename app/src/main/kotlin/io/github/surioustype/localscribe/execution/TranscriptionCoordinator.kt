package io.github.surioustype.localscribe.execution

import android.database.sqlite.SQLiteFullException
import io.github.surioustype.localscribe.audio.AudioSourceException
import io.github.surioustype.localscribe.core.domain.TranscriptContext
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk
import io.github.surioustype.localscribe.core.ports.AudioPipeline
import io.github.surioustype.localscribe.core.ports.AudioRepository
import io.github.surioustype.localscribe.core.ports.CancellationSignal
import io.github.surioustype.localscribe.core.ports.InstalledModelRepository
import io.github.surioustype.localscribe.core.ports.TranscriptionEngine
import io.github.surioustype.localscribe.core.ports.TranscriptionRepository
import io.github.surioustype.localscribe.engine.WhisperEngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicReference

interface DurableTranscriptionRepository : TranscriptionRepository {
    suspend fun pauseJob(jobId: String, pausedAtEpochMs: Long, reason: DomainFailure?)
}

fun interface ExecutionClock {
    fun nowEpochMs(): Long
}

enum class ThermalSeverity {
    NONE,
    MODERATE,
    SEVERE,
    CRITICAL,
}

data class ThermalSnapshot(val severity: ThermalSeverity) {
    fun threadCount(configured: Int): Int =
        when (severity) {
            ThermalSeverity.NONE -> configured
            ThermalSeverity.MODERATE -> (configured / 2).coerceAtLeast(1)
            ThermalSeverity.SEVERE,
            ThermalSeverity.CRITICAL,
            -> 1
        }
}

fun interface ThermalState {
    fun snapshot(): ThermalSnapshot

    companion object {
        val None = ThermalState { ThermalSnapshot(ThermalSeverity.NONE) }
    }
}

sealed interface ExecutionOutcome {
    data object Completed : ExecutionOutcome

    data object Paused : ExecutionOutcome

    data object Cancelled : ExecutionOutcome

    data class Failed(val failure: DomainFailure) : ExecutionOutcome
}

class TranscriptionCoordinator(
    private val repository: DurableTranscriptionRepository,
    private val audioRepository: AudioRepository,
    private val installedModels: InstalledModelRepository,
    private val audioPipeline: AudioPipeline,
    private val engine: TranscriptionEngine,
    private val clock: ExecutionClock = ExecutionClock { System.currentTimeMillis() },
    private val thermalState: ThermalState = ThermalState.None,
    private val transcriptContext: TranscriptContext = TranscriptContext(),
    private val executionMutex: Mutex,
) {
    private val controlMutex = Mutex()
    private val activeExecutions = mutableListOf<ActiveExecution>()

    suspend fun execute(jobId: String): ExecutionOutcome {
        val control = ActiveExecution(jobId)
        controlMutex.withLock { activeExecutions += control }
        try {
            return executionMutex.withLock {
                var claimed: TranscriptionChunk? = null
                var loaded = false
                try {
                    outcomeFor(control.request.get())?.let { return@withLock it }
                    val job =
                        repository.getJob(jobId)
                            ?: return@withLock ExecutionOutcome.Failed(
                                DomainFailure(FailureCode.UNKNOWN, "job_missing"),
                            )
                    if (job.status ==
                        JobStatus.CANCELLED
                    ) {
                        return@withLock ExecutionOutcome.Cancelled
                    }
                    if (job.status ==
                        JobStatus.COMPLETED
                    ) {
                        return@withLock ExecutionOutcome.Completed
                    }
                    if (job.status != JobStatus.PENDING && job.status != JobStatus.PAUSED) {
                        return@withLock ExecutionOutcome.Failed(
                            DomainFailure(
                                FailureCode.INVALID_STATE_TRANSITION,
                                "job_not_startable",
                            ),
                        )
                    }
                    val startOutcome =
                        controlMutex.withLock {
                            outcomeFor(control.request.get())
                                ?: when (repository.getJob(jobId)?.status) {
                                    JobStatus.PENDING,
                                    JobStatus.PAUSED,
                                    -> {
                                        repository.resumeJob(jobId, clock.nowEpochMs())
                                        null
                                    }
                                    JobStatus.CANCELLED -> ExecutionOutcome.Cancelled
                                    JobStatus.COMPLETED -> ExecutionOutcome.Completed
                                    else ->
                                        ExecutionOutcome.Failed(
                                            DomainFailure(
                                                FailureCode.INVALID_STATE_TRANSITION,
                                                "job_not_startable",
                                            ),
                                        )
                                }
                        }
                    if (startOutcome != null) return@withLock startOutcome

                    val sourceRecord =
                        audioRepository.verifySource(job.sourceId)
                            ?: return@withLock pauseForDependency(
                                jobId,
                                DomainFailure(FailureCode.SOURCE_MISSING, "source_missing"),
                            )
                    if (
                        sourceRecord.accessStatus !=
                        io.github.surioustype.localscribe.core.model.SourceAccessStatus.AVAILABLE
                    ) {
                        val code =
                            if (
                                sourceRecord.accessStatus ==
                                SourceAccessStatus.PERMISSION_REQUIRED
                            ) {
                                FailureCode.SOURCE_PERMISSION_REQUIRED
                            } else {
                                FailureCode.SOURCE_MISSING
                            }
                        return@withLock pauseForDependency(
                            jobId,
                            DomainFailure(code, "source_unavailable"),
                        )
                    }
                    val currentFingerprint = sourceRecord.contentFingerprint
                    if (currentFingerprint == null) {
                        return@withLock pauseForDependency(
                            jobId,
                            DomainFailure(
                                FailureCode.SOURCE_MISSING,
                                "source_fingerprint_unavailable",
                            ),
                        )
                    }
                    if (job.sourceFingerprint != currentFingerprint) {
                        repository.restartForSourceFingerprint(
                            jobId,
                            currentFingerprint,
                            clock.nowEpochMs(),
                        )
                    }
                    val model =
                        installedModels.getInstalledModel(job.config.modelId, job.modelHash)
                            ?: return@withLock pauseForDependency(
                                jobId,
                                DomainFailure(FailureCode.MODEL_CORRUPTED, "model_missing"),
                            )
                    val vad =
                        job.config.inference.vad?.let { config ->
                            installedModels.getInstalledModel(config.modelId, config.modelHash)
                                ?: return@withLock pauseForDependency(
                                    jobId,
                                    DomainFailure(
                                        FailureCode.MODEL_CORRUPTED,
                                        "vad_model_missing_or_changed",
                                    ),
                                )
                        }
                    engine.loadModel(model, vad)
                    loaded = true

                    while (true) {
                        outcomeFor(control.request.get())?.let { return@withLock it }
                        val thermal = thermalState.snapshot()
                        if (
                            thermal.severity == ThermalSeverity.SEVERE ||
                            thermal.severity == ThermalSeverity.CRITICAL
                        ) {
                            repository.pauseJob(
                                jobId,
                                clock.nowEpochMs(),
                                DomainFailure(FailureCode.THERMAL_CRITICAL, "device_too_hot"),
                            )
                            return@withLock ExecutionOutcome.Paused
                        }
                        claimed = repository.claimNextChunk(jobId, clock.nowEpochMs()) ?: break
                        val chunk = claimed
                        val previous =
                            repository
                                .observeSegments(jobId)
                                .first()
                                .filter {
                                    it.chunkId != chunk.id &&
                                        it.absoluteEndMs <= chunk.startMs
                                }
                        val prompt =
                            transcriptContext.build(
                                previous,
                                job.config.contextMaxCharacters,
                            )
                        val pcm =
                            audioPipeline.readWindow(
                                sourceRecord.source,
                                chunk.startMs,
                                chunk.endMs,
                            )
                        val config =
                            job.config.inference.withThreads(
                                thermal.threadCount(job.config.inference.threadCount),
                            )
                        val result =
                            engine.transcribe(
                                pcm,
                                config,
                                prompt,
                                CancellationSignal { control.request.get() != ControlRequest.NONE },
                            )
                        outcomeFor(control.request.get())?.let { return@withLock it }
                        val segments =
                            result.segments.mapIndexed { index, segment ->
                                val duration = chunk.endMs - chunk.startMs
                                TranscriptSegment(
                                    id = "${chunk.id}:$index",
                                    chunkId = chunk.id,
                                    absoluteStartMs =
                                        chunk.startMs + segment.startMs.coerceIn(0, duration),
                                    absoluteEndMs =
                                        chunk.startMs + segment.endMs.coerceIn(0, duration),
                                    text = segment.text,
                                )
                            }
                        repository.completeChunk(chunk.id, segments, clock.nowEpochMs())
                        claimed = null
                    }
                    repository.completeJob(jobId, clock.nowEpochMs())
                    ExecutionOutcome.Completed
                } catch (cancelled: CancellationException) {
                    outcomeFor(control.request.get()) ?: run {
                        withContext(NonCancellable) { pauseOwnedExecution(control) }
                        throw cancelled
                    }
                } catch (error: Throwable) {
                    outcomeFor(control.request.get()) ?: run {
                        val failure = error.toDomainFailure()
                        if (failure.code in dependencyFailureCodes) {
                            pauseForDependency(jobId, failure)
                        } else {
                            claimed?.let {
                                runCatching {
                                    repository.failChunk(
                                        it.id,
                                        failure,
                                        clock.nowEpochMs(),
                                    )
                                }
                            }
                            failJob(jobId, failure)
                        }
                    }
                } finally {
                    if (loaded) withContext(NonCancellable) { engine.unloadModel() }
                }
            }
        } finally {
            withContext(NonCancellable) {
                controlMutex.withLock { activeExecutions.removeAll { it === control } }
            }
        }
    }

    suspend fun pause(
        jobId: String,
        atEpochMs: Long = clock.nowEpochMs(),
        reason: DomainFailure? = null,
    ) {
        controlMutex.withLock {
            val job = repository.getJob(jobId) ?: return
            val active = activeExecutions.filter { it.jobId == jobId }
            if (job.status == JobStatus.PENDING) {
                active.forEach { it.request.set(ControlRequest.PAUSE) }
                return
            }
            if (
                job.status == JobStatus.CANCELLED ||
                job.status == JobStatus.FAILED ||
                job.status == JobStatus.COMPLETED
            ) {
                return
            }
            if (job.status == JobStatus.RUNNING) {
                repository.pauseJob(jobId, atEpochMs, reason)
            }
            active.forEach { it.request.set(ControlRequest.PAUSE) }
        }
    }

    suspend fun cancel(jobId: String, atEpochMs: Long = clock.nowEpochMs()) {
        controlMutex.withLock {
            val job = repository.getJob(jobId) ?: return
            if (job.status in terminalJobStatuses) return
            repository.cancelJob(jobId, atEpochMs)
            activeExecutions
                .filter { it.jobId == jobId }
                .forEach { it.request.set(ControlRequest.CANCEL) }
        }
    }

    private suspend fun failJob(jobId: String, failure: DomainFailure): ExecutionOutcome.Failed {
        repository.failJob(jobId, failure, clock.nowEpochMs())
        return ExecutionOutcome.Failed(failure)
    }

    private suspend fun pauseForDependency(
        jobId: String,
        failure: DomainFailure,
    ): ExecutionOutcome {
        repository.pauseJob(jobId, clock.nowEpochMs(), failure)
        return ExecutionOutcome.Paused
    }

    private suspend fun pauseOwnedExecution(control: ActiveExecution) {
        controlMutex.withLock {
            if (activeExecutions.none { it === control }) return
            val job = repository.getJob(control.jobId) ?: return
            if (job.status == JobStatus.RUNNING) {
                repository.pauseJob(
                    control.jobId,
                    clock.nowEpochMs(),
                    DomainFailure(FailureCode.TIMEOUT, "execution_interrupted"),
                )
            }
            control.request.set(ControlRequest.PAUSE)
        }
    }

    private fun outcomeFor(request: ControlRequest): ExecutionOutcome? =
        when (request) {
            ControlRequest.NONE -> null
            ControlRequest.PAUSE -> ExecutionOutcome.Paused
            ControlRequest.CANCEL -> ExecutionOutcome.Cancelled
        }

    private fun InferenceConfig.withThreads(threads: Int) = copy(threadCount = threads)

    private data class ActiveExecution(
        val jobId: String,
        val request: AtomicReference<ControlRequest> = AtomicReference(ControlRequest.NONE),
    )

    private enum class ControlRequest {
        NONE,
        PAUSE,
        CANCEL,
    }

    private companion object {
        val terminalJobStatuses = setOf(JobStatus.CANCELLED, JobStatus.FAILED, JobStatus.COMPLETED)
        val dependencyFailureCodes =
            setOf(
                FailureCode.SOURCE_PERMISSION_REQUIRED,
                FailureCode.SOURCE_MISSING,
                FailureCode.MODEL_CORRUPTED,
                FailureCode.MODEL_CHECKSUM_MISMATCH,
            )
    }
}

private fun Throwable.toDomainFailure(): DomainFailure =
    when (this) {
        is AudioSourceException -> failure
        is WhisperEngineException -> failure
        is SQLiteFullException -> DomainFailure(FailureCode.STORAGE_FULL, "database_full")
        is SecurityException ->
            DomainFailure(
                FailureCode.SOURCE_PERMISSION_REQUIRED,
                "source_access_denied",
            )
        is FileNotFoundException -> DomainFailure(FailureCode.SOURCE_MISSING, "source_missing")
        is OutOfMemoryError -> DomainFailure(FailureCode.INSUFFICIENT_MEMORY, "out_of_memory")
        is IllegalArgumentException ->
            DomainFailure(
                FailureCode.UNSUPPORTED_CODEC,
                "invalid_audio_or_configuration",
            )
        else -> DomainFailure(FailureCode.UNKNOWN, "transcription_failed")
    }
