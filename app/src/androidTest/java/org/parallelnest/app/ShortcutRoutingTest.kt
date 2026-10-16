package org.parallelnest.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.core.RegistryCodec
import java.io.File

class ShortcutRoutingTest {
    @Test fun consecutiveShortcutRequestsEventuallyOpenTheRequestedSecondGuest() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val rows=RegistryCodec.decode(File(context.getExternalFilesDir(null),"virtual-evidence/instances.json").readText())
        rows.forEach { row ->
            context.startActivity(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("instance",row.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        assertTrue("Second shortcut must not be discarded while the first launch is busy",device.wait(Until.hasObject(By.textContains(rows[1].id)),30000))
        assertTrue(device.hasObject(By.text("Actual Guest Probe Activity")))
        device.pressBack()
    }
}
