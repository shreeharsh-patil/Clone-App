package org.parallelnest.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkProfileFlowTest {
    @Test fun optionalWorkProfileKeepsTheProbeEngineAndProtectsProvisioningCallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nest = context.applicationContext as NestApplication
        assertEquals("virtual", nest.engine.status().type)
        assertTrue(nest.engine.status().supportsMultipleInstances)
        val manifest = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        val callback = manifest.activities.orEmpty().single { it.name.endsWith(".ProvisionActivity") }
        assertEquals("android.permission.BIND_DEVICE_ADMIN", callback.permission)
        val legacy = nest.registry.snapshot().filter { it.engine=="work_profile" }
        nest.engine.getInstances()
        assertEquals(legacy, nest.registry.snapshot().filter { it.engine=="work_profile" })
    }
}
