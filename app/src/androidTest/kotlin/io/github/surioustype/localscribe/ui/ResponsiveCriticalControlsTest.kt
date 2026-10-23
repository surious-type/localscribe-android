package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.surioustype.localscribe.core.model.AudioSource
import io.github.surioustype.localscribe.core.model.AudioSourceRecord
import io.github.surioustype.localscribe.core.model.SourceAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ResponsiveCriticalControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun phoneLargeFontKeepsImportAndStartActionsReachable() {
        var pressed = ""
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale = 1.8f),
            ) {
                Box(Modifier.width(320.dp)) {
                    ResponsiveActionButtons(listOf("Import file", "Start transcription")) {
                        pressed =
                            it
                    }
                }
            }
        }

        rule.onNodeWithContentDescription("Start transcription").assertIsDisplayed().performClick()
        assertEquals("Start transcription", pressed)
    }

    @Test
    fun largeFontKeepsEveryChunkWindowChoiceReachable() {
        var selected = 20
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale = 1.8f),
            ) {
                Box(Modifier.width(280.dp)) {
                    ChunkWindowSelector(selected) { selected = it }
                }
            }
        }

        rule.onNodeWithText("30 с").assertIsDisplayed().performClick()
        assertEquals(30, selected)
        rule.onNodeWithText("10 с").assertIsDisplayed()
    }

    @Test
    fun smallScreenLargeFontKeepsSettingsToggleReachable() {
        var enabled = false
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale = 2f),
            ) {
                Box(Modifier.width(280.dp)) {
                    PreferenceSwitch(
                        "Проверять обновления автоматически",
                        enabled,
                        "Проверка GitHub Releases без отправки записей.",
                    ) { enabled = it }
                }
            }
        }
        rule.onNodeWithText("Проверять обновления автоматически").assertIsDisplayed()
        rule.onNodeWithText("Проверка GitHub Releases без отправки записей.").assertIsDisplayed()
    }

    @Test
    fun narrowLargeFontRecordingStillExposesActionsAndStatus() {
        var renameRequested = false
        val record =
            AudioSourceRecord(
                source = AudioSource("id", "content://audio/1", "Лекция", 120_000L, "audio/wav"),
                accessStatus = SourceAccessStatus.AVAILABLE,
                hasPersistedPermission = false,
            )
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale = 1.8f),
            ) {
                Box(Modifier.width(300.dp)) {
                    LibraryRecordingCard(
                        record = record,
                        presentation = libraryRecordPresentation(record, emptyList()),
                        hasTranscript = false,
                        onOpen = {},
                        onRename = { renameRequested = true },
                        onRetranscribe = {},
                    )
                }
            }
        }
        rule.onNodeWithText("Без расшифровки").assertIsDisplayed()
        rule.onNodeWithContentDescription("Действия с записью Лекция").performClick()
        rule.onNodeWithText("Переименовать").assertIsDisplayed().performClick()
        assertTrue(renameRequested)
    }

    @Test
    fun actionableEmptyLibraryCanStartImportOnSmallScreen() {
        var importRequested = false
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale = 1.8f),
            ) {
                Box(Modifier.width(300.dp)) {
                    LibraryEmptyState(
                        title = "Записей пока нет",
                        description = "Добавьте аудиофайл.",
                        actionLabel = "Импортировать аудио",
                        onAction = { importRequested = true },
                    )
                }
            }
        }
        rule.onNodeWithText("Импортировать аудио").assertIsDisplayed().performClick()
        assertTrue(importRequested)
    }

    @Test
    fun tabletWidthKeepsImportAndStartActionsReachable() {
        rule.setContent {
            Box(Modifier.width(900.dp)) {
                ResponsiveActionButtons(listOf("Import file", "Start transcription")) {}
            }
        }

        rule.onNodeWithContentDescription("Start transcription").assertIsDisplayed()
        rule.onNodeWithContentDescription("Import file").assertIsDisplayed()
    }
}
