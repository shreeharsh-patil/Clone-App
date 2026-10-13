package org.parallelnest.app.engine

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PersistableBundle
import android.os.UserManager
import org.parallelnest.app.BuildConfig
import org.parallelnest.app.core.SecureStore
import android.util.Base64

const val WORK_COMMAND = "org.parallelnest.app.WORK_COMMAND"
fun admin(context: Context) = ComponentName(context, NestAdminReceiver::class.java)

fun configureProfile(context: Context, intent: Intent) {
    val dpm = context.getSystemService(DevicePolicyManager::class.java)
    if (!dpm.isProfileOwnerApp(context.packageName)) return
    val extras = intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
    extras?.getString("bridge-key")?.let { raw ->
        if (SecureStore(context).get("bridge") == null) SecureStore(context).put("bridge", Base64.decode(raw, Base64.NO_WRAP))
    }
    dpm.clearCrossProfileIntentFilters(admin(context))
    dpm.addCrossProfileIntentFilter(admin(context), IntentFilter(WORK_COMMAND).apply { addCategory(Intent.CATEGORY_DEFAULT) }, DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT)
    dpm.setProfileName(admin(context), "ParallelNest")
    // Default managed-profile policy blocks sideloading. Permit user-selected
    // installations in our own profile; Android's installer still validates APKs
    // and displays its consent flow. Never modify another owner's restrictions.
    dpm.clearUserRestriction(admin(context), UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
    if (BuildConfig.DEBUG) dpm.clearUserRestriction(admin(context), UserManager.DISALLOW_DEBUGGING_FEATURES)
    dpm.setProfileEnabled(admin(context))
    context.packageManager.setComponentEnabledSetting(ComponentName(context, org.parallelnest.app.MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
}
class NestAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) { configureProfile(context, intent) }
}
class NestPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { configureProfile(context, intent) }
}
class ProvisionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == DevicePolicyManager.ACTION_GET_PROVISIONING_MODE) {
            val modes = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
            if (modes != null && DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE !in modes) setResult(RESULT_CANCELED)
            else setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE))
        } else {
            configureProfile(this, intent)
            setResult(RESULT_OK)
        }
        finish()
    }
}
