package io.github.surioustype.localscribe.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
        rule.onNodeWithText("Your recordings stay on this device").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("LocalScribe").assertIsDisplayed()
    }

    @Test fun navigationShowsModelsAndSettings() {
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Models").performClick()
        rule
            .onNodeWithText(
                "Models stay outside the APK and download only when you request them.",
            ).assertIsDisplayed()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Automatic update checks").assertIsDisplayed()
    }
}
