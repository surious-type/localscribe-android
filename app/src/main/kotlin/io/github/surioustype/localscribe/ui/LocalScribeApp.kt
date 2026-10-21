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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.github.surioustype.localscribe.benchmark.BenchmarkStep
import io.github.surioustype.localscribe.core.domain.ChunkPlanner
import io.github.surioustype.localscribe.core.domain.ChunkWindowProfiles
import io.github.surioustype.localscribe.core.domain.TranscriptAssembler
import io.github.surioustype.localscribe.core.model.AppVersion
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
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
import kotlinx.coroutines.CancellationException
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
        val processing = savedUi.processingVisible || destination == Destination.PROCESSING
        val detail =
            savedUi.transcriptJobId != null || savedUi.selectedSourceId != null || processing
        val backgroundJobs by graph.transcriptionRepository
            .observeJobs()
            .collectAsStateWithLifecycle(emptyList())
        val activeJob =
            backgroundJobs.firstOrNull { it.status == JobStatus.RUNNING }
                ?: backgroundJobs.firstOrNull {
                    it.status == JobStatus.PENDING || it.status == JobStatus.PAUSED
                }
        BackHandler(enabled = uiModel.canNavigateBack()) {
            uiModel.navigateBack()
        }
        val title =
            when {
                savedUi.transcriptJobId != null -> "Транскрипция"
                savedUi.selectedSourceId != null -> "Настройка транскрибации"
                destination == Destination.HOME -> "LocalScribe"
                processing -> "Очередь обработки"
                destination == Destination.MODELS -> "Модели"
                else -> "Настройки"
            }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (detail) {
                    TopAppBar(
                        title = { Text(title, fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            IconButton(onClick = uiModel::navigateBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                            }
                        },
                        colors =
                            androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                            ),
                    )
                }
            },
            bottomBar = {
                if (!detail) {
                    Column {
                        if (activeJob != null) {
                            Card(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                        .clickable { uiModel.showProcessing(true) }
                                        .semantics {
                                            contentDescription = "Открыть очередь обработки"
                                        },
                                colors = localScribeCardColors(),
                                shape = localScribeShape,
                            ) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("Обработка · " + localizedJobStatus(activeJob.status.name))
                                    Text(
                                        "Модель: " + activeJob.config.modelId,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Нажмите, чтобы открыть очередь",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                            Destination.entries
                                .filter { it != Destination.PROCESSING }
                                .forEach { item ->
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
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = 900.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    when {
                    savedUi.transcriptJobId != null -> {
                        val selectedJobId = requireNotNull(savedUi.transcriptJobId)
                        key(selectedJobId) {
                            TranscriptScreen(
                                selectedJobId,
                                graph,
                                savedUi.transcriptQuery,
                                savedUi.transcriptSeekToMs,
                                savedUi.exportFormat,
                                uiModel::setTranscriptQuery,
                                uiModel::setExportFormat,
                                uiModel::completeTranscriptExport,
                                uiModel::setStatusMessage,
                                onChooseVersion = uiModel::showTranscript,
                                onRetranscribe = uiModel::retranscribe,
                                close = { uiModel.showTranscript(null) },
                            )
                        }
                    }
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
                    processing -> Processing(graph, uiModel)
                    destination == Destination.HOME -> Home(graph, receivedUri, uiModel, savedUi)
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
    val preferences = remember { AppPreferences(context) }
    val sources by graph.audioRepository.observeSources().collectAsStateWithLifecycle(emptyList())
    val jobs by graph.transcriptionRepository.observeJobs().collectAsStateWithLifecycle(emptyList())
    val searchSegments by graph.transcriptSearch.collectAsStateWithLifecycle(emptyList())
    var filter by remember { mutableStateOf(preferences.libraryFilter) }
    var sort by remember { mutableStateOf(preferences.librarySort) }
    var renameTarget by remember { mutableStateOf<AudioSourceRecord?>(null) }
    var renameText by remember { mutableStateOf("") }
    val query = savedUi.libraryQuery
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                scope.launch {
                    runCatching { graph.audioRepository.importSource(it.toString(), true) }
                        .onSuccess { uiModel.setStatusMessage("Запись добавлена") }
                        .onFailure { uiModel.setStatusMessage("Не удалось импортировать аудио") }
                }
            }
        }
    val mediaPermission =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scope.launch { graph.audioRepository.refresh() }
            } else {
                uiModel.setStatusMessage(
                    "Доступ к библиотеке не предоставлен. Импорт файлов доступен.",
                )
            }
        }
    LaunchedEffect(receivedUri) {
        if (receivedUri != null && uiModel.consumeInboundAudio(receivedUri)) {
            runCatching { graph.audioRepository.importSource(receivedUri, true) }
                .onSuccess { uiModel.setStatusMessage("Полученная запись добавлена") }
                .onFailure { uiModel.setStatusMessage("Не удалось добавить полученное аудио") }
        }
        graph.audioRepository.refresh()
    }
    val filteredSources =
        sources.filter { record ->
            val ready =
                jobs.any {
                    it.sourceId == record.source.id && it.status == JobStatus.COMPLETED
                }
            val active =
                jobs.any {
                    it.sourceId == record.source.id &&
                        it.status in setOf(JobStatus.RUNNING, JobStatus.PENDING, JobStatus.PAUSED)
                }
            when (filter) {
                "ready" -> ready
                "new" -> !ready && !active
                "active" -> active
                else -> true
            }
        }
    val ordered = remember(filteredSources, sort) { sortLibrary(filteredSources, sort) }
    val hits =
        remember(ordered, searchSegments, query) {
            searchLibrary(ordered, searchSegments, query)
        }
    val openRecord: (AudioSourceRecord) -> Unit = { record ->
        val completed = latestCompletedTranscriptionJob(jobs, record.source.id)
        val active =
            jobs.any {
                it.sourceId == record.source.id &&
                    it.status in setOf(JobStatus.RUNNING, JobStatus.PENDING, JobStatus.PAUSED)
            }
        when {
            completed != null -> uiModel.openRecording(record.source.id, completed.id)
            active -> uiModel.showProcessing(true)
            else -> uiModel.openRecording(record.source.id, null)
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Ваши записи", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = uiModel::setLibraryQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск по записям и тексту…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
        }
        item {
            BoxWithConstraints {
                if (maxWidth < 390.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { picker.launch(arrayOf("audio/*")) },
                        ) { Text("Импортировать аудио") }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { permission.launch(mediaPermission) },
                        ) { Text("Показать записи на устройстве") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Text("Импортировать аудио")
                        }
                        OutlinedButton(onClick = { permission.launch(mediaPermission) }) {
                            Text("Показать записи")
                        }
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(
                    listOf(
                        "all" to "Все",
                        "ready" to "С текстом",
                        "new" to "Без текста",
                        "active" to "В обработке",
                    ),
                ) { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = {
                            filter = key
                            preferences.libraryFilter = key
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
                    Text(
                        when (sort) {
                            "recorded" -> "По дате записи"
                            "title" -> "По названию"
                            "long" -> "Сначала длинные"
                            "short" -> "Сначала короткие"
                            else -> "По дате добавления"
                        },
                        Modifier.padding(start = 8.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    listOf(
                        "added" to "По дате добавления",
                        "recorded" to "По дате записи",
                        "title" to "По названию",
                        "long" to "Сначала длинные",
                        "short" to "Сначала короткие",
                    ).forEach { (key, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                sort = key
                                preferences.librarySort = key
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        savedUi.statusMessage?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (query.isNotBlank()) {
            item {
                Text(
                    "Результаты поиска: ${hits.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (hits.isEmpty()) {
                item {
                    LibraryEmptyState(
                        title = "Совпадений нет",
                        description = "Попробуйте другой запрос или покажите все записи.",
                        actionLabel = "Сбросить поиск и фильтр",
                        onAction = {
                            uiModel.setLibraryQuery("")
                            filter = "all"
                            preferences.libraryFilter = "all"
                        },
                    )
                }
            }
            items(hits) { hit ->
                LibrarySearchResultCard(hit) {
                    if (hit.jobId != null && hit.startMs != null) {
                        uiModel.openTranscriptAt(hit.jobId, hit.startMs, query)
                    } else {
                        openRecord(hit.record)
                    }
                }
            }
        } else {
            if (ordered.isEmpty()) {
                item {
                    if (sources.isEmpty()) {
                        LibraryEmptyState(
                            title = "Записей пока нет",
                            description =
                                "Добавьте аудиофайл, чтобы начать локальную расшифровку. " +
                                    "Ваши записи останутся на устройстве.",
                            actionLabel = "Импортировать аудио",
                            onAction = { picker.launch(arrayOf("audio/*")) },
                        )
                    } else {
                        LibraryEmptyState(
                            title = "Нет записей с этим статусом",
                            description = "Попробуйте другой фильтр, чтобы увидеть остальные записи.",
                            actionLabel = "Показать все записи",
                            onAction = {
                                filter = "all"
                                preferences.libraryFilter = "all"
                            },
                        )
                    }
                }
            }
            items(ordered, key = { it.source.id }) { record ->
                val recordJobs = jobs.filter { it.sourceId == record.source.id }
                val presentation = libraryRecordPresentation(record, recordJobs)
                LibraryRecordingCard(
                    record = record,
                    presentation = presentation,
                    hasTranscript = recordJobs.any { it.status == JobStatus.COMPLETED },
                    onOpen = { openRecord(record) },
                    onRename = {
                        renameText = libraryTitle(record)
                        renameTarget = record
                    },
                    onRetranscribe = { uiModel.retranscribe(record.source.id) },
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    renameTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Название записи") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(120) },
                    label = { Text("Новое название") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        renameTarget = null
                        scope.launch {
                            runCatching {
                                graph.audioRepository.renameSource(record.source.id, renameText)
                            }.onSuccess {
                                uiModel.setStatusMessage("Название изменено")
                            }.onFailure {
                                uiModel.setStatusMessage("Не удалось переименовать запись")
                            }
                        }
                    },
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun Processing(graph: AppGraph, uiModel: LocalScribeUiStateViewModel) {
    val context = LocalContext.current
    val jobs by graph.transcriptionRepository.observeJobs().collectAsStateWithLifecycle(emptyList())
    val sources by graph.audioRepository.observeSources().collectAsStateWithLifecycle(emptyList())
    // Completed work stays visible instead of disappearing from the active-only filter.
    val visible = visibleProcessingJobs(jobs)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    var chunkSeconds by rememberSaveable(sourceId) {
        mutableStateOf(ChunkWindowProfiles.DEFAULT_SECONDS)
    }
    val existingJobs by graph.transcriptionRepository
        .observeJobs()
        .collectAsStateWithLifecycle(emptyList())
    val latestCompletedJob = latestCompletedTranscriptionJob(existingJobs, sourceId)
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
                    chunkDurationMs = chunkSeconds * 1_000L,
                    overlapMs = ChunkWindowProfiles.OVERLAP_MS,
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
                        // Keep recording details underneath the transcript for Android Back.
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
        Text("Длина фрагмента", style = MaterialTheme.typography.titleMedium)
        ChunkWindowSelector(chunkSeconds) { chunkSeconds = it }
        Text(
            "Короткие фрагменты чаще обновляют текст, но увеличивают число " +
                "перекрытий и время обработки. Выбор действует только для новых задач.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * Two short chip rows remain tappable on narrow devices and at large font scales.
 * This selection is persisted only inside the new job's immutable chunk plan.
 */
@Composable
internal fun ChunkWindowSelector(
    selectedSeconds: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChunkWindowProfiles.optionsSeconds.chunked(2).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { seconds ->
                    FilterChip(
                        selected = selectedSeconds == seconds,
                        onClick = { onSelect(seconds) },
                        label = { Text("$seconds с") },
                    )
                }
            }
        }
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
                    }.onSuccess { actionMessage = "Доступ восстановлен. Продолжите обработку." }
                        .onFailure { actionMessage = "Не удалось открыть выбранную запись." }
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
    val visibleAudioMs =
        if (job.status == JobStatus.RUNNING) {
            displayedAudioProgressMs(completedDuration, previewSegments, totalDuration)
        } else {
            completedDuration
        }
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
                    if (job.status == JobStatus.RUNNING && visibleAudioMs == 0L) {
                        null
                    } else {
                        visibleAudioMs.toFloat() / totalDuration.toFloat()
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
            val progressLabel =
                if (visibleAudioMs > completedDuration) {
                    "Распознано (предварительно): "
                } else {
                    "Обработано аудио: "
                }
            Text(
                progressLabel + formatAudioDuration(visibleAudioMs) +
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (job.status ==
                    JobStatus.RUNNING
                ) {
                    IconButton(
                        onClick = { scope.launch { graph.coordinator.pause(job.id) } },
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = "Приостановить обработку")
                    }
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
                                    "Разрешите уведомления для управления обработкой в фоне."
                            TranscriptionStartResult.SERVICE_START_FAILED ->
                                actionMessage =
                                    "Не удалось продолжить обработку. Сохранённые части остаются доступными."
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
                                    "Разрешите уведомления для управления обработкой в фоне."
                            }
                        }
                    IconButton(onClick = {
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
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Продолжить")
                    }
                }
                if (job.status in setOf(JobStatus.PENDING, JobStatus.RUNNING, JobStatus.PAUSED)) {
                    IconButton(
                        onClick = { scope.launch { graph.coordinator.cancel(job.id) } },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Остановить обработку")
                    }
                }
                if (job.status in setOf(JobStatus.COMPLETED, JobStatus.RUNNING, JobStatus.PAUSED)) {
                    IconButton(onClick = showTranscript) {
                        Icon(Icons.Default.Description, contentDescription = "Открыть текст")
                    }
                }
            }
            if (job.status == JobStatus.PAUSED &&
                job.failure?.code?.name in
                setOf("SOURCE_PERMISSION_REQUIRED", "SOURCE_MISSING")
            ) {
                Text(
                    "Выберите исходную запись повторно, чтобы сохранить готовые фрагменты.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { relinkAudio.launch(arrayOf("audio/*")) }) {
                    Text("Восстановить доступ к записи")
                }
            }
            actionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun userFacingFailure(code: String): String =
    when (code) {
        "THERMAL_CRITICAL" -> "Телефон нагрелся. Дайте ему остыть и продолжите."
        "SOURCE_PERMISSION_REQUIRED" ->
            "Доступ к аудио утрачен. Выберите файл снова и продолжите."
        "SOURCE_MISSING" -> "Запись недоступна. Выберите другую копию файла."
        "MODEL_CORRUPTED", "MODEL_CHECKSUM_MISMATCH" ->
            "Модель повреждена. Скачайте её заново."
        "TIMEOUT" -> "Android прервал обработку. Откройте приложение и продолжите."
        else -> "Обработка приостановлена. Проверьте запись, модель и свободную память."
    }

@Composable
private fun TranscriptScreen(
    jobId: String,
    graph: AppGraph,
    query: String,
    seekToMs: Long?,
    pendingExportFormat: String?,
    setQuery: (String) -> Unit,
    setExportFormat: (String?) -> Unit,
    completeExport: (Boolean) -> Unit,
    setStatus: (String?) -> Unit,
    onChooseVersion: (String) -> Unit,
    onRetranscribe: (String) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val job by graph.transcriptionRepository.observeJob(jobId).collectAsStateWithLifecycle(null)
    val allJobs by graph.transcriptionRepository
        .observeJobs()
        .collectAsStateWithLifecycle(emptyList())
    val versions = completedTranscriptionVersions(allJobs, job?.sourceId.orEmpty())
    var showVersions by remember { mutableStateOf(false) }
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
    val transcriptListState = rememberLazyListState()
    LaunchedEffect(seekToMs, filtered, versions.size, job?.status) {
        if (seekToMs != null && filtered.isNotEmpty()) {
            val nearest =
                filtered.indices.minByOrNull {
                    kotlin.math.abs(filtered[it].absoluteStartMs - seekToMs)
                } ?: 0
            val versionHeader =
                if (versions.size > 1 ||
                    (versions.isNotEmpty() && job?.status != JobStatus.COMPLETED)
                ) {
                    1
                } else {
                    0
                }
            val rerunButton = if (job?.status == JobStatus.COMPLETED) 1 else 0
            transcriptListState.scrollToItem(nearest + versionHeader + rerunButton + 3)
        }
    }
    var showExport by remember { mutableStateOf(false) }
    LaunchedEffect(sourceUri, seekToMs) {
        sourceUri?.let { player.seekTo(it, seekToMs ?: 0L) }
    }
    LazyColumn(
        state = transcriptListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // A running rerun still lets the user open an earlier completed transcript.
        if (versions.size > 1 || (versions.isNotEmpty() && job?.status != JobStatus.COMPLETED)) {
            item {
                Box {
                    OutlinedButton(onClick = { showVersions = true }) {
                        Text(
                            "Версия: " + (job?.config?.modelId ?: "") + " · " +
                                formatLocalDate(
                                    job?.completedAtEpochMs ?: job?.updatedAtEpochMs ?: 0L,
                                ),
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showVersions,
                        onDismissRequest = { showVersions = false },
                    ) {
                        versions.forEach { version ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        version.config.modelId + " · " +
                                            formatLocalDate(
                                                version.completedAtEpochMs
                                                    ?: version.updatedAtEpochMs,
                                            ),
                                    )
                                },
                                onClick = {
                                    showVersions = false
                                    onChooseVersion(version.id)
                                },
                            )
                        }
                    }
                }
            }
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
        if (job?.status == JobStatus.COMPLETED) {
            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { job?.sourceId?.let(onRetranscribe) },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Распознать заново")
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
    BackHandler(enabled = showExport) { showExport = false }
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

/** Shared widget used for both phone and accessibility-width benchmark screens. */
@Composable
fun ModelBenchmarkRunningStatus(
    stage: BenchmarkStep,
    elapsedMs: Long,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalScribeProgress(null)
        Text(
            when (stage) {
                BenchmarkStep.WAITING_FOR_ENGINE -> "Ожидание свободного движка"
                BenchmarkStep.PREPARING_AUDIO -> "Подготовка тестового аудио"
                BenchmarkStep.LOADING_MODEL -> "Загрузка модели в память"
                BenchmarkStep.RECOGNIZING -> "Распознавание тестовой речи"
                BenchmarkStep.SAVING -> "Сохранение результатов теста"
            } + " · прошло " + formatAudioDuration(elapsedMs),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.semantics { contentDescription = "Отменить тест модели" },
        ) {
            Text("Отменить тест")
        }
    }
}

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
    var actionError by remember { mutableStateOf<String?>(null) }
    val pendingDownloads = uiModel.pendingModelDownloads
    LaunchedEffect(downloads, installed) {
        // As soon as an observable transfer or installed model appears,
        // the durable manager, rather than this short UI latch, owns its state.
        uiModel.reconcileModelDownloadRequests(
            downloads.map { it.modelId }.toSet() +
                installed.map { it.descriptorId }.toSet(),
        )
    }
    var testElapsed by remember { mutableStateOf(controller.elapsedMs()) }
    LaunchedEffect(controller.isBusy) {
        while (controller.isBusy) {
            testElapsed = controller.elapsedMs()
            delay(500)
        }
        testElapsed = controller.elapsedMs()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(catalog.filter { it.kind == ModelKind.TRANSCRIPTION }, key = { it.id }) { model ->
            val saved = installed.firstOrNull { it.descriptorId == model.id }
            val download = downloads.firstOrNull { it.modelId == model.id }
            val transfer =
                modelCardPresentation(
                    installed = saved != null,
                    downloadStatus = download?.status?.name,
                    requestPending = model.id in pendingDownloads,
                )
            val downloading = transfer.busy
            val result =
                benchmarks.firstOrNull {
                    it.modelId == model.id && it.modelHash.equals(saved?.sha256, true)
                }
            val testing = controller.isBusy && controller.activeModelId == model.id
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
                        transfer.statusLabel,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (transfer.canDownload) {
                            IconButton(onClick = {
                                actionModelId = model.id
                                actionError = null
                                uiModel.requestModelDownload(model.id)
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
                                        uiModel.clearModelDownloadRequest(model.id)
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
                        if (saved != null && transfer.canDelete) {
                            IconButton(
                                enabled = !controller.isBusy,
                                onClick = {
                                    actionModelId = model.id
                                    actionError = null
                                    controller.start(model.id) {
                                        try {
                                            // One inference pass yields both speed and WER/CER.
                                            val quality =
                                                graph.benchmark.runQualityDemo(
                                                    saved,
                                                    InferenceConfig(4, "en"),
                                                    "english_jfk_37s",
                                                    AndroidHardwareProfile.read(context),
                                                )
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
                                            controller.rememberQualityResult(model.id)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            actionError = "Не удалось завершить тест модели."
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Default.Science,
                                    contentDescription = "Протестировать скорость и качество",
                                )
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
                        ModelBenchmarkRunningStatus(
                            stage = runState.step,
                            elapsedMs = testElapsed,
                            onCancel = controller::cancel,
                        )
                    }
                    if (result != null) {
                        val speedDescription =
                            if (result.realTimeMultiplier < 1.0) {
                                "медленнее реального времени"
                            } else {
                                "быстрее реального времени"
                            }
                        Text(
                            "Скорость: " + "%.1f".format(result.realTimeMultiplier) +
                                "× · " + speedDescription,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (controller.lastQualityModelId == model.id &&
                        savedUi.qualityResult != null
                    ) {
                        Text(
                            "Точность на английском тестовом аудио: " +
                                (savedUi.benchmarkStatus ?: "тест завершён"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "WER — ошибки в словах, CER — ошибки в символах; " +
                                "чем меньше процент, тем точнее результат.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        item { Text("Подавление тишины", style = MaterialTheme.typography.titleMedium) }
        items(catalog.filter { it.kind == ModelKind.VAD }, key = { "vad-" + it.id }) { model ->
            val saved = installed.firstOrNull { it.descriptorId == model.id }
            val download = downloads.firstOrNull { it.modelId == model.id }
            val transfer =
                modelCardPresentation(
                    installed = saved != null,
                    downloadStatus = download?.status?.name,
                    requestPending = model.id in pendingDownloads,
                )
            val busy = transfer.busy
            Card(shape = localScribeShape, colors = localScribeCardColors()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(transfer.statusLabel)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (transfer.canDownload) {
                            IconButton(onClick = {
                                actionModelId = model.id
                                actionError = null
                                uiModel.requestModelDownload(model.id)
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
                                        uiModel.clearModelDownloadRequest(model.id)
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
                        if (saved != null && transfer.canDelete) {
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
                        if (download?.status?.name == "DOWNLOADING") {
                            Text(
                                (download.downloadedBytes / 1_024).toString() +
                                    " из " + (download.totalBytes / 1_024) + " КБ",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
    LaunchedEffect(automatic) {
        // Reflect toggles immediately; an enabled manager uses its cached check.
        graph.updates.checkForUpdate(AppVersion(BuildConfig.VERSION_NAME), force = false)
    }
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
                        "Разрешение на установку проверено. Нажмите «Установить обновление» " +
                        "для продолжения или отмените установку."
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                PreferenceSwitch(
                    "Цвета системы",
                    dynamic,
                    description =
                        "Использовать цвета обоев Android вместо стандартного оформления.",
                ) {
                    dynamic = it
                    preferences.dynamicColors = it
                    setDynamicColors(it)
                }
            }
        }
        item {
            Text("Тема", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
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
        item {
            PreferenceSwitch(
                "Проверять обновления автоматически",
                automatic,
                description = "Проверка GitHub Releases; файлы записей не отправляются.",
            ) {
                automatic = it
                preferences.automaticUpdates = it
            }
        }
        item {
            Text(
                "Версия " + BuildConfig.VERSION_NAME + " · " +
                    when (update.availability) {
                        UpdateAvailability.FAILED ->
                            if (update.failure?.diagnostic == "release_not_published") {
                                "Релизы пока не опубликованы"
                            } else {
                                "Не удалось проверить"
                            }
                        UpdateAvailability.AVAILABLE -> "Есть обновление"
                        UpdateAvailability.UP_TO_DATE -> "Новых версий нет"
                        UpdateAvailability.CHECKING -> "Проверяем…"
                        UpdateAvailability.DISABLED -> "Автопроверка выключена"
                        else ->
                            if (update.failure?.diagnostic == "release_not_published") {
                                "Релизы пока не опубликованы"
                            } else {
                                "Не проверено"
                            }
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
        if (update.failure != null &&
            update.failure?.diagnostic != "release_not_published"
        ) {
            item {
                Text(
                    "Не удалось проверить обновления. Проверьте соединение и повторите.",
                    style = MaterialTheme.typography.bodySmall,
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
        if (update.availability == UpdateAvailability.AVAILABLE) {
            presentation.releaseNotes?.let { notes -> item { Text(notes, maxLines = 5) } }
        }
        if (presentation.canDownload && !BuildConfig.DEBUG) {
            item {
                Button(onClick = {
                    scope.launch { graph.updates.downloadAvailableUpdate() }
                }) { Text("Скачать обновление") }
            }
        } else if (presentation.canDownload && BuildConfig.DEBUG) {
            item {
                Text(
                    "Это тестовая версия. Для её обновления установите новую тестовую APK.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
    }
}

@Composable
internal fun PreferenceSwitch(
    label: String,
    checked: Boolean,
    description: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) =
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
