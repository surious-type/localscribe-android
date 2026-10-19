package io.github.surioustype.localscribe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.surioustype.localscribe.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun resetOnboarding() {
        rule.runOnUiThread {
            rule.activity
                .getSharedPreferences("ui_preferences", 0)
                .edit()
                .clear()
                .commit()
        }
        rule.activityRule.scenario.recreate()
    }

    @Test fun onboardingExplainsOfflinePrivacy() {
        rule.onNodeWithText("Ваши записи остаются на вашем устройстве").assertIsDisplayed()
        rule.onNodeWithText("Начать").performClick()
        rule.onNodeWithText("LocalScribe").assertIsDisplayed()
    }

    @Test fun navigationShowsModelsAndSettings() {
        rule.onNodeWithText("Начать").performClick()
        rule.onNodeWithContentDescription("Модели").performClick()
        rule.onNodeWithText("Локальные модели").assertIsDisplayed()
        rule.onNodeWithContentDescription("Настройки").performClick()
        rule.onNodeWithText("Проверять обновления автоматически").assertIsDisplayed()
        rule.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        rule.onNodeWithText("Локальные модели").assertIsDisplayed()
    }
}
