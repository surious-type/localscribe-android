@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("DEPRECATION")

package io.github.surioustype.localscribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.text.font.FontWeight
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
import io.github.surioustype.localscribe.benchmark.BenchmarkRunState
import io.github.surioustype.localscribe.core.domain.ChunkPlanner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private enum class Destination { HOME, PROCESSING, MODELS, SETTINGS }

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
                ThemeSelection.DARK -> localScribeDarkScheme()
                ThemeSelection.LIGHT -> localScribeLightScheme()
            }
        } else {
            when (theme) {
                ThemeSelection.DARK,
                ThemeSelection.DYNAMIC_DARK,
                -> localScribeDarkScheme()
                ThemeSelection.LIGHT,
                ThemeSelection.DYNAMIC_LIGHT,
                -> localScribeLightScheme()
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
        val detail = savedUi.transcriptJobId != null || savedUi.selectedSourceId != null
        BackHandler(enabled = uiModel.canNavigateBack()) {
            uiModel.navigateBack()
        }
        val title =
            when {
                savedUi.transcriptJobId != null -> "Транскрипция"
                savedUi.selectedSourceId != null -> "Настройка транскрибации"
                destination == Destination.HOME -> "LocalScribe"
                destination == Destination.PROCESSING -> "Обработка"
                destination == Destination.MODELS -> "Модели"
                else -> "Настройки"
            }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        if (detail) {
                            IconButton(onClick = uiModel::navigateBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                            }
                        }
                    },
                    colors =
                        androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                )
            },
            bottomBar = {
                if (!detail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        Destination.entries.forEach { item ->
                            val icon =
                                when (item) {
                                    Destination.HOME -> Icons.Default.Home
                                    Destination.PROCESSING -> Icons.Default.Mic
                                    Destination.MODELS -> Icons.Default.Folder
                                    Destination.SETTINGS -> Icons.Default.Settings
                                }
                            val label =
                                when (item) {
                                    Destination.HOME -> "Записи"
                                    Destination.PROCESSING -> "Обработка"
                                    Destination.MODELS -> "Модели"
                                    Destination.SETTINGS -> "Настройки"
                                }
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { uiModel.selectDestination(item.name) },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .widthIn(max = 900.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                when {
                    savedUi.transcriptJobId != null ->
                        TranscriptScreen(
                            requireNotNull(savedUi.transcriptJobId),
                            graph,
                            savedUi.transcriptQuery,
                            savedUi.exportFormat,
                            uiModel::setTranscriptQuery,
                            uiModel::setExportFormat,
                            uiModel::completeTranscriptExport,
                            uiModel::setStatusMessage,
                            close = { uiModel.showTranscript(null) },
                        )
                    savedUi.selectedSourceId != null -> {
                        val installed by graph.installedModels
                            .observeInstalledModels()
                            .collectAsStateWithLifecycle(emptyList())
                        LazyColumn {
                            item {
                                SourceControls(
                                    requireNotNull(savedUi.selectedSourceId),
                                    installed,
                                    graph,
                                    context,
                                    uiModel,
                                    savedUi,
                                    close = { uiModel.selectSource(null) },
                                )
                            }
                        }
                    }
                    destination == Destination.HOME -> Home(graph, receivedUri, uiModel, savedUi)
                    destination == Destination.PROCESSING -> Processing(graph, uiModel)
                    destination == Destination.MODELS -> Models(graph, uiModel, savedUi)
                    else ->
                        Settings(graph, preferences, { appearanceMode = it }, {
                            dynamicColors = it
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
                "Ваши записи остаются на вашем устройстве",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "LocalScribe распознаёт речь полностью офлайн. Интернет нужен " +
                    "только для скачивания моделей и проверки обновлений.",
            )
            Text(
                "Для начала рекомендуем мультиязычную модель Small. " +
                    "Вы сами выбираете, какие модели скачивать.",
            )
            Button(
                onClick = done,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            "Завершить ознакомление"
                    },
            ) { Text("Начать") }
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
    val recordingPreferences = remember(context) { AppPreferences(context) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(recordingPreferences.recordingsFilter) }
    var sort by remember { mutableStateOf(recordingPreferences.recordingsSort) }
    val visible =
        sources
            .filter { item ->
                item.source.displayName.contains(query, ignoreCase = true) &&
                    when (filter) {
                        "ready" ->
                            jobs.any {
                                it.sourceId == item.source.id && it.status == JobStatus.COMPLETED
                            }
                        "new" ->
                            jobs.none {
                                it.sourceId == item.source.id && it.status == JobStatus.COMPLETED
                            }
                        else -> true
                    }
            }.let { items ->
                when (sort) {
                    "old" -> items.asReversed()
                    "az" -> items.sortedBy { it.source.displayName.lowercase() }
                    "za" -> items.sortedByDescending { it.source.displayName.lowercase() }
                    "long" -> items.sortedByDescending { it.source.durationMs }
                    "short" -> items.sortedBy { it.source.durationMs }
                    else -> items
                }
            }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ваши записи", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск записей…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "Все", "ready" to "С текстом", "new" to "Без текста")
                    .forEach { (value, label) ->
                        FilterChip(
                            selected = filter == value,
                            onClick = {
                                filter = value
                                recordingPreferences.recordingsFilter = value
                            },
                            label = { Text(label) },
                        )
                    }
            }
        }
        item {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (sort) {
                            "old" -> "Сначала старые"
                            "az" -> "По названию А–Я"
                            "za" -> "По названию Я–А"
                            "long" -> "Сначала длинные"
                            "short" -> "Сначала короткие"
                            else -> "Сначала новые"
                        },
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    listOf(
                        "new" to "Сначала новые",
                        "old" to "Сначала старые",
                        "az" to "По названию А–Я",
                        "za" to "По названию Я–А",
                        "long" to "Сначала длинные",
                        "short" to "Сначала короткие",
                    ).forEach { (value, title) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(title) },
                            onClick = {
                                sort = value
                                recordingPreferences.recordingsSort = value
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                    Text("＋ Импорт")
                }
                OutlinedButton(onClick = { permission.launch(mediaPermission) }) {
                    Text("Показать записи")
                }
            }
        }
        savedUi.statusMessage?.let { item { Text(it) } }
        if (visible.isEmpty()) {
            item {
                Card(colors = localScribeCardColors(), shape = localScribeShape) {
                    Text("Нет записей для выбранного фильтра.", Modifier.padding(20.dp))
                }
            }
        }
        items(visible, key = { it.source.id }) { record ->
            val ready =
                jobs.any {
                    it.sourceId == record.source.id && it.status == JobStatus.COMPLETED
                }
            Card(
                Modifier.fillMaxWidth().clickable { uiModel.selectSource(record.source.id) },
                colors = localScribeCardColors(),
                shape = localScribeShape,
            ) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            record.source.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatAudioDuration(record.source.durationMs) + " · " +
                                localizedSourceStatus(record.accessStatus.name),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (ready) {
                            Text(
                                "✓ Транскрипция готова",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Processing(graph: AppGraph, uiModel: LocalScribeUiStateViewModel) {
    val context = LocalContext.current
    val jobs by graph.transcriptionRepository.observeJobs().collectAsStateWithLifecycle(emptyList())
    val sources by graph.audioRepository.observeSources().collectAsStateWithLifecycle(emptyList())
    // Completed work stays visible instead of disappearing from the active-only filter.
    val visible = jobs.filter { it.status != JobStatus.CANCELLED }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Транскрибации", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Текущие и готовые записи",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (visible.isEmpty()) {
            item {
                Card(colors = localScribeCardColors(), shape = localScribeShape) {
                    Text("Здесь появятся запущенные транскрибации.", Modifier.padding(20.dp))
                }
            }
        }
        items(visible, key = { it.id }) { job ->
            JobRow(
                job = job,
                graph = graph,
                context = context,
                sourceName =
                    sources
                        .firstOrNull { it.source.id == job.sourceId }
                        ?.source
                        ?.displayName,
                showTranscript = { uiModel.showTranscript(job.id) },
            )
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
    val existingJobs by graph.transcriptionRepository
        .observeJobs()
        .collectAsStateWithLifecycle(emptyList())
    val latestCompletedJob =
        existingJobs.firstOrNull {
            it.sourceId == sourceId && it.status == JobStatus.COMPLETED
        }
    val transcriptionModels = installedModels.filter { it.descriptorId != "silero-v6.2.0" }
    val installedModelIds = transcriptionModels.map { it.descriptorId }.distinct()
    val vadModels = installedModels.filter { it.descriptorId == "silero-v6.2.0" }
    val selectedModel = savedUi.sourceModelId ?: installedModelIds.firstOrNull()
    // Prefer speech detection whenever its tiny model is installed; keep explicit opt-out.
    val selectedVad =
        when (savedUi.sourceVadModelId) {
            "disabled" -> null
            null -> vadModels.firstOrNull()?.descriptorId
            else -> savedUi.sourceVadModelId
        }
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
                    // Persisted in the job: resuming an old job keeps its original boundaries.
                    chunkDurationMs = 20_000,
                    overlapMs = 2_000,
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
                TranscriptionStartResult.STARTED -> {
                    close()
                    uiModel.selectDestination(Destination.PROCESSING.name)
                }
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
    var sourceName by remember { mutableStateOf("Аудиозапись") }
    LaunchedEffect(sourceId) {
        sourceName = graph.audioRepository
            .getSource(sourceId)
            ?.source
            ?.displayName ?: "Аудиозапись"
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(colors = localScribeCardColors(), shape = localScribeShape) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Column {
                    Text(sourceName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Вся обработка выполняется на телефоне",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        latestCompletedJob?.let { completed ->
            Card(colors = localScribeCardColors(), shape = localScribeShape) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Для этой записи есть готовая транскрипция",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = {
                        uiModel.selectSource(null)
                        uiModel.showTranscript(completed.id)
                    }) {
                        Text("Открыть расшифровку")
                    }
                }
            }
        }
        Text("Модель распознавания", style = MaterialTheme.typography.titleMedium)
        if (installedModelIds.isEmpty()) {
            Text("Сначала скачайте модель во вкладке «Модели».")
        }
        transcriptionModels.forEach { model ->
            val active = selectedModel == model.descriptorId
            Card(
                shape = localScribeShape,
                colors = localScribeCardColors(),
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        uiModel.setSourceSetup(model.descriptorId, language, null)
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = active,
                        onClick = { uiModel.setSourceSetup(model.descriptorId, language, null) },
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (model.descriptorId == "small") {
                                "Рекомендуется для начала · хорошее качество"
                            } else {
                                "Установлена · " + (model.bytes / (1_024 * 1_024)) + " МБ"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Text("Язык записи", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Авто", "ru" to "Русский", "en" to "English")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = language == value,
                        onClick = { uiModel.setSourceSetup(selectedModel, value, null) },
                        label = { Text(label) },
                    )
                }
        }
        Card(colors = localScribeCardColors(), shape = localScribeShape) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Определение речи (VAD)")
                    Text(
                        "Помогает пропускать тишину и уменьшать ложные фразы",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = selectedVad != null,
                    onCheckedChange = { enabled ->
                        uiModel.setSourceSetup(
                            selectedModel,
                            language,
                            null,
                            if (enabled) vadModels.firstOrNull()?.descriptorId else "disabled",
                        )
                    },
                    enabled = vadModels.isNotEmpty(),
                )
            }
        }
        if (vadModels.isEmpty()) {
            Text(
                "Для определения речи можно скачать Silero VAD во вкладке «Модели».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    beginTranscription()
                }
            },
            enabled = selectedModel != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Начать транскрибацию")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun JobRow(
    job: TranscriptionJob,
    graph: AppGraph,
    context: Context,
    showTranscript: () -> Unit,
    sourceName: String? = null,
) {
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(job.status) {
        while (job.status == JobStatus.RUNNING) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
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
    val timing = transcriptionTiming(chunks, now)
    val totalDuration = timing.totalAudioMs.coerceAtLeast(1L)
    val completedDuration = timing.completedAudioMs
    val savedSegments by graph.transcriptionRepository
        .observeSegments(job.id)
        .collectAsStateWithLifecycle(emptyList())
    val previewSegments by graph.liveTranscript
        .observe(job.id)
        .collectAsStateWithLifecycle(emptyList())
    Card(
        Modifier.fillMaxWidth(),
        colors = localScribeCardColors(),
        shape = localScribeShape,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                sourceName ?: "Аудиозапись",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                job.config.modelId + " · " + localizedJobStatus(job.status.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LocalScribeProgress(
                progress =
                    if (job.status == JobStatus.RUNNING && completedDuration == 0L) {
                        null
                    } else {
                        completedDuration.toFloat() / totalDuration.toFloat()
                    },
            )
            if (job.status == JobStatus.RUNNING || job.status == JobStatus.PAUSED) {
                val remainingText =
                    timing.remainingMs?.let { "~" + formatAudioDuration(it) } ?: "рассчитываем…"
                Text(
                    "Прошло: " + formatAudioDuration(timing.elapsedMs) +
                        " · Осталось: " + remainingText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Обработано аудио: " + formatAudioDuration(completedDuration) +
                    " / " + formatAudioDuration(totalDuration),
                style = MaterialTheme.typography.bodySmall,
            )
            if (savedSegments.isNotEmpty() || previewSegments.isNotEmpty()) {
                Text(
                    if (previewSegments.isNotEmpty()) {
                        "Текст появляется в реальном времени"
                    } else {
                        "Уже распознано"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    (savedSegments.takeLast(2) + previewSegments)
                        .takeLast(4)
                        .joinToString(" ") { it.text },
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            job.failure?.let {
                Text(
                    userFacingFailure(it.code.name),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.status ==
                    JobStatus.RUNNING
                ) {
                    OutlinedButton(
                        onClick = { scope.launch { graph.coordinator.pause(job.id) } },
                    ) { Text("Пауза") }
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
                    }) { Text("Продолжить") }
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
                    ) { Text("Остановить") }
                }
                if (job.status in setOf(JobStatus.COMPLETED, JobStatus.RUNNING, JobStatus.PAUSED)) {
                    OutlinedButton(
                        onClick = showTranscript,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть текст")
                    }
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
    val previewSegments by graph.liveTranscript
        .observe(jobId)
        .collectAsStateWithLifecycle(emptyList())
    val visibleSegments = segments + previewSegments
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
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uri = result.data?.data
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
        remember(visibleSegments, query) {
            visibleSegments.filter { it.text.contains(query, ignoreCase = true) }
        }
    var showExport by remember { mutableStateOf(false) }
    LaunchedEffect(sourceUri) {
        sourceUri?.let { player.seekTo(it, 0L) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Расшифровка записи",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Card(shape = localScribeShape, colors = localScribeCardColors()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Оригинальная запись", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val source =
                                    job?.let {
                                        graph.audioRepository.getSource(it.sourceId)?.source
                                    } ?: return@launch
                                if (playback.isPlaying) player.pause() else player.play(source.uri)
                            }
                        }) {
                            Icon(
                                if (playback.isPlaying) {
                                    Icons.Default.Pause
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = null,
                            )
                            Text(if (playback.isPlaying) "Пауза" else "Воспроизвести")
                        }
                    }
                    Slider(
                        value =
                            playback.positionMs
                                .coerceIn(
                                    0L,
                                    playback.durationMs.coerceAtLeast(1L),
                                ).toFloat(),
                        onValueChange = { position ->
                            sourceUri?.let { player.seekTo(it, position.toLong()) }
                        },
                        valueRange = 0f..playback.durationMs.coerceAtLeast(1L).toFloat(),
                        modifier =
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = "Позиция воспроизведения"
                            },
                    )
                    Text(
                        formatAudioDuration(playback.positionMs) + " / " +
                            formatAudioDuration(playback.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    playback.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                query,
                setQuery,
                label = { Text("Поиск по тексту") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    enabled = segments.isNotEmpty(),
                    onClick = {
                        clipboard.setText(AnnotatedString(segments.joinToString("\n") { it.text }))
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Копировать текст")
                }
                IconButton(
                    enabled = segments.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            val source =
                                job?.let {
                                    graph.audioRepository.getSource(it.sourceId)?.source
                                } ?: return@launch
                            runCatching {
                                TranscriptExporter(context).share(
                                    source,
                                    segments,
                                    ExportFormat.TXT,
                                )
                            }.onSuccess {
                                setStatus("Текст отправлен")
                            }.onFailure {
                                setStatus("Не удалось поделиться текстом")
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Поделиться текстом")
                }
                IconButton(
                    enabled = segments.isNotEmpty(),
                    onClick = { showExport = true },
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = "Экспортировать текст")
                }
            }
        }
        if (visibleSegments.isEmpty()) {
            item {
                Card(shape = localScribeShape, colors = localScribeCardColors()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (job?.status == JobStatus.COMPLETED) {
                                "Речь не обнаружена"
                            } else {
                                "Текст пока не готов"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (job?.status == JobStatus.COMPLETED) {
                                "Обработка завершилась, но распознанных фрагментов нет. " +
                                    "Попробуйте другую модель или проверьте, есть ли речь в аудио."
                            } else {
                                "Распознанные фрагменты появятся здесь по мере " +
                                    "завершения обработки частей записи."
                            },
                        )
                    }
                }
            }
        } else if (filtered.isEmpty()) {
            item { Text("По запросу ничего не найдено.") }
        }
        items(filtered, key = { it.id }) { segment ->
            Card(
                colors = localScribeCardColors(),
                shape = RoundedCornerShape(14.dp),
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            val uri =
                                job?.let {
                                    graph.audioRepository
                                        .getSource(it.sourceId)
                                        ?.source
                                        ?.uri
                                }
                            uri?.let { player.seekTo(it, segment.absoluteStartMs) }
                        }
                    },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        formatAudioDuration(segment.absoluteStartMs),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(segment.text, style = MaterialTheme.typography.bodyLarge)
                    if (segment.id.startsWith("preview-")) {
                        Text(
                            "Распознаётся · предварительный текст",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (showExport) {
        ModalBottomSheet(onDismissRequest = { showExport = false }) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Экспортировать текст", style = MaterialTheme.typography.titleLarge)
                ExportFormat.entries.forEach { format ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = segments.isNotEmpty(),
                        onClick = {
                            showExport = false
                            setExportFormat(format.name)
                            val (extension, mime) =
                                when (format) {
                                    ExportFormat.TXT -> "txt" to "text/plain"
                                    ExportFormat.MARKDOWN -> "md" to "text/markdown"
                                    ExportFormat.SRT -> "srt" to "application/x-subrip"
                                    ExportFormat.VTT -> "vtt" to "text/vtt"
                                    ExportFormat.JSON -> "json" to "application/json"
                                }
                            val intent =
                                Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType(mime)
                                    .putExtra(
                                        Intent.EXTRA_TITLE,
                                        "localscribe-transcript." + extension,
                                    )
                            save.launch(intent)
                        },
                    ) {
                        Text(
                            when (format) {
                                ExportFormat.MARKDOWN -> "Markdown (.md)"
                                else -> format.name + " (." + format.name.lowercase() + ")"
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
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
    val scope = rememberCoroutineScope()
    val catalog by graph.catalog.observeCatalog().collectAsStateWithLifecycle(emptyList())
    val downloads by graph.downloads.observeDownloads().collectAsStateWithLifecycle(emptyList())
    val installed by graph.installedModels
        .observeInstalledModels()
        .collectAsStateWithLifecycle(emptyList())
    val benchmarks by graph.benchmark
        .observeBenchmarks()
        .collectAsStateWithLifecycle(emptyList())
    val runState by graph.benchmark
        .observeRunState()
        .collectAsStateWithLifecycle(BenchmarkRunState())
    val controller = uiModel.benchmarkController
    var actionModelId by remember { mutableStateOf<String?>(null) }
    var qualityModelId by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var testStartedAt by remember { mutableStateOf(0L) }
    var testElapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(runState.phase, runState.modelId) {
        if (runState.phase.name == "RUNNING") {
            if (testStartedAt == 0L) testStartedAt = android.os.SystemClock.elapsedRealtime()
            while (runState.phase.name == "RUNNING") {
                testElapsed = android.os.SystemClock.elapsedRealtime() - testStartedAt
                delay(1_000)
            }
        } else {
            testStartedAt = 0L
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Локальные модели", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Модель скачивается один раз. Нажмите на значок теста для проверки на вашем телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(catalog.filter { it.kind == ModelKind.TRANSCRIPTION }, key = { it.id }) { model ->
            val saved = installed.firstOrNull { it.descriptorId == model.id }
            val download = downloads.firstOrNull { it.modelId == model.id }
            val downloading =
                download?.status?.name in setOf("QUEUED", "DOWNLOADING", "VERIFYING")
            val result =
                benchmarks.firstOrNull {
                    it.modelId == model.id && it.modelHash.equals(saved?.sha256, true)
                }
            val testing =
                runState.phase.name == "RUNNING" && runState.modelId == model.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = localScribeShape,
                colors = localScribeCardColors(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (model.id == "small") "Рекомендуется для начала · " else "") +
                            when (model.quality.name) {
                                "LOW" -> "Быстрая"
                                "BALANCED" -> "Баланс качества и скорости"
                                else -> "Высокая точность"
                            } + " · " + (model.installedBytes / 1_048_576) + " МБ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when {
                            saved != null -> "Установлена и проверена"
                            download?.status?.name == "QUEUED" -> "Ожидает скачивания"
                            download?.status?.name == "DOWNLOADING" -> "Скачивается…"
                            download?.status?.name == "VERIFYING" -> "Проверка файла…"
                            download?.status?.name == "FAILED" -> "Не удалось скачать"
                            else -> "Не установлена"
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (saved == null && !downloading) {
                            IconButton(onClick = {
                                actionModelId = model.id
                                actionError = null
                                scope.launch {
                                    runCatching {
                                        if (download?.status?.name in
                                            setOf("CANCELLED", "FAILED")
                                        ) {
                                            graph.downloads.retry(model.id)
                                        } else {
                                            graph.downloads.enqueue(model.id)
                                        }
                                    }.onFailure {
                                        actionError =
                                            "Не удалось начать загрузку. Попробуйте ещё раз."
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Default.FileDownload,
                                    contentDescription = "Скачать модель",
                                )
                            }
                        }
                        if (saved != null) {
                            IconButton(
                                enabled = !controller.isBusy,
                                onClick = {
                                    actionModelId = model.id
                                    qualityModelId = model.id
                                    actionError = null
                                    testStartedAt = android.os.SystemClock.elapsedRealtime()
                                    controller.start {
                                        runCatching {
                                            graph.benchmark.runQualityDemo(
                                                saved,
                                                InferenceConfig(4, "en"),
                                                "english_jfk_37s",
                                                AndroidHardwareProfile.read(context),
                                            )
                                        }.onSuccess { quality ->
                                            uiModel.setQualityResult(
                                                QualityResultPresentation(
                                                    quality.referenceText,
                                                    quality.recognizedText,
                                                    quality.score.wordErrors,
                                                    quality.score.referenceWordCount,
                                                    quality.differences.joinToString {
                                                        it.kind.name
                                                    },
                                                    "Тестовая английская речь",
                                                ),
                                            )
                                            uiModel.setBenchmarkStatus(
                                                "WER " +
                                                    (quality.score.wordErrorRate * 100).toInt() +
                                                    "% · CER " +
                                                    (quality.score.characterErrorRate * 100)
                                                        .toInt() + "%",
                                            )
                                        }.onFailure {
                                            actionError = "Тест качества не завершён."
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Science, contentDescription = "Тест качества")
                            }
                            IconButton(
                                enabled = !controller.isBusy,
                                onClick = {
                                    actionModelId = model.id
                                    actionError = null
                                    testStartedAt = android.os.SystemClock.elapsedRealtime()
                                    controller.start {
                                        runCatching {
                                            graph.benchmark.benchmark(
                                                saved,
                                                InferenceConfig(4, "en"),
                                                "english_jfk_37s",
                                                AndroidHardwareProfile.read(context),
                                            )
                                        }.onFailure {
                                            actionError = "Не удалось измерить скорость."
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = "Тест скорости")
                            }
                            IconButton(
                                enabled = !controller.isBusy,
                                onClick = {
                                    actionModelId = model.id
                                    actionError = null
                                    scope.launch {
                                        runCatching { graph.modelFiles.delete(model.id) }
                                            .onFailure {
                                                actionError =
                                                    "Модель используется задачей или её не удалось удалить."
                                            }
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить модель")
                            }
                        }
                        if (downloading) {
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { graph.downloads.cancel(model.id) }
                                        .onFailure { actionError = "Не удалось отменить загрузку." }
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Отменить загрузку")
                            }
                        }
                    }
                    if (downloading) {
                        LocalScribeProgress(
                            if (download?.status?.name == "DOWNLOADING") {
                                download.downloadedBytes.toFloat() /
                                    download.totalBytes.coerceAtLeast(1L).toFloat()
                            } else {
                                null
                            },
                        )
                        if (download?.status?.name == "DOWNLOADING") {
                            Text(
                                (download.downloadedBytes / 1_048_576).toString() +
                                    " из " + (download.totalBytes / 1_048_576) + " МБ",
                            )
                        }
                    }
                    if (testing) {
                        LocalScribeProgress(null)
                        Text(
                            "Идёт тест · прошло " + formatAudioDuration(testElapsed) +
                                ". Ожидаем результаты распознавания.",
                        )
                    }
                    if (result != null) {
                        Text(
                            "Скорость на телефоне: " +
                                "%.1f".format(result.realTimeMultiplier) + "× реального времени",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (qualityModelId == model.id && savedUi.qualityResult != null) {
                        Text(
                            "Качество теста: " + (savedUi.benchmarkStatus ?: "готово") +
                                " (меньше ошибок — лучше)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (actionModelId == model.id && actionError != null) {
                        Text(
                            requireNotNull(actionError),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Text("Определение речи", style = MaterialTheme.typography.titleLarge)
            Text(
                "Silero VAD пропускает тишину и помогает избежать выдуманных фраз.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(catalog.filter { it.kind == ModelKind.VAD }, key = { "vad-" + it.id }) { model ->
            val saved = installed.firstOrNull { it.descriptorId == model.id }
            val download = downloads.firstOrNull { it.modelId == model.id }
            val busy = download?.status?.name in setOf("QUEUED", "DOWNLOADING", "VERIFYING")
            Card(shape = localScribeShape, colors = localScribeCardColors()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            saved != null -> "Установлена"
                            download?.status?.name == "QUEUED" -> "В очереди"
                            download?.status?.name == "DOWNLOADING" -> "Скачивание…"
                            download?.status?.name == "VERIFYING" -> "Проверка файла…"
                            download?.status?.name == "FAILED" -> "Ошибка загрузки"
                            else -> "Не установлена"
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (saved == null && !busy) {
                            IconButton(onClick = {
                                actionModelId = model.id
                                actionError = null
                                scope.launch {
                                    runCatching {
                                        if (download?.status?.name in
                                            setOf("FAILED", "CANCELLED")
                                        ) {
                                            graph.downloads.retry(model.id)
                                        } else {
                                            graph.downloads.enqueue(model.id)
                                        }
                                    }.onFailure {
                                        actionError =
                                            "Не удалось скачать VAD. Проверьте сеть и повторите."
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Default.FileDownload,
                                    contentDescription = "Скачать VAD",
                                )
                            }
                        }
                        if (busy) {
                            IconButton(onClick = {
                                scope.launch { graph.downloads.cancel(model.id) }
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Отменить загрузку VAD",
                                )
                            }
                        }
                        if (saved != null) {
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { graph.modelFiles.delete(model.id) }
                                        .onFailure { actionError = "VAD используется задачей." }
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить VAD")
                            }
                        }
                    }
                    if (busy) {
                        LocalScribeProgress(
                            if (download?.status?.name == "DOWNLOADING") {
                                download.downloadedBytes.toFloat() /
                                    download.totalBytes.coerceAtLeast(1L).toFloat()
                            } else {
                                null
                            },
                        )
                    }
                    if (actionModelId == model.id && actionError != null) {
                        Text(requireNotNull(actionError), color = MaterialTheme.colorScheme.error)
                    }
                }
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
        item { Text("Внешний вид", style = MaterialTheme.typography.headlineMedium) }
        item {
            PreferenceSwitch("Цвета системы", dynamic) {
                dynamic = it
                preferences.dynamicColors =
                    it
                setDynamicColors(it)
            }
        }
        item {
            Text("Тема")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppearanceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = appearanceMode == mode,
                        onClick = {
                            appearanceMode = mode
                            preferences.appearanceMode = mode.name
                            setAppearanceMode(mode)
                        },
                        label = {
                            Text(
                                when (mode) {
                                    AppearanceMode.SYSTEM -> "Системная"
                                    AppearanceMode.LIGHT -> "Светлая"
                                    AppearanceMode.DARK -> "Тёмная"
                                },
                            )
                        },
                    )
                }
            }
        }
        item { Text("Обновления", style = MaterialTheme.typography.titleLarge) }
        item {
            PreferenceSwitch("Проверять обновления автоматически", automatic) {
                automatic = it
                preferences.automaticUpdates = it
            }
        }
        item {
            Text(
                "Версия " + BuildConfig.VERSION_NAME + " · " +
                    when (update.availability) {
                        UpdateAvailability.FAILED -> "Ошибка проверки"
                        UpdateAvailability.AVAILABLE -> "Есть обновление"
                        UpdateAvailability.UP_TO_DATE -> "Последняя версия"
                        else -> "Не проверено"
                    },
            )
        }
        update.lastCheckedAtEpochMs?.let { checked ->
            item {
                Text(
                    "Последняя проверка: " + formatLocalDate(checked),
                )
            }
        }
        update.failure?.let {
            item {
                Text(
                    "Не удалось получить сведения о релизе. Проверьте сеть или попробуйте позже.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Button(onClick = {
                scope.launch {
                    graph.updates.checkForUpdate(AppVersion(BuildConfig.VERSION_NAME), force = true)
                }
            }) { Text("Проверить сейчас") }
        }
        presentation.releaseNotes?.let { notes -> item { Text(notes) } }
        if (presentation.canDownload) {
            item {
                Button(onClick = {
                    scope.launch { graph.updates.downloadAvailableUpdate() }
                }) { Text("Скачать обновление") }
            }
        }
        if (updateDownload.status.name != "IDLE") {
            item {
                Text(
                    when (updateDownload.status.name) {
                        "DOWNLOADING" -> "Скачивание обновления"
                        "VERIFYING" -> "Проверка обновления"
                        "READY_TO_INSTALL" -> "Обновление готово к установке"
                        "FAILED" -> "Не удалось загрузить обновление"
                        "CANCELLED" -> "Загрузка отменена"
                        else -> "Подготовка обновления"
                    },
                )
                if (updateDownload.status.name == "DOWNLOADING") {
                    LocalScribeProgress(
                        updateDownload.downloadedBytes.toFloat() /
                            updateDownload.totalBytes.coerceAtLeast(1L).toFloat(),
                    )
                }
            }
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
                }) { Text("Установить обновление") }
            }
        }
        installReturnMessage?.let { item { Text(it) } }
        item {
            Text(
                "Конфиденциальность: записи и тексты остаются на телефоне. " +
                    "Облачная транскрибация и аналитика не используются.",
            )
        }
        item {
            Text(
                "Whisper.cpp распространяется с приложенной лицензией. " +
                    "Сведения о тестовых аудиофайлах доступны в документации.",
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
