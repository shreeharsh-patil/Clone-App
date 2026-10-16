package org.parallelnest.app

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.core.*
import org.parallelnest.app.engine.virtualization.*
import java.io.File

internal class GuestTestDriver {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val nest get() = context.applicationContext as NestApplication
    val device = UiDevice.getInstance(instrumentation)
    fun visible() { assertTrue("Guest APK's own UI must be visible", device.wait(Until.hasObject(By.text("Actual Guest Probe Activity")), 15000)) }
    fun save(value: String) {
        android.util.Log.i("GuestTest", "save $value: waiting for UI")
        visible()
        val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000) ?: error("Missing guest input")
        android.util.Log.i("GuestTest", "save $value: setting text")
        input.text = value
        android.util.Log.i("GuestTest", "save $value: finding button")
        val save = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Save guest value", java.util.regex.Pattern.CASE_INSENSITIVE))), 5000) ?: error("Missing guest save button")
        save.click()
        android.util.Log.i("GuestTest", "save $value: clicked")
        assertTrue(device.wait(Until.hasObject(By.text("Saved: $value")), 10000))
        device.pressBack() // hide IME, if present; lifecycle is also checked by stop below
        android.util.Log.i("GuestTest", "save $value: done")
    }
    fun assertStored(row: CloneInfo, value: String) {
        val root = GuestStorage(context).instance(row.id)
        assertEquals(value, root.resolve("files/value.txt").readText())
        assertEquals(value, JSONObject(root.resolve("preferences/guest_probe.json").readText()).getJSONObject("value").getString("value"))
        SQLiteDatabase.openDatabase(root.resolve("databases/probe.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM probe WHERE id=1", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(value, cursor.getString(0)) }
        }
        val lifecycle = JSONObject(root.resolve("preferences/guest_probe.json").readText()).getJSONObject("lifecycle").getString("value")
        listOf("create;", "start;", "resume;", "pause;", "stop;", "destroy;").forEach { assertTrue("Missing $it: $lifecycle", lifecycle.contains(it)) }
    }
    fun original() = device.executeShellCommand("run-as org.parallelnest.probe cat shared_prefs/guest_probe.xml")
    fun evidence(name: String) {
        val folder = File(context.getExternalFilesDir(null), "virtual-evidence").apply { mkdirs() }
        device.takeScreenshot(File(folder, "$name.png"))
    }
}

