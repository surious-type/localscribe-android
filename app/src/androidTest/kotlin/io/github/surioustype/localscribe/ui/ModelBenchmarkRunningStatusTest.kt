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
import io.github.surioustype.localscribe.benchmark.BenchmarkStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelBenchmarkRunningStatusTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun benchmarkCancelAccessibleAtPhoneWidthAndLargeFont() {
        var cancelled = 0
        rule.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(1f, fontScale = 1.8f),
            ) {
                Box(Modifier.width(320.dp)) {
                    ModelBenchmarkRunningStatus(BenchmarkStep.RECOGNIZING, 31_000L) {
                        cancelled++
                    }
                }
            }
        }
        rule.onNodeWithText("Распознавание тестовой речи", substring = true)
            .assertIsDisplayed()
        rule.onNodeWithContentDescription("Отменить тест модели")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, cancelled)
    }
}
