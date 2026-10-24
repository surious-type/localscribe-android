package io.github.surioustype.localscribe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.surioustype.localscribe.R
import io.github.surioustype.localscribe.core.model.ChunkStatus
import io.github.surioustype.localscribe.core.model.DomainFailure
import io.github.surioustype.localscribe.core.model.FailureCode
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.ports.AudioRepository
import io.github.surioustype.localscribe.execution.AndroidThermalMonitor
import io.github.surioustype.localscribe.execution.DurableTranscriptionRepository
import io.github.surioustype.localscribe.execution.ThermalSeverity
import io.github.surioustype.localscribe.execution.TranscriptionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ServiceDependencies {
    val coordinator: TranscriptionCoordinator
    val queuePriorities: DurableQueuePriorities
    val repository: DurableTranscriptionRepository
    val audioRepository: AudioRepository
    val thermalMonitor: AndroidThermalMonitor

    suspend fun awaitStartupRecovery()
}

interface ServiceDependenciesProvider {
    val transcriptionServiceDependencies: ServiceDependencies
}

class TranscriptionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actionMutex = Mutex()
    private val controlRouting = TranscriptionServiceControlRouting()
    private lateinit var dependencies: ServiceDependencies
    private lateinit var executionQueue: SerialExecutionQueue
    private var notificationJob: Job? = null
    private var thermalJob: Job? = null
    private val manualResumeIds = mutableSetOf<String>()
    private var latestStartId = 0
    private var executionWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        dependencies =
            (application as? ServiceDependenciesProvider)?.transcriptionServiceDependencies
                ?: error("Application must implement ServiceDependenciesProvider")
        executionQueue =
            SerialExecutionQueue(
                scope = serviceScope,
                execute = { jobId ->
                    val status = dependencies.repository.getJob(jobId)?.status
                    val explicitlyResumed = manualResumeIds.remove(jobId)
                    val canResume =
                        (status == JobStatus.PAUSED || status == JobStatus.FAILED) &&
                            explicitlyResumed
                    if (status == JobStatus.PENDING || canResume) {
                        dependencies.coordinator.execute(jobId)
                    }
                },
                onStarted = { jobId ->
                    controlRouting.executionStarted(jobId)
                    startForegroundCompat(jobId, 0, 0)
                    acquireExecutionWakeLock()
                    observeProgress(jobId)
                },
                onIdle = { _ ->
                    controlRouting.executionIdle()
                    releaseExecutionWakeLock()
                    stopForegroundAndSelf(latestStartId)
                },
            )
        createNotificationChannel()
        dependencies.thermalMonitor.start()
        thermalJob =
            serviceScope.launch {
                dependencies.thermalMonitor.observe().collectLatest { thermal ->
                    if (thermal.severity == ThermalSeverity.SEVERE ||
                        thermal.severity == ThermalSeverity.CRITICAL
                    ) {
                        controlRouting.activeJobId?.let {
                            dependencies.coordinator.pause(
                                it,
                                reason =
                                    DomainFailure(
                                        FailureCode.THERMAL_CRITICAL,
                                        "device_too_hot",
                                    ),
                            )
                        }
                    }
                }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val jobId = intent.getStringExtra(EXTRA_JOB_ID)
        latestStartId = startId
        if (action in setOf(ACTION_START, ACTION_RESUME, ACTION_REORDER)) {
            startForegroundCompat(
                controlRouting.notificationJobIdFor(jobId ?: "queue"),
                0,
                0,
            )
            serviceScope.launch {
                actionMutex.withLock {
                    ServiceStartRecoveryGate(
                        recover = dependencies::awaitStartupRecovery,
                        queue = { _, queuedStartId ->
                            if (action == ACTION_RESUME && jobId != null) {
                                manualResumeIds += jobId
                            }
                            rebuildQueue(queuedStartId)
                        },
                        onFailure = { stopForegroundAndSelf(startId) },
                    ).start(jobId ?: "queue", startId)
                }
            }
            return START_NOT_STICKY
        }
        if (jobId == null) return START_NOT_STICKY
        serviceScope.launch {
            actionMutex.withLock {
                when (action) {
                    ACTION_PAUSE -> pause(jobId, startId)
                    ACTION_STOP -> cancel(jobId, startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Rebuild pending execution from Room. Paused jobs are resumed only on user request. */
    private suspend fun rebuildQueue(startId: Int) {
        val jobs = dependencies.repository.observeJobs().first()
        val resumable =
            jobs
                .filter {
                    it.status == JobStatus.PAUSED || it.status == JobStatus.FAILED
                }.map { it.id }
                .toSet()
        manualResumeIds.retainAll(resumable)
        val eligible =
            jobs.filter { it.status == JobStatus.PENDING || it.id in manualResumeIds }
        val ready =
            eligible.map { candidate ->
                if (candidate.id in manualResumeIds) {
                    candidate.copy(status = JobStatus.PENDING)
                } else {
                    candidate
                }
            }
        val ordered =
            pendingTranscriptionQueue(
                ready,
                dependencies.queuePriorities.priorities.value,
            ).map { it.id }
        executionQueue.schedule(ordered, startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        val jobId = controlRouting.activeJobId
        if (jobId == null) {
            stopForegroundAndSelf(startId)
            return
        }
        serviceScope.launch {
            actionMutex.withLock {
                dependencies.coordinator.pause(
                    jobId,
                    reason = DomainFailure(FailureCode.TIMEOUT, "foreground_service_timeout"),
                )
                stopForegroundAndSelf(startId)
            }
        }
    }

    override fun onDestroy() {
        releaseExecutionWakeLock()
        executionQueue.cancel()
        notificationJob?.cancel()
        thermalJob?.cancel()
        dependencies.thermalMonitor.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun pause(jobId: String, startId: Int) {
        dependencies.coordinator.pause(jobId)
        manualResumeIds.remove(jobId)
        executionQueue.remove(jobId)
        rebuildQueue(startId)
    }

    private suspend fun cancel(jobId: String, startId: Int) {
        dependencies.coordinator.cancel(jobId)
        manualResumeIds.remove(jobId)
        executionQueue.remove(jobId)
        rebuildQueue(startId)
    }

    private fun observeProgress(jobId: String) {
        notificationJob?.cancel()
        notificationJob =
            serviceScope.launch {
                dependencies.repository.observeChunks(jobId).collectLatest { chunks ->
                    val totalMs = chunks.maxOfOrNull { it.endMs } ?: 0
                    val completedMs =
                        chunks
                            .filter { it.status == ChunkStatus.COMPLETED }
                            .maxOfOrNull { it.endMs } ?: 0
                    updateNotification(jobId, completedMs, totalMs)
                }
            }
    }

    private suspend fun updateNotification(jobId: String, completedMs: Long, totalMs: Long) {
        // Notifications must remain available when the recording is revoked,
        // deleted, or temporarily inaccessible. Source verification belongs in
        // the coordinator, not in this presentation-only update.
        val sourceName =
            try {
                dependencies.repository.getJob(jobId)?.let { job ->
                    dependencies.audioRepository
                        .getSource(job.sourceId)
                        ?.source
                        ?.displayName
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: getString(R.string.transcription_notification_title)
        val notification = notification(jobId, sourceName, completedMs, totalMs)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(jobId: String, completedMs: Long, totalMs: Long) {
        val notification =
            notification(
                jobId,
                getString(R.string.transcription_notification_title),
                completedMs,
                totalMs,
            )
        if (Build.VERSION.SDK_INT >= 35) {
            // ServiceCompat 1.19 masks out the API 35 media-processing bit.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType(),
            )
        }
    }

    /** Keep CPU execution available while the user-requested, foreground-notified job runs.
     * Android may still enforce media-processing quotas or OEM process restrictions.
     * The lock is process-owned and always released on idle or service teardown.
     */
    private fun acquireExecutionWakeLock() {
        val lock = executionWakeLock ?: run {
            val manager = getSystemService(POWER_SERVICE) as PowerManager
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transcription")
                .apply { setReferenceCounted(false) }
                .also { executionWakeLock = it }
        }
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseExecutionWakeLock() {
        executionWakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun foregroundServiceType(): Int =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }

    private fun notification(
        jobId: String,
        title: String,
        completedMs: Long,
        totalMs: Long,
    ): Notification {
        val progress = if (totalMs > 0) ((completedMs * 100) / totalMs).toInt() else 0
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(formatProgress(completedMs, totalMs))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, progress, totalMs <= 0)
                .addAction(
                    0,
                    getString(R.string.action_pause),
                    actionIntent(ACTION_PAUSE, jobId, 1),
                ).addAction(
                    0,
                    getString(R.string.action_stop),
                    actionIntent(ACTION_STOP, jobId, 3),
                )
        return builder.build()
    }

    private fun actionIntent(action: String, jobId: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TranscriptionService::class.java)
                .setAction(action)
                .putExtra(EXTRA_JOB_ID, jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun formatProgress(completedMs: Long, totalMs: Long): String =
        "${completedMs.toClock()} / ${totalMs.toClock()}"

    private fun Long.toClock(): String {
        val seconds = this.coerceAtLeast(0) / 1_000
        return "%d:%02d:%02d".format(seconds / 3_600, (seconds / 60) % 60, seconds % 60)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.transcription_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun stopForegroundAndSelf(startId: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    companion object {
        const val ACTION_START = "io.github.surioustype.localscribe.action.START_TRANSCRIPTION"
        const val ACTION_RESUME = "io.github.surioustype.localscribe.action.RESUME_TRANSCRIPTION"
        const val ACTION_PAUSE = "io.github.surioustype.localscribe.action.PAUSE_TRANSCRIPTION"
        const val ACTION_STOP = "io.github.surioustype.localscribe.action.STOP_TRANSCRIPTION"
        const val ACTION_REORDER = "io.github.surioustype.localscribe.action.REORDER_QUEUE"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 1001
    }
}
