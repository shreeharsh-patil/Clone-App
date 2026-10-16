package org.parallelnest.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Regression coverage for removal of the old provisioning flow. */
class WorkProfileFlowTest {
    @Test fun newEngineHasNoManagedProfileProvisioningRoute() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nest = context.applicationContext as NestApplication
        assertEquals("virtual", nest.engine.status().type)
        assertTrue(nest.engine.status().supportsMultipleInstances)
        val manifest = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        assertFalse(manifest.activities.orEmpty().any { it.name.endsWith(".ProvisionActivity") })
        assertTrue(context.packageManager.queryIntentActivities(Intent("android.app.action.PROVISION_MANAGED_PROFILE").setPackage(context.packageName), PackageManager.ResolveInfoFlags.of(0)).isEmpty())
        val legacy = nest.registry.snapshot().filter { it.engine=="work_profile" }
        nest.engine.getInstances()
        assertEquals(legacy, nest.registry.snapshot().filter { it.engine=="work_profile" })
    }
}
