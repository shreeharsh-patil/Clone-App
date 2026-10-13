package org.parallelnest.app.core.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.*
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.*
import org.json.JSONArray
import org.json.JSONObject

enum class CapabilityStatus(val label: String) {
    TESTED("Supported and tested"), UNTESTED("Available but untested"), PERMISSION("Permission required"),
    RESTRICTED("Restricted by Android"), UNSUPPORTED("Unsupported by the cloning engine"), FAILED("Failed")
}
data class Capability(val name: String,val status: CapabilityStatus,val detail: String)
data class AppPermission(val name: String,val granted: Boolean,val kind: String)
data class CompatibilityReport(val packageName: String,val profile: Long,val managed: Boolean,val api: Int,val target: Int,
    val permissions: List<AppPermission>,val capabilities: List<Capability>) {
    fun json()=JSONObject().put("package",packageName).put("profile",profile).put("managed",managed).put("api",api).put("target",target)
        .put("permissions",JSONArray().apply { permissions.forEach { put(JSONObject().put("name",it.name).put("granted",it.granted).put("kind",it.kind)) } })
        .put("capabilities",JSONArray().apply { capabilities.forEach { put(JSONObject().put("name",it.name).put("status",it.status.name).put("detail",it.detail)) } }).toString()
    companion object {
        fun parse(text: String): CompatibilityReport {
            require(text.toByteArray().size<=131_072) { "Diagnostic response is too large" }
            val root=JSONObject(text);val permissions=root.getJSONArray("permissions");val capabilities=root.getJSONArray("capabilities")
            return CompatibilityReport(root.getString("package"),root.getLong("profile"),root.getBoolean("managed"),root.getInt("api"),root.getInt("target"),
                (0 until permissions.length()).map { permissions.getJSONObject(it).let { p -> AppPermission(p.getString("name"),p.getBoolean("granted"),p.getString("kind")) } },
                (0 until capabilities.length()).map { capabilities.getJSONObject(it).let { c -> Capability(c.getString("name"),CapabilityStatus.valueOf(c.getString("status")),c.getString("detail")) } })
        }
    }
}
/** Runs in the managed user's real context. Reads grants; never grants or impersonates another package. */
object PermissionCompatibilityManager {
    private val special=setOf(Manifest.permission.SYSTEM_ALERT_WINDOW,Manifest.permission.WRITE_SETTINGS,Manifest.permission.SCHEDULE_EXACT_ALARM,Manifest.permission.MANAGE_EXTERNAL_STORAGE,Manifest.permission.REQUEST_INSTALL_PACKAGES)
    fun inspect(context: Context,pkg: String): CompatibilityReport {
        val pm=context.packageManager
        val info=pm.getPackageInfo(pkg,PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        val permissions=info.requestedPermissions.orEmpty().mapIndexed { i,name ->
            val dangerous=runCatching { pm.getPermissionInfo(name,0).protection == PermissionInfo.PROTECTION_DANGEROUS }.getOrDefault(false)
            AppPermission(name,((info.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0,
                if(name in special) "Android settings" else if(dangerous) "Runtime consent" else "Manifest permission")
        }
        val users=context.getSystemService(UserManager::class.java)
        val result=mutableListOf<Capability>()
        fun checked(name: String,required: Set<String> = emptySet(),restriction: String?=null,check: ()->String) {
            val missing=permissions.filter { it.name in required && !it.granted }
            if(restriction!=null && users.hasUserRestriction(restriction)) { result+=Capability(name,CapabilityStatus.RESTRICTED,"A policy restricts this operation in this Android profile.");return }
            if(name=="Camera" && context.getSystemService(android.app.admin.DevicePolicyManager::class.java).getCameraDisabled(null)) { result+=Capability(name,CapabilityStatus.RESTRICTED,"Android device/profile policy disables cameras.");return }
            if(missing.isNotEmpty()) { result+=Capability(name,CapabilityStatus.PERMISSION,"Open the clone and grant ${missing.joinToString { it.name.substringAfterLast('.') }} through Android. One-time/revoked grants may change; refresh this snapshot.");return }
            try { result+=Capability(name,CapabilityStatus.UNTESTED,check()+" Physical hardware and this app's full workflow have not been verified.") }
            catch(e: Exception) { result+=Capability(name,CapabilityStatus.FAILED,"Read-only API check failed: ${e.javaClass.simpleName}. ${e.message.orEmpty().take(160)}") }
        }
        checked("Bluetooth",setOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE),UserManager.DISALLOW_BLUETOOTH) {
            val service=context.getSystemService(Context.BLUETOOTH_SERVICE)
            "Android Bluetooth service ${if(service!=null) "responded" else "is unavailable"}; outgoing sharing ${if(users.hasUserRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING)) "is restricted" else "is allowed"}. Classic/BLE connections, accessories, reconnection and call routing need tests in the clone."
        }
        val foregroundLocation=permissions.any { it.name in setOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION) && it.granted }
        checked("Location",if(foregroundLocation)emptySet() else setOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),UserManager.DISALLOW_SHARE_LOCATION) {
            val service=context.getSystemService(LocationManager::class.java)
            "Location service responded: enabled=${service.isLocationEnabled}; providers=${service.allProviders.joinToString()}. Precise/approximate and foreground/background grants remain Android controlled. No location is fabricated or requested by the manager."
        }
        checked("Camera",setOf(Manifest.permission.CAMERA)) { "CameraManager returned ${context.getSystemService(CameraManager::class.java).cameraIdList.size} camera IDs. This does not prove preview, capture or video-call compatibility." }
        checked("Microphone",setOf(Manifest.permission.RECORD_AUDIO),UserManager.DISALLOW_UNMUTE_MICROPHONE) { "AudioManager returned ${context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).size} input routes. No audio is recorded by the manager." }
        checked("Speakers & media") { "AudioManager returned ${context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).size} output routes. Playback, focus, media sessions and headset/call transitions need in-app tests." }
        checked("Wi-Fi & networking",setOf(Manifest.permission.NEARBY_WIFI_DEVICES)) { "The networking service is ${if(context.getSystemService(Context.CONNECTIVITY_SERVICE)!=null) "present" else "unavailable"}. Internet, DNS, HTTPS, WebSockets, VPN routing and reconnection must be exercised by the app using its own permissions." }
        checked("Mobile data & telephony",setOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.SEND_SMS)) { "Telephony service ${if(context.getSystemService(Context.TELEPHONY_SERVICE)!=null) "responded" else "is unavailable"}. SIM access, SMS/default roles and profile policies are platform controlled; no role is assigned by Clone Apps." }
        checked("Notifications",setOf(Manifest.permission.POST_NOTIFICATIONS)) { "NotificationManager is ${if(context.getSystemService(Context.NOTIFICATION_SERVICE)!=null) "present" else "unavailable"}. Delivery, channels, sounds, actions, calls and configured FCM registration require an app/backend test." }
        checked("Background execution") { "JobScheduler and Android lifecycle apply directly to the real work package. Doze, battery policy, foreground-service types and a paused profile can limit work. No permanently running guest process is maintained." }
        checked("File access") { "ContentResolver is available in this profile. SAF/photo pickers, scoped storage, providers, MediaStore and URI grants must be exercised by the clone; files from another profile are not automatically accessible." }
        checked("Sensors & NFC") { "SensorManager returned ${context.getSystemService(SensorManager::class.java).getSensorList(android.hardware.Sensor.TYPE_ALL).size} sensors; NFC feature=${pm.hasSystemFeature(PackageManager.FEATURE_NFC)}. Events, haptics and NFC transactions are untested." }
        checked("Google services & integrations") {
            val google=runCatching { pm.getPackageInfo("com.google.android.gms",PackageManager.PackageInfoFlags.of(0)).versionName }.getOrNull()
            "Google services package ${google ?: "not visible/present"}. Presence does not prove FCM, Maps, accounts, Credential Manager, purchases or Integrity compatibility. Authentication, payment and integrity protections are never bypassed."
        }
        return CompatibilityReport(pkg,users.getSerialNumberForUser(Process.myUserHandle()),users.isManagedProfile,Build.VERSION.SDK_INT,info.applicationInfo?.targetSdkVersion ?: 0,permissions,result)
    }
}
