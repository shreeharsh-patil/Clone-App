package org.parallelnest.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppCompatibilityPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun ordinaryAppOffersSeparateCopyWithoutCreatingItOrProvisioningAutomatically() {
        val driver = GuestTestDriver()
        val engine=driver.nest.engine
        val blocked=runBlocking { engine.listSupportedApps().first { !it.supported } }
        val before=runBlocking { engine.getInstances().map { it.id }.toSet() }
        val usersBefore = driver.device.executeShellCommand("pm list users")
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("add-app")
        compose.onNodeWithText("Add App").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Limited app support").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("No supported apps installed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("installed-apps").performScrollToNode(hasText(blocked.label))
        compose.onNodeWithText(blocked.label).performClick()
        compose.onNodeWithText("${blocked.label} copy options").assertIsDisplayed()
        compose.onNode(hasText(blocked.reason) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clone (0)").assertIsNotEnabled()
        compose.onNodeWithText(blocked.label).performClick()
        compose.onNodeWithText("Install separate copy").performClick()
        compose.onNodeWithText("Separate copy of ${blocked.label}").assertIsDisplayed()
        compose.onNodeWithText("Private Space and phone options").assertIsDisplayed()
        assertEquals(before,runBlocking { engine.getInstances().map { it.id }.toSet() })
        assertEquals(usersBefore, driver.device.executeShellCommand("pm list users"))
    }
}
