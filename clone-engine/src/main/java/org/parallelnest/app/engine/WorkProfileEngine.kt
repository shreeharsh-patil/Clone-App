package org.parallelnest.app.engine

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.*
import org.parallelnest.app.core.*
import org.parallelnest.app.engine.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object OperationMailbox {
    val pending = ConcurrentHashMap<String, CompletableDeferred<Intent>>()
}
class WorkProfileEngine(private val context: Context, private val registry: Registry) : CloneEngine {
    private val pm = context.packageManager
    private val users = context.getSystemService(UserManager::class.java)
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val createMutex = Mutex()
    private fun forwarder(): ComponentName? = pm.queryIntentActivities(Intent(WORK_COMMAND), PackageManager.ResolveInfoFlags.of(0)).firstOrNull {
        it.activityInfo.packageName == "android" && it.activityInfo.name.endsWith("ForwardIntentToManagedProfile")
    }?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    override fun status(): ProfileState {
        val managed = users.isManagedProfile
        val supported = pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
        val others = users.userProfiles.any { it != Process.myUserHandle() }
        val bridge = runCatching { forwarder() != null }.getOrDefault(false)
        val provision = !managed && !others && supported && dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        val reason = when {
            managed && dpm.isProfileOwnerApp(context.packageName) -> "Running inside the Clone Apps work profile"
            managed -> "This work profile is controlled by another application"
            bridge -> "Clone Apps work profile connected"
            others -> "An existing profile is present. Clone Apps will not replace its controller. If your Clone Apps profile is paused, resume it in Android settings."
            !supported -> "This device does not support Android managed profiles"
            !provision -> "Android or your device administrator does not allow work-profile provisioning"
            else -> "Set up a work profile to create one separate copy per app"
        }
        return ProfileState(managed, supported, provision, others, bridge, reason)
    }
    fun provisioningIntent(): Intent {
        check(status().canProvision) { status().description }
        val token = SecureStore(context).newBridgeKey()
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin(context))
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle().apply { putString("bridge-key", android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)) })
    }
    override suspend fun listSupportedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0)).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }.filter { it.packageName != context.packageName }.map { app ->
            val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
            val system=app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0
            AppInfo(app.packageName, app.loadLabel(pm).toString(), paths, system || paths.all { java.io.File(it).canRead() }, if(system) "Enable the existing system package in the managed profile" else if (paths.all { java.io.File(it).canRead() }) "Android profile installation; app sign-in compatibility must be tested" else "APK files are not readable on this device; install from a trusted source inside the work profile",system)
        }.sortedBy { it.label.lowercase() }
    }
    suspend fun request(op: String, packageName: String = "", name: String = "", paths: List<String> = emptyList()): Intent {
        val nonce = UUID.randomUUID().toString()
        val result = CompletableDeferred<Intent>()
        OperationMailbox.pending[nonce] = result
        val callback = PendingIntent.getBroadcast(context, nonce.hashCode(), Intent(context, OperationReceiver::class.java).setAction("RESULT.$nonce").putExtra("nonce", nonce), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
        var launched=false
        try {
            if(op in listOf("install","enable_system")) withContext(Dispatchers.IO) { registry.rememberCreation(nonce,packageName,name) }
            val command = Intent(WORK_COMMAND).putExtra("op", op).putExtra("nonce", nonce).putExtra("time", System.currentTimeMillis().toString()).putExtra("package", packageName).putExtra("name", name).putStringArrayListExtra("paths", ArrayList(paths)).putExtra("callback", callback)
            withContext(Dispatchers.IO) { BridgeAuth.sign(context, command) }
            command.component = forwarder() ?: error(status().description)
            withContext(Dispatchers.Main) { context.startActivity(command.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            launched=true
            return try { withTimeout(180_000) { result.await() } } catch (_: TimeoutCancellationException) {
                error("Android did not finish within three minutes. Resume the work profile and sync work apps to recover any completed installation.")
            }
        } finally { OperationMailbox.pending.remove(nonce); callback.cancel();if(op in listOf("install","enable_system") && !launched) withContext(NonCancellable+Dispatchers.IO) { registry.forgetCreation(nonce) } }
    }
    private fun result(intent: Intent) = OperationResult(intent.getBooleanExtra("success", false), intent.getStringExtra("message") ?: "No result returned")
    override suspend fun createInstance(app: AppInfo, name: String, progress: (CreationStage)->Unit): OperationResult = createMutex.withLock {
        progress(CreationStage.CHECKING)
        require(name.trim().isNotEmpty() && name.length <= 80 && name.none { it.isISOControl() }) { "Name must be 1–80 characters without control characters" }
        // Package updates replace /data/app paths. A selection may survive an
        // update, so re-read the installed package before sending APK sources.
        val currentApp=listSupportedApps().firstOrNull { it.packageName==app.packageName }
            ?: return@withLock OperationResult(false,"The original app is no longer installed or has no launcher activity. Refresh Device Apps.")
        if (!currentApp.supported) return@withLock OperationResult(false, currentApp.reason)
        progress(CreationStage.PREPARING)
        check(status().bridgeAvailable) { status().description }
        progress(CreationStage.ANDROID)
        val reply = request(if(currentApp.system) "enable_system" else "install", currentApp.packageName, name.trim(), if(currentApp.system)emptyList() else currentApp.paths)
        if (reply.getBooleanExtra("success", false)) {
            val serial = reply.getLongExtra("profile", -1)
            check(serial >= 0) { "Installer returned no profile identifier" }
            progress(CreationStage.REGISTERED)
            progress(CreationStage.VERIFYING)
            check(getInstances().any { it.packageName==app.packageName && it.profile==serial }) { "Android installed the app, but it is not yet available. Refresh Apps to recover it." }
        }
        result(reply)
    }
    override suspend fun launchInstance(instance: CloneInfo): OperationResult {
        val reply = request("launch", instance.packageName)
        if (reply.getBooleanExtra("success", false)) registry.update { rows -> rows.map { if (it.id == instance.id) it.copy(lastLaunch = System.currentTimeMillis()) else it } }
        return result(reply)
    }
    override suspend fun stopInstance(instance: CloneInfo) = result(request("freeze", instance.packageName))
    override suspend fun deleteInstance(instance: CloneInfo): OperationResult {
        val reply = request("delete", instance.packageName)
        if (reply.getBooleanExtra("success", false)) registry.update { it.filterNot { row -> row.id == instance.id } }
        return result(reply)
    }
    suspend fun adoptProfileApps(): OperationResult {
        val reply = request("list")
        if (!reply.getBooleanExtra("success", false)) return result(reply)
        val serial = reply.getLongExtra("profile", -1)
        val packages = reply.getStringArrayListExtra("packages").orEmpty()
        val labels = reply.getStringArrayListExtra("labels").orEmpty()
        registry.reconcileCreations(packages.toSet(),serial)
        registry.update { old -> old + packages.mapIndexedNotNull { i, pkg -> if (old.any { it.packageName == pkg && it.profile == serial }) null else CloneInfo(UUID.randomUUID().toString(), pkg, labels.getOrElse(i) { pkg }.take(80), serial, System.currentTimeMillis()) } }
        return OperationResult(true, "Installed work apps synchronized")
    }
    override suspend fun getInstances(): List<CloneInfo> = withContext(Dispatchers.IO) {
        val profiles = users.userProfiles.filter { it != Process.myUserHandle() }.associateBy { users.getSerialNumberForUser(it) }
        registry.instances.first().mapNotNull { row ->
            val user = profiles[row.profile] ?: return@mapNotNull null
            // A paused profile remains installed; do not delete its metadata.
            if (users.isQuietModeEnabled(user)) row.copy(paused=true)
            else runCatching { launcher.getActivityList(row.packageName,user).firstOrNull()?.let { row.copy(suspended=(it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED)!=0) } }.getOrNull()
        }
    }
    suspend fun installedEnvironmentKeys(): Set<Pair<String, Long>> = withContext(Dispatchers.IO) {
        users.userProfiles.filter { it != Process.myUserHandle() }.flatMap { user ->
            runCatching { launcher.getActivityList(null,user).map { it.applicationInfo.packageName to users.getSerialNumberForUser(user) } }.getOrDefault(emptyList())
        }.toSet()
    }
    suspend fun rename(row: CloneInfo, name: String) { registry.update { rows -> rows.map { if (it.id == row.id) it.copy(name = name.trim()) else it } } }
    suspend fun accent(row: CloneInfo, value: Int) { registry.update { rows -> rows.map { if (it.id == row.id) it.copy(accent = value) else it } } }
}
