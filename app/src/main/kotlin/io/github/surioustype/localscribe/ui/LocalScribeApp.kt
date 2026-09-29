@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("DEPRECATION")

package io.github.surioustype.localscribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.surioustype.localscribe.BuildConfig
import io.github.surioustype.localscribe.benchmark.AndroidHardwareProfile
import io.github.surioustype.localscribe.benchmark.BenchmarkComparisons
import io.github.surioustype.localscribe.core.domain.ChunkPlanner
import io.github.surioustype.localscribe.core.domain.RecommendationEngine
import io.github.surioustype.localscribe.core.domain.TranscriptAssembler
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.BenchmarkRecord
import io.github.surioustype.localscribe.core.model.ExportFormat
import io.github.surioustype.localscribe.core.model.InferenceConfig
import io.github.surioustype.localscribe.core.model.InstalledModel
import io.github.surioustype.localscribe.core.model.JobStatus
import io.github.surioustype.localscribe.core.model.ModelKind
import io.github.surioustype.localscribe.core.model.TranscriptionConfig
import io.github.surioustype.localscribe.core.model.TranscriptionJob
import io.github.surioustype.localscribe.core.model.UpdateAvailability
import io.github.surioustype.localscribe.core.model.VadConfig
import io.github.surioustype.localscribe.di.AppGraph
import io.github.surioustype.localscribe.di.AppPreferences
import io.github.surioustype.localscribe.service.TranscriptionService
import io.github.surioustype.localscribe.ui.playback.AndroidMediaPlayerBackend
import io.github.surioustype.localscribe.ui.playback.TranscriptPlaybackController
import kotlinx.coroutines.launch
import java.util.UUID

private enum class Destination { HOME, MODELS, SETTINGS }

@Composable
fun LocalScribeApp(graph: AppGraph, receivedUri: String?) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var onboarded by remember { mutableStateOf(preferences.onboardingComplete) }
    var appearanceMode by remember {
        mutableStateOf(
            runCatching {
                AppearanceMode.valueOf(preferences.appearanceMode)
            }.getOrDefault(AppearanceMode.SYSTEM),
        )
    }
    var dynamicColors by remember { mutableStateOf(preferences.dynamicColors) }
    val uiModel: LocalScribeUiStateViewModel = viewModel()
    val savedUi by uiModel.uiState.collectAsStateWithLifecycle()
    val theme =
        selectTheme(
            appearanceMode = appearanceMode,
            systemDark = isSystemInDarkTheme(),
            dynamicColors = dynamicColors,
            dynamicColorsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )
    val scheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (theme) {
                ThemeSelection.DYNAMIC_DARK -> dynamicDarkColorScheme(context)
                ThemeSelection.DYNAMIC_LIGHT -> dynamicLightColorScheme(context)
                ThemeSelection.DARK -> darkColorScheme()
                ThemeSelection.LIGHT -> lightColorScheme()
            }
        } else {
            when (theme) {
                ThemeSelection.DARK,
                ThemeSelection.DYNAMIC_DARK,
                -> darkColorScheme()
                ThemeSelection.LIGHT,
                ThemeSelection.DYNAMIC_LIGHT,
                -> lightColorScheme()
            }
        }
    MaterialTheme(colorScheme = scheme) {
        if (!onboarded) {
            Onboarding {
                preferences.onboardingComplete = true
                onboarded = true
            }
            return@MaterialTheme
        }
        LaunchedEffect(Unit) {
            graph.updates.checkForUpdate(AppVersion(BuildConfig.VERSION_NAME), force = false)
        }
        val destination =
            runCatching {
                Destination.valueOf(savedUi.destination)
            }.getOrDefault(Destination.HOME)
        Scaffold(
            topBar = { TopAppBar(title = { Text("LocalScribe") }) },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(selected = destination == item, onClick = {
                            uiModel.selectDestination(item.name)
                        }, icon = {}, label = {
                            Text(
                                item.name.lowercase().replaceFirstChar(Char::uppercase),
                            )
                        })
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .widthIn(max = 900.dp)
                    .padding(16.dp),
            ) {
                when (destination) {
                    Destination.HOME -> Home(graph, receivedUri, uiModel, savedUi)
                    Destination.MODELS -> Models(graph, uiModel, savedUi)
                    Destination.SETTINGS ->
                        Settings(graph, preferences, { appearanceMode = it }, {
                            dynamicColors =
                                it
                        })
                }
            }
        }
    }
}

