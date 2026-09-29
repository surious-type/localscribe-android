package io.github.surioustype.localscribe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
