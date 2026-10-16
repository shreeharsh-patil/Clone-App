package org.parallelnest.app.engine.virtualization

import android.Manifest
import android.content.pm.PackageManager

/** No privileged guest bridges are implemented. Host grants never imply guest grants. */
object GuestPermissionPolicy {
    val unsupported = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_WIFI_STATE)
    fun decision(permission: String) = if(permission in unsupported) PackageManager.PERMISSION_DENIED else null
}
