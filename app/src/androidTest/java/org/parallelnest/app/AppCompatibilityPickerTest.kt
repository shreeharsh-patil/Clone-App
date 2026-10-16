package org.parallelnest.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppCompatibilityPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun unavailableAppExplainsCompatibilityWithoutSelectingOrCreatingIt() {
        val engine=GuestTestDriver().nest.engine
        val blocked=runBlocking { engine.listSupportedApps().first { !it.supported } }
        val before=runBlocking { engine.getInstances().map { it.id }.toSet() }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("add-app")
        compose.onNodeWithText("Add App").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Limited app support").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("No supported apps installed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("installed-apps").performScrollToNode(hasText(blocked.label))
        compose.onNodeWithText(blocked.label).performClick()
        compose.onNodeWithText("Cannot clone ${blocked.label}").assertIsDisplayed()
        compose.onNode(hasText(blocked.reason) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Clone (0)").assertIsNotEnabled()
        assertEquals(before,runBlocking { engine.getInstances().map { it.id }.toSet() })
    }
}
