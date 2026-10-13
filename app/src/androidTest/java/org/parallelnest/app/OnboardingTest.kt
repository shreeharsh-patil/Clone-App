package org.parallelnest.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nativeComposeDashboardOrOnboardingIsVisible() {
        compose.onNodeWithText("Clone Apps").assertIsDisplayed()
        compose.onAllNodesWithText("Settings").fetchSemanticsNodes()
    }
}
