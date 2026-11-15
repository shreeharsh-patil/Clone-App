package org.parallelnest.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.engine.virtualization.GuestPackageManager
import org.parallelnest.app.engine.virtualization.VirtualContainerEngine

class GuestBluetoothConsentTest {
    @Test fun hostGrantDoesNotAuthorizeADifferentProbeInstance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as NestApplication
        val engine = app.engine as VirtualContainerEngine
        val device = UiDevice.getInstance(instrumentation)
        val probe = engine.listSupportedApps().single { it.packageName == GuestPackageManager.PROBE && it.supported }
        val created = mutableListOf<String>()

        // Tests run on a disposable emulator. This supplies the real host grants without selecting hardware.
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
        try {
            for (name in listOf("Bluetooth test A", "Bluetooth test B")) {
                val result = engine.createInstance(probe, name)
                assertTrue(result.message, result.success)
                created += requireNotNull(result.instanceId)
            }
            val rows = engine.getInstances().associateBy { it.id }
            val first = rows.getValue(created[0])
            val second = rows.getValue(created[1])
            assertEquals(false, engine.bluetoothConsent(first))
            assertEquals(false, engine.bluetoothConsent(second))
            assertTrue(engine.setBluetoothConsent(first, true).success)
            assertEquals(true, engine.bluetoothConsent(first))
            assertEquals(false, engine.bluetoothConsent(second))
            assertTrue(engine.launchInstance(second).success)
            val denial = "Enable Bluetooth for this instance in Clone Details"
            val scan = device.wait(Until.findObject(By.text("Run BLE scan")), 10_000)
            assertNotNull("The BLE control must run inside the actual guest Activity", scan)
            scan.click()
            assertTrue("Unconsented guest must not enter the Bluetooth path",
                device.wait(Until.hasObject(By.textContains(denial)), 5_000))
            assertTrue(engine.setBluetoothConsent(first, false).success)
            assertEquals(false, engine.bluetoothConsent(first))
        } finally {
            for (row in engine.getInstances().filter { it.id in created }) {
                assertTrue(engine.deleteInstance(row).success)
            }
        }
    }
}
