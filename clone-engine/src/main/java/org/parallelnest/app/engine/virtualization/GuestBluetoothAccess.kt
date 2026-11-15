package org.parallelnest.app.engine.virtualization

import android.Manifest
import android.content.pm.PackageInfo
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** Consent for the trusted, cooperative probe. Android still enforces the host UID's grants. */
internal class GuestBluetoothAccess(root: File, pkg: PackageInfo) {
    companion object {
        private const val PROTOCOL = "org.parallelnest.GUEST_BLUETOOTH"
        private val required = setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)

        internal fun allows(permission: String, declared: Set<String>, consent: Boolean, hostGranted: Boolean) =
            permission in required && permission in declared && consent && hostGranted
    }

    private val marker = AtomicFile(GuestStorage.child(root, "bluetooth-consent"))
    private val declared = pkg.requestedPermissions.orEmpty().toSet()
    val supported = pkg.applicationInfo?.metaData?.getInt(PROTOCOL) == 1 && declared.containsAll(required)

    val consent: Boolean
        get() = supported && try {
            marker.openRead().use { it.read() == 1 }
        } catch (_: FileNotFoundException) {
            false
        }

    fun granted(permission: String, hostGranted: Boolean) =
        permission in required && allows(permission, declared, consent, hostGranted)

    fun setConsent(allowed: Boolean) {
        check(supported || !allowed) { "Update this probe before enabling Bluetooth" }
        val output = marker.startWrite()
        try {
            output.write(if (allowed) 1 else 0)
            marker.finishWrite(output)
        } catch (e: Exception) {
            marker.failWrite(output)
            throw e
        }
    }
}
