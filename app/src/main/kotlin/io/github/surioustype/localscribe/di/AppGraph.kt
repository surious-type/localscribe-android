package io.github.surioustype.localscribe.di

import android.content.Context
import io.github.surioustype.localscribe.audio.AndroidAudioPipeline
import io.github.surioustype.localscribe.audio.AndroidAudioRepository
import io.github.surioustype.localscribe.benchmark.AndroidAssetReader
import io.github.surioustype.localscribe.benchmark.AndroidBenchmarkManager
import io.github.surioustype.localscribe.benchmark.AndroidBenchmarkSampler
import io.github.surioustype.localscribe.benchmark.AssetDemoAudioRepository
import io.github.surioustype.localscribe.benchmark.BenchmarkEngineFactory
import io.github.surioustype.localscribe.data.LocalScribeDatabase
import io.github.surioustype.localscribe.data.RoomAudioSourceStore
import io.github.surioustype.localscribe.data.RoomBenchmarkStore
import io.github.surioustype.localscribe.data.RoomInstalledModelRepository
import io.github.surioustype.localscribe.data.RoomTranscriptionRepository
import io.github.surioustype.localscribe.engine.WhisperTranscriptionEngine
import io.github.surioustype.localscribe.execution.AndroidThermalMonitor
import io.github.surioustype.localscribe.execution.RecoveryInitializer
import io.github.surioustype.localscribe.execution.TranscriptionCoordinator
import io.github.surioustype.localscribe.models.InstalledModelFileStore
import io.github.surioustype.localscribe.models.JsonModelCatalogRepository
import io.github.surioustype.localscribe.models.SecureModelDownloadManager
import io.github.surioustype.localscribe.network.HttpsNetworkClient
import io.github.surioustype.localscribe.service.ServiceDependencies
import io.github.surioustype.localscribe.updates.createAppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Process-owned adapters. The same mutex protects engine sessions, benchmarks and file deletion. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val executionMutex = Mutex()
    private val recoveryMutex = Mutex()
    private var recovered = false
    val database = LocalScribeDatabase.open(appContext)
    val transcriptionRepository = RoomTranscriptionRepository(database)
    private val sourceStore = RoomAudioSourceStore(database)
    val installedModels = RoomInstalledModelRepository(database)
    private val benchmarkStore = RoomBenchmarkStore(database)
    val audioRepository = AndroidAudioRepository(appContext, sourceStore)
    private val audioPipeline = AndroidAudioPipeline(appContext)
    val thermalMonitor = AndroidThermalMonitor(appContext)
    val catalog =
        JsonModelCatalogRepository(
            bundledCatalog = {
                appContext.assets
                    .open("models/catalog.json")
                    .bufferedReader()
                    .use { it.readText() }
            },
            networkClient = HttpsNetworkClient(),
        )
    private val modelDirectory = File(appContext.filesDir, "models")
    val downloads =
        SecureModelDownloadManager(
            catalog = catalog,
            installedModels = installedModels,
            modelDirectory = modelDirectory,
            networkClient = HttpsNetworkClient(),
            scope = applicationScope,
            nowEpochMs = System::currentTimeMillis,
            modelMutex = executionMutex,
        )
    val coordinator =
        TranscriptionCoordinator(
            repository = transcriptionRepository,
            audioRepository = audioRepository,
            installedModels = installedModels,
            audioPipeline = audioPipeline,
            engine = WhisperTranscriptionEngine(),
            thermalState = thermalMonitor,
            executionMutex = executionMutex,
        )
    val modelFiles =
        InstalledModelFileStore(
            modelDirectory = modelDirectory,
            repository = installedModels,
            modelMutex = executionMutex,
            isModelActive = ::isReferencedByResumableJob,
        )
    val demoAudio = AssetDemoAudioRepository(AndroidAssetReader(appContext.assets))
    val benchmark =
        AndroidBenchmarkManager(
            engineFactory = BenchmarkEngineFactory { WhisperTranscriptionEngine() },
            benchmarkStore = benchmarkStore,
            demoAudioRepository = demoAudio,
            executionMutex = executionMutex,
            sampler = AndroidBenchmarkSampler(appContext),
            vadModelResolver = { vad ->
                installedModels.getInstalledModel(vad.modelId, vad.modelHash)
            },
        )
    val updates =
        createAppUpdateManager(appContext, applicationScope) { preferences().automaticUpdates }
    val serviceDependencies =
        object : ServiceDependencies {
            override val coordinator = this@AppGraph.coordinator
            override val repository = this@AppGraph.transcriptionRepository
            override val audioRepository = this@AppGraph.audioRepository
            override val thermalMonitor = this@AppGraph.thermalMonitor

            override suspend fun awaitStartupRecovery() = this@AppGraph.awaitStartupRecovery()
        }

    suspend fun awaitStartupRecovery() =
        recoveryMutex.withLock {
            if (!recovered) {
                RecoveryInitializer(transcriptionRepository).recover()
                recovered = true
            }
        }

    private suspend fun isReferencedByResumableJob(modelId: String): Boolean =
        transcriptionRepository.observeJobs().first().any { job ->
            job.status.name in RESUMABLE_STATUSES &&
                (
                    job.config.modelId == modelId ||
                        job.config.inference.vad
                            ?.modelId == modelId
                )
        }

    private fun preferences() = AppPreferences(appContext)

    private companion object {
        val RESUMABLE_STATUSES = setOf("PENDING", "RUNNING", "PAUSED")
    }
}