@Composable
private fun Onboarding(done: () -> Unit) =
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Your recordings stay on this device",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "LocalScribe transcribes audio offline. Internet is used only when you choose to download a Whisper model or check for an app update.",
            )
            Text(
                "Download a multilingual Small model for a balanced first transcription. Models are never included in the APK and are never downloaded automatically.",
            )
            Button(
                onClick = done,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            "Finish onboarding"
                    },
            ) { Text("Continue") }
        }
    }

@Composable
private fun Home(
    graph: AppGraph,
    receivedUri: String?,
    uiModel: LocalScribeUiStateViewModel,
    savedUi: LocalScribeUiState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by graph.audioRepository.observeSources().collectAsStateWithLifecycle(emptyList())
    val jobs by graph.transcriptionRepository.observeJobs().collectAsStateWithLifecycle(emptyList())
    val models by graph.installedModels.observeInstalledModels().collectAsStateWithLifecycle(
        emptyList(),
    )
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                scope.launch {
                    runCatching { graph.audioRepository.importSource(it.toString(), true) }
                        .onSuccess { uiModel.setStatusMessage("Audio imported") }
                        .onFailure {
                            uiModel.setStatusMessage(
                                "Could not import this audio source",
                            )
                        }
                }
            }
        }
    val mediaPermission =
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    val permission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                scope.launch {
                    graph.audioRepository.refresh()
                }
            }
        }
    LaunchedEffect(receivedUri) {
        if (receivedUri != null && uiModel.consumeInboundAudio(receivedUri)) {
            runCatching { graph.audioRepository.importSource(receivedUri, true) }
                .onSuccess { uiModel.setStatusMessage("Shared audio imported") }
                .onFailure { uiModel.setStatusMessage("Could not import shared audio") }
        }
        ; graph.audioRepository.refresh()
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Audio", style = MaterialTheme.typography.headlineSmall) }
        item {
            ResponsiveActionButtons(listOf("Import file", "Show recordings")) { action ->
                when (action) {
                    "Import file" -> picker.launch(arrayOf("audio/*"))
                    "Show recordings" -> permission.launch(mediaPermission)
                }
            }
        }
        savedUi.statusMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(sources, key = { it.source.id }) { record ->
            Card(Modifier.fillMaxWidth().clickable { uiModel.selectSource(record.source.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(record.source.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${record.source.durationMs / 1000}s · ${record.accessStatus.name.lowercase()}",
                    )
                }
            }
        }
        item {
            Text(
                "Jobs",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        items(jobs, key = {
            it.id
        }) { job ->
            JobRow(
                job,
                graph,
                context,
                showTranscript = { uiModel.showTranscript(job.id) },
            )
        }
        savedUi.selectedSourceId?.let { sourceId ->
            item {
                SourceControls(
                    sourceId,
                    models,
                    graph,
                    context,
                    uiModel,
                    savedUi,
                    close = { uiModel.selectSource(null) },
                )
            }
        }
        savedUi.transcriptJobId?.let { jobId ->
            item {
                TranscriptScreen(
                    jobId = jobId,
                    graph = graph,
                    query = savedUi.transcriptQuery,
                    pendingExportFormat = savedUi.exportFormat,
                    setQuery = uiModel::setTranscriptQuery,
                    setExportFormat = uiModel::setExportFormat,
                    completeExport = uiModel::completeTranscriptExport,
                    setStatus = uiModel::setStatusMessage,
                    close = { uiModel.showTranscript(null) },
                )
            }
        }
    }
}

@Composable
private fun SourceControls(
    sourceId: String,
    installedModels: List<InstalledModel>,
    graph: AppGraph,
    context: Context,
    uiModel: LocalScribeUiStateViewModel,
    savedUi: LocalScribeUiState,
    close: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val transcriptionModels = installedModels.filter { it.descriptorId != "silero-v6.2.0" }
    val installedModelIds = transcriptionModels.map { it.descriptorId }.distinct()
    val vadModels = installedModels.filter { it.descriptorId == "silero-v6.2.0" }
    val selectedModel = savedUi.sourceModelId ?: installedModelIds.firstOrNull()
    val selectedVad = savedUi.sourceVadModelId
    val language = savedUi.sourceLanguage
    val message = savedUi.sourceMessage
    val beginTranscription: () -> Unit = begin@{
        val modelId = selectedModel ?: return@begin
        scope.launch {
            val source = graph.audioRepository.getSource(sourceId)
            val model = graph.installedModels.getInstalledModel(modelId)
            val vad = selectedVad?.let { graph.installedModels.getInstalledModel(it) }
            if (source == null ||
                source.accessStatus.name != "AVAILABLE" ||
                model == null ||
                (selectedVad != null && vad == null) ||
                source.source.durationMs <= 0
            ) {
                uiModel.setSourceSetup(
                    selectedModel,
                    language,
                    "This source or model is no longer available. Choose another one and try again.",
                )
                return@launch
            }
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val config =
                TranscriptionConfig(
                    modelId,
                    InferenceConfig(
                        threadCount = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
                        language =
                            language.takeUnless {
                                it ==
                                    "auto"
                            },
                        vad = vad?.let { VadConfig(it.descriptorId, it.sha256) },
                    ),
                )
            val job =
                TranscriptionJob(
                    id = id,
                    sourceId = sourceId,
                    config = config,
                    modelHash = model.sha256,
                    status = JobStatus.PENDING,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                    sourceFingerprint = source.contentFingerprint,
                )
            when (
                TranscriptionStartController.start(
                    notificationGranted = true,
                    createJob = {
                        graph.transcriptionRepository.createJob(
                            job,
                            ChunkPlanner().plan(id, modelId, source.source.durationMs, config, now),
                        )
                    },
                    launchForegroundService = {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, TranscriptionService::class.java)
                                .setAction(TranscriptionService.ACTION_START)
                                .putExtra(TranscriptionService.EXTRA_JOB_ID, id),
                        )
                    },
                    cancelJob = { graph.coordinator.cancel(id) },
                )
            ) {
                TranscriptionStartResult.STARTED -> close()
                TranscriptionStartResult.SERVICE_START_FAILED ->
                    uiModel.setSourceSetup(
                        selectedModel,
                        language,
                        "The transcription service could not start. Your audio was not sent anywhere.",
                    )
                TranscriptionStartResult.NOTIFICATION_PERMISSION_REQUIRED ->
                    uiModel.setSourceSetup(
                        selectedModel,
                        language,
                        "Notification permission is needed for visible transcription controls. Allow it and start again.",
                    )
            }
        }
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginTranscription()
            } else {
                uiModel.setSourceSetup(
                    selectedModel,
                    language,
                    "Notification permission is needed for visible transcription controls. Allow it and start again.",
                )
            }
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transcription setup", style = MaterialTheme.typography.titleMedium)
            if (installedModelIds.isEmpty()) {
                Text(
                    "Install a transcription model before starting.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            installedModelIds.forEach { id ->
                FilterChip(selected = selectedModel == id, onClick = {
                    uiModel.setSourceSetup(id, language, null)
                }, label = { Text(id) })
            }
            FilterChip(
                selected = selectedVad == null,
                onClick = { uiModel.setSourceSetup(selectedModel, language, null, null) },
                label = { Text("VAD off") },
            )
            vadModels.forEach { vad ->
                FilterChip(
                    selected = selectedVad == vad.descriptorId,
                    onClick = {
                        uiModel.setSourceSetup(selectedModel, language, null, vad.descriptorId)
                    },
                    label = { Text("Use ${vad.displayName} VAD") },
                )
            }
            OutlinedTextField(value = language, onValueChange = {
                uiModel.setSourceSetup(selectedModel, it, null)
            }, label = { Text("Language (auto, en, ru…)") })
            ResponsiveActionButtons(
                labels = listOf("Start transcription"),
                enabled = { selectedModel != null },
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    beginTranscription()
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun JobRow(
    job: TranscriptionJob,
    graph: AppGraph,
    context: Context,
    showTranscript: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val chunks by graph.transcriptionRepository
        .observeChunks(
            job.id,
        ).collectAsStateWithLifecycle(emptyList())
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val relinkAudio =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                scope.launch {
                    runCatching {
                        graph.audioRepository.relinkSource(
                            job.sourceId,
                            it.toString(),
                            true,
                        )
                    }.onSuccess { actionMessage = "Audio relinked. Resume to continue." }
                        .onFailure { actionMessage = "Could not relink this audio source." }
                }
            }
        }
    val totalDuration = chunks.sumOf { it.endMs - it.startMs }.coerceAtLeast(1L)
    val completedDuration =
        chunks.filter { it.status.name == "COMPLETED" }.sumOf {
            it.endMs -
                it.startMs
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${job.config.modelId} · ${job.status.name.lowercase()}")
            Text("${(completedDuration * 100 / totalDuration)}% complete")
            job.failure?.let {
                Text(
                    userFacingFailure(it.code.name),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.status ==
                    JobStatus.RUNNING
                ) {
                    OutlinedButton(
                        onClick = { scope.launch { graph.coordinator.pause(job.id) } },
                    ) { Text("Pause") }
                }
                if (job.status == JobStatus.PAUSED || job.status == JobStatus.PENDING) {
                    val resume: () -> Unit = {
                        when (
                            TranscriptionStartController.resume(
                                notificationGranted = true,
                                launchForegroundService = {
                                    ContextCompat.startForegroundService(
                                        context,
                                        Intent(context, TranscriptionService::class.java)
                                            .setAction(TranscriptionService.ACTION_RESUME)
                                            .putExtra(TranscriptionService.EXTRA_JOB_ID, job.id),
                                    )
                                },
                            )
                        ) {
                            TranscriptionStartResult.STARTED -> Unit
                            TranscriptionStartResult.NOTIFICATION_PERMISSION_REQUIRED ->
                                actionMessage =
                                    "Notification permission is needed for visible transcription controls. Allow it and resume again."
                            TranscriptionStartResult.SERVICE_START_FAILED ->
                                actionMessage =
                                    "The transcription service could not resume. Your paused job is still available."
                        }
                    }
                    val notificationPermission =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { granted ->
                            if (granted) {
                                resume()
                            } else {
                                actionMessage =
                                    "Notification permission is needed for visible transcription controls. Allow it and resume again."
                            }
                        }
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            resume()
                        }
                    }) { Text("Resume") }
                    if (job.failure?.code?.name in
                        setOf("SOURCE_PERMISSION_REQUIRED", "SOURCE_MISSING")
                    ) {
                        Text("Choose the same recording to keep the completed transcript parts.")
                        OutlinedButton(onClick = { relinkAudio.launch(arrayOf("audio/*")) }) {
                            Text("Reauthorize or relink audio")
                        }
                    }
                }
                if (job.status in setOf(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.PAUSED)) {
                    OutlinedButton(
                        onClick = { scope.launch { graph.coordinator.cancel(job.id) } },
                    ) { Text("Stop") }
                }
                if (job.status ==
                    JobStatus.COMPLETED
                ) {
                    OutlinedButton(onClick = showTranscript) { Text("Transcript") }
                }
            }
            actionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun userFacingFailure(code: String): String =
    when (code) {
        "THERMAL_CRITICAL" -> "Paused because the device is too warm. Let it cool, then resume."
        "SOURCE_PERMISSION_REQUIRED" ->
            "Audio access was lost. Re-import the audio file, then resume."
        "SOURCE_MISSING" -> "The audio source is unavailable. Choose an accessible copy."
        "MODEL_CORRUPTED", "MODEL_CHECKSUM_MISMATCH" ->
            "The selected model needs to be downloaded again."
        "TIMEOUT" -> "Android stopped long-running work. Resume while the app is visible."
        else -> "This job needs attention before it can continue."
    }

