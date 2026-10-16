package org.parallelnest.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.parallelnest.app.engine.virtualization.*
import java.io.File
import java.util.UUID

class VirtualStorageSecurityTest {
    @Test fun traversalAndSymlinkEscapesRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = GuestStorage(context)
        assertThrows(IllegalArgumentException::class.java) { storage.instance("../outside") }
        val id = UUID.randomUUID().toString()
        val root = storage.prepare(id)
        try {
            listOf("../outside", "..", "/data/data", "a\\b", "").forEach { name -> assertThrows(IllegalArgumentException::class.java) { GuestStorage.child(root, name) } }
            android.system.Os.symlink(context.cacheDir.path, File(root,"escape").path)
            assertThrows(IllegalArgumentException::class.java) { GuestStorage.child(root,"escape") }
            val sentinel = File(context.cacheDir,"guest-deletion-sentinel").apply { writeText("keep") }
            storage.delete(id)
            assertEquals("keep", sentinel.readText()); sentinel.delete()
        } finally { storage.delete(id) }
    }
    @Test fun foreignPackagesCannotPassTheRuntimeGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThrows(IllegalArgumentException::class.java) { GuestPackageManager(context).installed("com.android.settings") }
    }
    @Test fun hostGrantsNeverAuthorizeAnUnsupportedGuestBridge() {
        GuestPermissionPolicy.unsupported.forEach { permission ->
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, GuestPermissionPolicy.decision(permission))
        }
    }
}
