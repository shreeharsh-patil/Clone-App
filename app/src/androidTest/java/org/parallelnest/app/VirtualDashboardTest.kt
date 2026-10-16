package org.parallelnest.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VirtualDashboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun addAppCloneAndDashboardLaunchUseTheGuestRuntime() {
        val driver = GuestTestDriver()
        val engine = driver.nest.engine
        val before = runBlocking { engine.getInstances().map { it.id }.toSet() }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("add-app")
        compose.onNodeWithText("Add App").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Clone Apps Probe").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Clone Apps Probe").performClick()
        compose.onNodeWithText("Clone (1)").performClick()
        driver.visible()
        val created = runBlocking {
            var row: org.parallelnest.app.core.CloneInfo? = null
            kotlinx.coroutines.withTimeout(10_000) {
                while(row==null) { row=engine.getInstances().firstOrNull { it.id !in before }; if(row==null)kotlinx.coroutines.delay(50) }
            }
            requireNotNull(row)
        }
        assertTrue(driver.device.hasObject(By.textContains(created.id)))
        assertFalse(driver.device.hasObject(By.text("Create work profile")))
        assertTrue(runBlocking { engine.stopInstance(created).success })
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Open Home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open Home").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(created.id)
        compose.onNodeWithText(created.name).performClick()
        assertTrue(driver.device.wait(Until.hasObject(By.text("Actual Guest Probe Activity")),15_000))
        assertTrue(driver.device.hasObject(By.textContains(created.id)))
        assertTrue(runBlocking { engine.deleteInstance(created).success })
    }
}
