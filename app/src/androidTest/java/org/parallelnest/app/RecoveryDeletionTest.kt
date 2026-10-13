package org.parallelnest.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RecoveryDeletionTest {
    @Test fun deletionRemovesOnlyWorkCopyAfterRecovery() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deleteFlow")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val nest=context.applicationContext as NestApplication
        device.executeShellCommand("am start --user 0 -n org.parallelnest.app/.MainActivity")
        assertTrue(device.wait(Until.hasObject(By.text("Clone Apps")),15_000))
        val row=nest.engine.getInstances().single { it.packageName=="org.parallelnest.probe" }
        val originalBefore=device.executeShellCommand("run-as org.parallelnest.probe --user 0 cat shared_prefs/probe.xml")
        assertTrue(originalBefore.contains("PERSONAL_SENTINEL_2026"))
        val deleting=async(Dispatchers.Default){nest.engine.deleteInstance(row)}
        val deadline=System.currentTimeMillis()+30_000
        while(!deleting.isCompleted && System.currentTimeMillis()<deadline){device.findObject(By.text("OK"))?.click();device.findObject(By.text("Uninstall"))?.click();delay(300)}
        assertTrue(deleting.await().toString(),deleting.await().success)
        assertTrue(nest.engine.getInstances().none { it.packageName==row.packageName })
        assertEquals(originalBefore,device.executeShellCommand("run-as org.parallelnest.probe --user 0 cat shared_prefs/probe.xml"))
        println("DELETION_PASS: work-only uninstall after reboot; original persistent data unchanged")
    }
}
