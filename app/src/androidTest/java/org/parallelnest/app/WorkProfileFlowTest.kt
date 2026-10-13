package org.parallelnest.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.parallelnest.app.core.CloneInfo
import java.io.File

/** Disposable-emulator test. All admin consent is clicked in the OS UI, never auto-approved by the application. */
class WorkProfileFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val nest get() = context.applicationContext as NestApplication
    private fun click(text: String, timeout: Long = 15_000) {
        val node = device.wait(Until.findObject(By.text(text)), timeout)
        assertNotNull("Missing UI action: $text", node)
        node.click()
    }
    private fun startManager() { device.executeShellCommand("am start --user 0 -n org.parallelnest.app/.MainActivity"); device.wait(Until.hasObject(By.text("ParallelNest")),15_000) }
    private fun saveProbe(value: String) {
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")),15_000)
        assertNotNull(input)
        input.text = value
        click("Save value")
        assertTrue(device.wait(Until.hasObject(By.text("Saved: $value")),10_000))
        if(device.hasObject(By.pkg("com.google.android.inputmethod.latin")))device.pressBack()
    }
    private fun screenshot(name: String) {
        val folder = File(context.getExternalFilesDir(null),"evidence").apply { mkdirs() }
        device.takeScreenshot(File(folder,"$name.png"))
    }
    private suspend fun awaitConsent(result: Deferred<*>) {
        val deadline = System.currentTimeMillis()+120_000
        while(!result.isCompleted && System.currentTimeMillis()<deadline) {
            device.findObject(By.text("Install"))?.click()
            device.findObject(By.text("Settings"))?.takeIf { device.hasObject(By.textContains("not allowed")) }?.click()
            device.findObject(By.text("Allow from this source"))?.let { label -> label.click(); device.pressBack() }
            device.findObject(By.text("OK"))?.click()
            delay(300)
        }
        check(result.isCompleted) { "Operation did not complete after system consent" }
    }
    @Test fun createLaunchAndSeparatePersistentValues() = runBlocking {
        assumeTrue("Full profile provisioning is tested on API 36",Build.VERSION.SDK_INT==36)
        assumeTrue("Only requested by disposable-emulator CI",InstrumentationRegistry.getArguments().getString("profileFlow") == "true")
        val probe=context.packageManager.getApplicationInfo("org.parallelnest.probe",0)
        assertTrue("Only first-party debuggable fixture may be inspected",probe.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        device.executeShellCommand("am start --user 0 -n org.parallelnest.probe/.ProbeActivity")
        saveProbe("PERSONAL_SENTINEL_2026")
        screenshot("personal-before")
        startManager()
        if(device.hasObject(By.text("Continue")))click("Continue")
        if(device.hasObject(By.text("Set up / resume work profile")))click("Set up / resume work profile")
        val checkbox=device.wait(Until.findObject(By.clazz("android.widget.CheckBox")),15_000)
        assertNotNull("Provisioning explanation/consent checkbox",checkbox)
        checkbox.click()
        click("Create work profile")
        click("Accept & continue",30_000)
        screenshot("android-profile-consent")
        click("Next",120_000)
        val deadline=System.currentTimeMillis()+30_000
        while(!nest.engine.status().bridgeAvailable && System.currentTimeMillis()<deadline)delay(300)
        assertTrue(nest.engine.status().description,nest.engine.status().bridgeAvailable)
        startManager()
        val app=nest.engine.listSupportedApps().first { it.packageName=="org.parallelnest.probe" }
        val created=async(Dispatchers.Default){nest.engine.createInstance(app,"Probe · Work")}
        awaitConsent(created)
        assertTrue(created.await().toString(),created.await().success)
        val row=nest.engine.getInstances().single { it.packageName==app.packageName }
        startManager()
        assertTrue(nest.engine.launchInstance(row).success)
        assertTrue(device.wait(Until.hasObject(By.text("WORK PROFILE")),15_000))
        saveProbe("WORK_SENTINEL_2026")
        screenshot("work-after")
        val workUser=Regex("UserInfo\\{(\\d+):ParallelNest:").find(device.executeShellCommand("pm list users"))!!.groupValues[1]
        val personal=device.executeShellCommand("run-as org.parallelnest.probe --user 0 cat shared_prefs/probe.xml")
        val work=device.executeShellCommand("run-as org.parallelnest.probe --user $workUser cat shared_prefs/probe.xml")
        assertTrue(personal,personal.contains("PERSONAL_SENTINEL_2026"))
        assertFalse(personal.contains("WORK_SENTINEL_2026"))
        assertTrue(work,work.contains("WORK_SENTINEL_2026"))
        assertFalse(work.contains("PERSONAL_SENTINEL_2026"))
        startManager()
        assertTrue(nest.engine.stopInstance(row).success)
        assertTrue(nest.engine.getInstances().single { it.id==row.id }.suspended)
        startManager()
        assertTrue(nest.engine.launchInstance(row).success)
        assertTrue(device.wait(Until.hasObject(By.text("Saved: WORK_SENTINEL_2026")),15_000))
        println("ISOLATION_PASS serial=${row.profile} personal=PERSONAL_SENTINEL_2026 work=WORK_SENTINEL_2026 suspension/resume=true")
    }
}
