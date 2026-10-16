package org.parallelnest.app

import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.engine.virtualization.*
import java.util.UUID

class GuestHostingProofTest {
    @Test fun actualApkActivityExecutesInHostProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = GuestStorage(context)
        val id = UUID.randomUUID().toString()
        val root = storage.prepare(id)
        val packages = GuestPackageManager(context)
        packages.write(root, packages.import(GuestPackageManager.PROBE, root))
        val runtime = GuestRuntimeManager(context)
        try {
            val launched=runtime.launch(id,0)
            assertTrue(launched.message,launched.success)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("Actual guest Activity did not render", device.wait(Until.hasObject(By.text("Actual Guest Probe Activity")), 15000))
            assertNotNull(device.findObject(By.textContains(id)))
            val process = device.executeShellCommand("pidof org.parallelnest.app:guest0").trim()
            assertTrue("Guest must run in its declared host subprocess", process.isNotEmpty())
        } finally {
            val stopped=runtime.stop(id,0)
            assertTrue(stopped.message,stopped.success)
            storage.delete(id)
        }
    }
}