@Composable
private fun TranscriptScreen(
    jobId: String,
    graph: AppGraph,
    query: String,
    pendingExportFormat: String?,
    setQuery: (String) -> Unit,
    setExportFormat: (String?) -> Unit,
    completeExport: (Boolean) -> Unit,
    setStatus: (String?) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val job by graph.transcriptionRepository.observeJob(jobId).collectAsStateWithLifecycle(null)
    var sourceUri by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(job?.sourceId) {
        sourceUri =
            job?.let {
                graph.audioRepository
                    .getSource(it.sourceId)
                    ?.source
                    ?.uri
            }
    }
    val rawSegments by graph.transcriptionRepository
        .observeSegments(
            jobId,
        ).collectAsStateWithLifecycle(emptyList())
    val chunks by graph.transcriptionRepository
        .observeChunks(
            jobId,
        ).collectAsStateWithLifecycle(emptyList())
    val segments =
        remember(chunks, rawSegments) { TranscriptAssembler().assemble(chunks, rawSegments) }
    val player =
        remember {
            TranscriptPlaybackController(
                scope = scope,
                backendFactory = { AndroidMediaPlayerBackend(context.applicationContext) },
            )
        }
    val playback by player.state.collectAsStateWithLifecycle()
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            val format =
                pendingExportFormat?.let { value ->
                    ExportFormat.entries.firstOrNull {
                        it.name ==
                            value
                    }
                }
            if (uri != null && format != null) {
                scope.launch {
                    val source =
                        job?.let { graph.audioRepository.getSource(it.sourceId)?.source }
                            ?: run {
                                completeExport(false)
                                return@launch
                            }
                    runCatching { TranscriptExporter(context).write(uri, source, segments, format) }
                        .onSuccess { completeExport(true) }
                        .onFailure { completeExport(false) }
                }
            } else {
                setExportFormat(null)
            }
        }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) player.onStop()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.close()
        }
    }
    val filtered =
        remember(segments, query) { segments.filter { it.text.contains(query, ignoreCase = true) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transcript", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = close) { Text("Close") }
            }
            OutlinedTextField(query, setQuery, label = {
                Text("Search transcript")
            }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val source =
                            job?.let { graph.audioRepository.getSource(it.sourceId)?.source }
                                ?: return@launch
                        if (playback.isPlaying) player.pause() else player.play(source.uri)
                    }
                }) { Text(if (playback.isPlaying) "Pause audio" else "Play audio") }
            }
            OutlinedButton(onClick = {
                clipboard.setText(
                    AnnotatedString(
                        segments.joinToString("\n") {
                            it.text
                        },
                    ),
                )
            }) { Text("Copy transcript") }
            ExportFormat.entries.forEach { format ->
                OutlinedButton(onClick = {
                    setExportFormat(format.name)
                    save.launch("transcript.${format.name.lowercase()}")
                }) { Text("Export ${format.name}") }
            }
            Slider(
                value = playback.positionMs.toFloat(),
                onValueChange = { position ->
                    sourceUri?.let { player.seekTo(it, position.toLong()) }
                },
                valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = "Playback position"
                    },
            )
            Text("${timestamp(playback.positionMs)}")
            filtered.forEach { segment ->
                Text(
                    "${timestamp(segment.absoluteStartMs)}  ${segment.text}",
                    modifier =
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                job?.let { active ->
                                    graph.audioRepository
                                        .getSource(
                                            active.sourceId,
                                        )?.source
                                        ?.uri
                                        ?.let {
                                            player.seekTo(it, segment.absoluteStartMs)
                                        }
                                }
                            }
                        },
                )
            }
            playback.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                scope.launch {
                    val source =
                        job?.let { graph.audioRepository.getSource(it.sourceId)?.source }
                            ?: return@launch
                    runCatching {
                        TranscriptExporter(
                            context,
                        ).share(source, segments, ExportFormat.TXT)
                    }.onSuccess { setStatus("Transcript shared") }
                        .onFailure { setStatus("Transcript share failed") }
                }
            }) { Text("Share TXT") }
        }
    }
}