class VirtualRuntimeTest {
    @Test(timeout=120_000) fun actualGuestMultiInstanceStorageAndOriginalIsolation() = runBlocking {
        val test = GuestTestDriver()
        val engine = test.nest.engine
        assertEquals("virtual", engine.status().type)
        val beforeUsers = test.device.executeShellCommand("pm list users")
        // Explicitly operate on the standalone original once, then require its bytes to stay unchanged.
        test.context.startActivity(Intent().setClassName(GuestPackageManager.PROBE, GuestPackageManager.ENTRY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        test.save("ORIGINAL_SENTINEL")
        // Wait for a quiescent standalone fixture before byte-for-byte comparisons.
        // Its own final stop/destroy callbacks can otherwise race the baseline read.
        test.device.executeShellCommand("am force-stop org.parallelnest.probe")
        val originalBefore = test.original()
        assertTrue(originalBefore.contains("ORIGINAL_SENTINEL"))
        val app = engine.listSupportedApps().single { it.packageName==GuestPackageManager.PROBE }
        assertTrue(app.reason, app.supported)
        val first = engine.createInstance(app, "Probe one")
        assertTrue(first.message, first.success)
        test.save("INSTANCE_ONE")
        val one = engine.getInstances().single { it.id==first.instanceId }
        assertTrue(engine.stopInstance(one).success)
        val second = engine.createInstance(app, "Probe two")
        assertTrue(second.message, second.success)
        test.save("INSTANCE_TWO")
        val two = engine.getInstances().single { it.id==second.instanceId }
        assertTrue(engine.stopInstance(two).success)
        assertNotEquals(one.id, two.id)
        assertNotEquals(one.virtualUser, two.virtualUser)
        assertNotEquals(one.number, two.number)
        test.assertStored(one, "INSTANCE_ONE"); test.assertStored(two, "INSTANCE_TWO")
        assertEquals(originalBefore, test.original())
        assertEquals(beforeUsers, test.device.executeShellCommand("pm list users"))
        assertTrue(engine.launchInstance(one).success)
        test.visible()
        assertTrue(test.device.wait(Until.hasObject(By.text("Saved: INSTANCE_ONE")), 10000))
        test.evidence("instance-one")
        assertTrue(engine.stopInstance(one).success)
        assertTrue(engine.launchInstance(two).success)
        assertTrue(test.device.wait(Until.hasObject(By.text("Saved: INSTANCE_TWO")), 10000))
        test.evidence("instance-two")
        assertTrue(engine.stopInstance(two).success)
        GuestStorage(test.context).instance(one.id).resolve("files/crash-once").writeText("one failure")
        val failed = engine.launchInstance(one)
        assertFalse("A throwing guest Activity must not report successful launch", failed.success)
        assertTrue(failed.message, failed.message.contains("Intentional first-party"))
        assertEquals("failed", engine.getInstances().single { it.id==one.id }.runtime)
        assertTrue("Guest must recover on an explicit retry", engine.launchInstance(one).success)
        assertTrue(test.device.wait(Until.hasObject(By.text("Saved: INSTANCE_ONE")),10000))
        assertTrue(engine.stopInstance(one).success)
        assertTrue(engine.updateInstance(one).success)
        assertTrue(test.device.wait(Until.hasObject(By.text("Saved: INSTANCE_ONE")), 10000))
        assertTrue(engine.stopInstance(one).success)
        val temporary = engine.createInstance(app, "Delete this guest")
        assertTrue(temporary.message, temporary.success)
        val deleting = engine.getInstances().single { it.id==temporary.instanceId }
        assertTrue(engine.stopInstance(deleting).success)
        val metadataFile = GuestStorage(test.context).instance(deleting.id).resolve("package.json")
        metadataFile.writeText(JSONObject(metadataFile.readText()).put("sha256","0".repeat(64)).toString())
        val corrupt = engine.launchInstance(deleting)
        assertFalse("A corrupt guest archive must never launch", corrupt.success)
        assertTrue(corrupt.message, corrupt.message.contains("verification failed"))
        assertEquals("failed",engine.getInstances().single { it.id==deleting.id }.runtime)
        assertTrue(engine.deleteInstance(deleting).success)
        assertFalse(GuestStorage(test.context).instance(deleting.id).exists())
        test.assertStored(one, "INSTANCE_ONE"); test.assertStored(two, "INSTANCE_TWO")
        assertEquals(originalBefore, test.original())
        val count = engine.getInstances().size
        val rejected = runCatching { engine.createInstance(app.copy(packageName="com.android.settings"), "Unsupported") }
        assertTrue(rejected.isFailure)
        assertEquals(count, engine.getInstances().size)
        File(test.context.getExternalFilesDir(null), "virtual-evidence/instances.json").writeText(RegistryCodec.encode(listOf(one,two)))
        println("VIRTUAL_RUNTIME_PASS: actual APK Activity, two subprocess slots, distinct preferences/files/SQLite, original unchanged, selective deletion, update without data loss, no Android user created")
    }
}

/** Invoked in a fresh instrumentation process after force-stop/reboot/APK replacement. */
class VirtualRecoveryTest {
    @Test fun existingGuestInstancesRetainTheirOwnData() = runBlocking {
        val test = GuestTestDriver()
        val engine = test.nest.engine
        val saved = RegistryCodec.decode(File(test.context.getExternalFilesDir(null), "virtual-evidence/instances.json").readText())
        val actual = engine.getInstances()
        assertTrue(test.original().contains("ORIGINAL_SENTINEL"))
        saved.forEachIndexed { i, row ->
            val value = if(i==0)"INSTANCE_ONE" else "INSTANCE_TWO"
            val current = actual.single { it.id==row.id }
            test.assertStored(current, value)
            assertTrue(engine.launchInstance(current).success)
            assertTrue(test.device.wait(Until.hasObject(By.text("Saved: $value")), 15000))
            assertTrue(engine.stopInstance(current).success)
        }
        println("VIRTUAL_RECOVERY_PASS: ${saved.size} real guest Activities resumed with independent persistent data")
    }
}

class VirtualGuestUpdateTest {
    @Test fun newerInstalledProbeUpdatesBothGuestsWithoutLosingData() = runBlocking {
        val test = GuestTestDriver()
        val saved = RegistryCodec.decode(File(test.context.getExternalFilesDir(null), "virtual-evidence/instances.json").readText())
        val installed = GuestPackageManager(test.context).installed(GuestPackageManager.PROBE).longVersionCode
        val original = test.original()
        saved.forEachIndexed { i, fixture ->
            val row = test.nest.engine.getInstances().single { it.id==fixture.id }
            assertTrue("Install a newer probe APK before this test", installed > row.versionCode)
            val result = test.nest.engine.updateInstance(row)
            assertTrue(result.message,result.success)
            val value = if(i==0)"INSTANCE_ONE" else "INSTANCE_TWO"
            assertTrue(test.device.wait(Until.hasObject(By.text("Saved: $value")),10000))
            assertTrue(test.nest.engine.stopInstance(row).success)
            val updated = test.nest.engine.getInstances().single { it.id==row.id }
            assertEquals(installed,updated.versionCode)
            test.assertStored(updated,value)
        }
        assertEquals(original,test.original())
    }
}
