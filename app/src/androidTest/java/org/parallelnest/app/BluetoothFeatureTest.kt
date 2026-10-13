package org.parallelnest.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.bluetooth.fileShareIntent

class BluetoothFeatureTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @SuppressLint("MissingPermission")
    @Test fun managerRequestsNearbyConsentAndStopsItsScanWhenLeaving() {
        device.executeShellCommand("am start --user 0 -n org.parallelnest.app/.MainActivity")
        val open=device.wait(Until.findObject(By.text("Bluetooth")),15_000)
        assertNotNull("Bluetooth is available without creating a work profile",open)
        open.click()
        assertTrue(device.wait(Until.hasObject(By.text("Send a file")),10_000))
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter
        if(adapter==null) {
            assertTrue(device.hasObject(By.text("Bluetooth unavailable on this device")))
            device.pressBack();return
        }
        device.wait(Until.findObject(By.text("Allow Nearby devices")),3_000)?.let {
            it.click()
            val allow=device.wait(Until.findObject(By.text("Allow")),10_000)
            assertNotNull("OS Nearby devices consent",allow)
            allow.click()
        }
        assertTrue(device.wait(Until.hasObject(By.text(if(adapter.isEnabled) "Bluetooth is on" else "Bluetooth is off")),10_000))
        listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN).forEach {
            assertEquals(it,PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(it))
        }
        if(adapter.isEnabled) {
            device.wait(Until.findObject(By.text("Scan for devices")),5_000)!!.click()
            val stop=device.wait(Until.findObject(By.text("Stop scan")),3_000)
            if(stop!=null) {
                device.pressBack()
                val deadline=System.currentTimeMillis()+5_000
                while(adapter.isDiscovering && System.currentTimeMillis()<deadline)Thread.sleep(100)
                assertFalse("Leaving Bluetooth screen must cancel its own scan",adapter.isDiscovering)
                println("MANAGER_BLUETOOTH_PASS: Nearby devices consent granted; real discovery cancelled on navigation")
            } else {
                assertTrue("A rejected/finished scan must be reported",device.hasObject(By.textContains("Scan finished")) || device.hasObject(By.textContains("Android could not start discovery")))
                device.pressBack()
                println("MANAGER_BLUETOOTH_PASS: Nearby consent granted; emulator discovery unavailable/finished")
            }
        } else {
            assertTrue(device.hasObject(By.text("Turn on Bluetooth")))
            device.pressBack()
            println("MANAGER_BLUETOOTH_PASS: Nearby consent granted; system enable action available")
        }
    }
    @Test fun fileSharingDelegatesOnlySelectedContentWithTemporaryReadAccess() {
        val uri=Uri.parse("content://org.example.documents/document/selected")
        val intent=fileShareIntent(uri,"text/plain")
        assertEquals(Intent.ACTION_SEND,intent.action)
        assertEquals("text/plain",intent.type)
        assertEquals(uri,intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java))
        assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags)
        assertThrows(IllegalArgumentException::class.java) { fileShareIntent(Uri.parse("file:///data/user/0/private.txt"),"text/plain") }
    }
}