private fun timestamp(value: Long): String =
    "%02d:%02d".format(
        value / 60_000,
        (value / 1_000) % 60,
    )

@Composable
private fun Models(
    graph: AppGraph,
    uiModel: LocalScribeUiStateViewModel,
    savedUi: LocalScribeUiState,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val catalog by graph.catalog.observeCatalog().collectAsStateWithLifecycle(emptyList())
    val downloads by graph.downloads.observeDownloads().collectAsStateWithLifecycle(emptyList())
    val installed by graph.installedModels.observeInstalledModels().collectAsStateWithLifecycle(
        emptyList(),
    )
    val benchmarks by graph.benchmark.observeBenchmarks().collectAsStateWithLifecycle(emptyList())
    val demoSamples by graph.demoAudio.observeSamples().collectAsStateWithLifecycle(emptyList())
    val runState by graph.benchmark.observeRunState().collectAsStateWithLifecycle(
        io.github.surioustype.localscribe.benchmark
            .BenchmarkRunState(),
    )
    val selected = savedUi.selectedBenchmarkModels
    val status = savedUi.benchmarkStatus
    val benchmarkController = remember(scope) { BenchmarkRunController(scope) }
    val qualityResult = savedUi.qualityResult
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) benchmarkController.onStop()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            benchmarkController.onStop()
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Models", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Models stay outside the APK and download only when you request them.") }
        item { Text("Installed model storage: ${installed.sumOf { it.bytes } / (1024 * 1024)} MB") }
        items(
            catalog.filter { it.kind == ModelKind.TRANSCRIPTION },
            key = { it.id },
        ) { descriptor ->
            val installedModel = installed.firstOrNull { it.descriptorId == descriptor.id }
            val download = downloads.firstOrNull { it.modelId == descriptor.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(descriptor.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${descriptor.quality.name.lowercase()} · ${descriptor.languages.joinToString()} · ${descriptor.installedBytes / (1024 * 1024)} MB",
                    )
                    if (installedModel == null) {
                        Button(onClick = {
                            scope.launch {
                                runCatching { graph.downloads.enqueue(descriptor.id) }.onFailure {
                                    uiModel.setBenchmarkStatus("Download could not start")
                                }
                            }
                        }) { Text("Download") }
                    } else {
                        Text("Installed and SHA-256 verified")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(selected.contains(descriptor.id), { checked ->
                                uiModel.setSelectedBenchmarkModels(
                                    if (checked) {
                                        selected + descriptor.id
                                    } else {
                                        selected -
                                            descriptor.id
                                    },
                                )
                            })
                            OutlinedButton(onClick = {
                                scope.launch {
                                    runCatching {
                                        graph.modelFiles.delete(
                                            descriptor.id,
                                        )
                                    }.onFailure {
                                        uiModel.setBenchmarkStatus(
                                            "Cannot delete a model used by a pending, running, or paused job.",
                                        )
                                    }
                                }
                            }) { Text("Delete") }
                            OutlinedButton(onClick = {
                                benchmarkController.start {
                                    runCatching {
                                        graph.benchmark.runQualityDemo(
                                            installedModel,
                                            InferenceConfig(4, "en"),
                                            "english_jfk_37s",
                                            AndroidHardwareProfile.read(context),
                                        )
                                    }.onSuccess { result ->
                                        val sample =
                                            demoSamples.firstOrNull {
                                                it.id ==
                                                    result.sampleId
                                            }
                                        uiModel.setQualityResult(
                                            QualityResultPresentation(
                                                result.referenceText,
                                                result.recognizedText,
                                                result.score.wordErrors,
                                                result.score.referenceWordCount,
                                                result.differences.joinToString {
                                                    it.kind.name.lowercase() + ":" +
                                                        (
                                                            it.recognizedToken
                                                                ?: it.referenceToken.orEmpty()
                                                        )
                                                },
                                                sample
                                                    ?.let {
                                                        "${it.displayName} · ${it.licenseName} · ${it.sourceUrl}"
                                                    }.orEmpty(),
                                            ),
                                        )
                                        uiModel.setBenchmarkStatus(
                                            "Quality: WER ${(result.score.wordErrorRate * 100).toInt()}%, CER ${(result.score.characterErrorRate * 100).toInt()}%",
                                        )
                                    }.onFailure {
                                        uiModel.setBenchmarkStatus(
                                            "Quality demo did not complete",
                                        )
                                    }
                                }
                            }) { Text("Quality demo") }
                        }
                    }
                    download?.let {
                        Text(
                            "${it.status.name.lowercase()}: ${it.downloadedBytes / 1024} / ${it.totalBytes / 1024} KiB",
                        )
                    }
                    if (download?.status?.name == "DOWNLOADING" ||
                        download?.status?.name == "VERIFYING"
                    ) {
                        OutlinedButton(onClick = {
                            scope.launch { graph.downloads.cancel(descriptor.id) }
                        }) { Text("Cancel download") }
                    }
                    if (download?.status?.name == "FAILED" ||
                        download?.status?.name == "CANCELLED"
                    ) {
                        OutlinedButton(onClick = {
                            scope.launch { graph.downloads.retry(descriptor.id) }
                        }) { Text("Retry") }
                    }
                }
            }
        }
        item {
            Text(
                "Optional voice activity detection",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(
            catalog.filter {
                it.kind != ModelKind.TRANSCRIPTION
            },
            key = { "vad-${it.id}" },
        ) { descriptor ->
            val installedVad = installed.firstOrNull { it.descriptorId == descriptor.id }
            val download = downloads.firstOrNull { it.modelId == descriptor.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${descriptor.displayName} (VAD, optional)")
                    Text("VAD is never selected as a transcription model.")
                    if (installedVad ==
                        null
                    ) {
                        Button(onClick = {
                            scope.launch { graph.downloads.enqueue(descriptor.id) }
                        }) { Text("Download VAD") }
                    } else {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { graph.modelFiles.delete(descriptor.id) }.onFailure {
                                    uiModel.setBenchmarkStatus(
                                        "This VAD model is used by a resumable job.",
                                    )
                                }
                            }
                        }) { Text("Delete VAD") }
                    }
                    download?.let {
                        Text(
                            "${it.status.name.lowercase()}: ${it.downloadedBytes / 1024} / ${it.totalBytes / 1024} KiB",
                        )
                    }
                    if (download?.status?.name == "DOWNLOADING" ||
                        download?.status?.name == "VERIFYING"
                    ) {
                        OutlinedButton(onClick = {
                            scope.launch { graph.downloads.cancel(descriptor.id) }
                        }) { Text("Cancel") }
                    }
                    if (download?.status?.name == "FAILED" ||
                        download?.status?.name == "CANCELLED"
                    ) {
                        OutlinedButton(onClick = {
                            scope.launch { graph.downloads.retry(descriptor.id) }
                        }) { Text("Retry") }
                    }
                }
            }
        }
        item {
            Button(enabled = selected.isNotEmpty() && !benchmarkController.isBusy, onClick = {
                val models = installed.filter { it.descriptorId in selected }
                benchmarkController.start {
                    runCatching {
                        graph.benchmark.benchmarkSelected(
                            models,
                            InferenceConfig(4, "en"),
                            "english_jfk_37s",
                            AndroidHardwareProfile.read(context),
                        )
                    }.onSuccess {
                        uiModel.setBenchmarkStatus(
                            "Benchmark saved. Timings use actual on-device inference after warm model loading.",
                        )
                    }.onFailure { uiModel.setBenchmarkStatus("Benchmark cancelled or failed") }
                }
            }) { Text("Benchmark selected") }
        }
        item {
            Text(
                "Benchmark: ${runState.phase.name.lowercase()} ${runState.completedModels}/${runState.totalModels}",
            )
        }
        status?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        qualityResult?.let { quality ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reference: ${quality.referenceText}")
                    Text("Recognized: ${quality.recognizedText}")
                    Text(
                        "Word errors ${quality.wordErrors}/${quality.referenceWordCount}",
                    )
                    Text("Differences: ${quality.differences}")
                    Text("Fixture: ${quality.fixture}")
                }
            }
        }
        item {
            Text(
                "Russian and mixed-language quality demos are unavailable in this bundled fixture manifest.",
            )
        }
        item { Text("Saved results", style = MaterialTheme.typography.titleMedium) }
        val comparison = latestBenchmarkComparison(benchmarks)
        comparison.reference?.let { reference ->
            item {
                Text(
                    "Comparable runs use the same device, model hash, sample, and inference configuration as ${reference.modelId}.",
                )
            }
        }
        items(comparison.compatible, key = { it.id }) { result ->
            Text(
                "${result.modelId}: ${result.processingDurationMs}ms, ${"%.2f".format(
                    result.realTimeFactor,
                )} RTF · peak ${result.approximatePeakMemoryBytes?.div(
                    1024 * 1024,
                ) ?: "unknown"} MB · thermal ${result.thermalStatus ?: "unknown"}",
            )
        }
        item {
            val recommendations =
                RecommendationEngine().recommend(
                    catalog,
                    benchmarks,
                    AndroidHardwareProfile.read(context),
                )
            Text("Recommendations")
            recommendations.forEach {
                Text(
                    "${it.modelId}: ${it.level.name.lowercase()} — ${it.reason}",
                )
            }
        }
    }
}

data class BenchmarkComparisonPresentation(
    val reference: BenchmarkRecord?,
    val compatible: List<BenchmarkRecord>,
)

/** Room returns benchmark records newest first, so the first record anchors the visible group. */
fun latestBenchmarkComparison(records: List<BenchmarkRecord>): BenchmarkComparisonPresentation {
    val reference = records.firstOrNull()
    return BenchmarkComparisonPresentation(
        reference = reference,
        compatible = reference?.let { BenchmarkComparisons.compatibleWith(it, records) }.orEmpty(),
    )
}

@Composable
private fun Settings(
    graph: AppGraph,
    preferences: AppPreferences,
    setAppearanceMode: (AppearanceMode) -> Unit,
    setDynamicColors: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val update by graph.updates.observeState().collectAsStateWithLifecycle(
        io.github.surioustype.localscribe.core.model
            .UpdateState(UpdateAvailability.UNKNOWN),
    )
    val updateDownload by graph.updates.observeDownload().collectAsStateWithLifecycle(
        io.github.surioustype.localscribe.core.model.UpdateDownload(
            io.github.surioustype.localscribe.core.model.UpdateDownloadStatus.IDLE,
            0,
            0,
        ),
    )
    val presentation = updatePresentation(update)
    var automatic by remember { mutableStateOf(preferences.automaticUpdates) }
    var dynamic by remember { mutableStateOf(preferences.dynamicColors) }
    var appearanceMode by remember {
        mutableStateOf(
            runCatching {
                AppearanceMode.valueOf(preferences.appearanceMode)
            }.getOrDefault(AppearanceMode.SYSTEM),
        )
    }
    var awaitingUnknownSourcesReturn by remember { mutableStateOf(false) }
    var installReturnMessage by remember { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && awaitingUnknownSourcesReturn) {
                    awaitingUnknownSourcesReturn = false
                    installReturnMessage =
                        "Unknown-source access returned. Tap Install update to continue, or keep it disabled to cancel."
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item {
            PreferenceSwitch("Automatic update checks", automatic) {
                automatic = it
                preferences.automaticUpdates =
                    it
            }
        }
        item {
            PreferenceSwitch("Use dynamic colors", dynamic) {
                dynamic = it
                preferences.dynamicColors =
                    it
                setDynamicColors(it)
            }
        }
        item {
            Text("Theme")
            AppearanceMode.entries.forEach { mode ->
                FilterChip(
                    selected = appearanceMode == mode,
                    onClick = {
                        appearanceMode = mode
                        preferences.appearanceMode = mode.name
                        setAppearanceMode(mode)
                    },
                    label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        item { Text("Updates", style = MaterialTheme.typography.titleLarge) }
        item { Text("App version ${BuildConfig.VERSION_NAME} · ${presentation.status}") }
        update.lastCheckedAtEpochMs?.let { checked -> item { Text("Last checked: $checked") } }
        update.failure?.let {
            item {
                Text(
                    "Update check failed. Try again when online.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Button(onClick = {
                scope.launch {
                    graph.updates.checkForUpdate(AppVersion(BuildConfig.VERSION_NAME), force = true)
                }
            }) { Text("Check now") }
        }
        presentation.releaseNotes?.let { notes -> item { Text(notes) } }
        if (presentation.canDownload) {
            item {
                Button(onClick = {
                    scope.launch { graph.updates.downloadAvailableUpdate() }
                }) { Text("Download verified update") }
            }
        }
        item {
            Text(
                "Download: ${updateDownload.status.name.lowercase()} ${updateDownload.downloadedBytes / 1024} / ${updateDownload.totalBytes / 1024} KiB",
            )
        }
        if (updateDownload.status ==
            io.github.surioustype.localscribe.core.model.UpdateDownloadStatus.READY_TO_INSTALL
        ) {
            item {
                Button(onClick = {
                    awaitingUnknownSourcesReturn =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    scope.launch { graph.updates.requestInstall() }
                }) { Text("Install update") }
            }
        }
        installReturnMessage?.let { item { Text(it) } }
        item {
            Text(
                "Privacy: audio and transcripts remain on this device. No analytics or cloud transcription are used.",
            )
        }
        item {
            Text(
                "Whisper.cpp is distributed under its bundled license. Demo fixture provenance and rights are shown in the quality demo manifest.",
            )
        }
    }
}

@Composable
private fun PreferenceSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
