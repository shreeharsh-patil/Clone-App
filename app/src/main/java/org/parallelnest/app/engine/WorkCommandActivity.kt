package org.parallelnest.app.engine

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.parallelnest.app.core.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

private val activeWorkOperations = ConcurrentHashMap<String, WeakReference<WorkCommandActivity>>()

class WorkCommandActivity : ComponentActivity() {
    private var callback: PendingIntent? = null
    private var sessionId = -1
    // PackageInstaller's terminal broadcast is authoritative. Its confirmation
    // Activity can return RESULT_CANCELED even after a successful install/delete.
    private val consent = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { }
    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return } // Never replay an installation on rotation.
        callback = intent.getParcelableExtra("callback", PendingIntent::class.java)
        val authentic = callback?.let { it.creatorPackage == packageName && (it.creatorUid % 100_000) == (Process.myUid() % 100_000) } == true
        if (!authentic || !getSystemService(UserManager::class.java).isManagedProfile || !dpm.isProfileOwnerApp(packageName) || !BridgeAuth.verify(this, intent)) { finish(); return }
        activeWorkOperations[intent.getStringExtra("nonce")!!] = WeakReference(this)
        setContent { MaterialTheme { Surface { Column(Modifier.padding(32.dp)) { Text("ParallelNest work profile", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(16.dp)); LinearProgressIndicator(); Text("Applying your requested profile operation…") } } } }
        lifecycleScope.launch {
            try {
                val pkg = intent.getStringExtra("package").orEmpty()
                val op = intent.getStringExtra("op")
                require(op == "list" || pkg != packageName && Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(pkg)) { "Invalid target package" }
                when (op) {
                    "list" -> {
                        val apps = withContext(Dispatchers.IO) { packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0)).filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName } }
                        reply(true, "Profile apps loaded", Intent().putStringArrayListExtra("packages", ArrayList(apps.map { it.activityInfo.packageName })).putStringArrayListExtra("labels", ArrayList(apps.map { it.loadLabel(packageManager).toString() })))
                    }
                    "install" -> install(pkg)
                    "launch" -> {
                        applySuspension(pkg,false)
                        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: error("App has no launcher activity in the work profile")
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        reply(true, "Launched in the work profile")
                    }
                    "freeze" -> {
                        applySuspension(pkg,true)
                        reply(true, "Work app suspended; launch resumes it")
                    }
                    "delete" -> {
                        // Only this profile's PackageInstaller is used. Never the parent's.
                        packageManager.packageInstaller.uninstall(pkg, statusCallback(pkg, "delete").intentSender)
                    }
                    "info" -> {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null)))
                        reply(true, "Work-profile app settings opened")
                    }
                    else -> error("Unsupported operation")
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; reply(false, e.message ?: "Android rejected this operation") }
        }
    }
    private suspend fun applySuspension(pkg: String, suspended: Boolean) {
        val events=Channel<Unit>(Channel.CONFLATED)
        val receiver=object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if(intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST)?.contains(pkg)==true) events.trySend(Unit)
            }
        }
        registerReceiver(receiver,IntentFilter(if(suspended) Intent.ACTION_PACKAGES_SUSPENDED else Intent.ACTION_PACKAGES_UNSUSPENDED),Context.RECEIVER_EXPORTED)
        try {
            check(dpm.setPackagesSuspended(admin(this),arrayOf(pkg),suspended).isEmpty()) { "Android refused this suspension change; the app may be critical or policy controlled" }
            try {
                withTimeout(10_000) { while(withContext(Dispatchers.IO){packageManager.isPackageSuspended(pkg)}!=suspended) events.receive() }
            } catch(_: TimeoutCancellationException) { error("Android did not apply the suspension change. Another system policy may control this app.") }
        } finally { unregisterReceiver(receiver);events.close() }
    }
    private fun reply(success: Boolean, message: String, extra: Intent = Intent()) {
        extra.putExtra("success", success).putExtra("message", message).putExtra("profile", getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle()))
        BridgeAuth.signReply(this,extra,intent.getStringExtra("nonce")!!)
        runCatching { callback?.send(this, 0, extra) }
        finish()
    }
    private fun statusCallback(pkg: String, op: String): PendingIntent {
        val nonce = intent.getStringExtra("nonce")!!
        val target = Intent(this, OperationReceiver::class.java).setAction("INSTALL.$nonce").putExtra("installer", true).putExtra("nonce",nonce).putExtra("session",sessionId).putExtra("package", pkg).putExtra("operation", op).putExtra("parent_callback", callback)
        // Explicit mutable callback required by PackageInstaller on target 35+.
        return PendingIntent.getBroadcast(this, nonce.hashCode(), target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }
    private suspend fun install(pkg: String) {
        if (packageManager.getLaunchIntentForPackage(pkg) != null) { reply(true, "Existing work-profile app added; its data was preserved"); return }
        val paths = intent.getStringArrayListExtra("paths").orEmpty()
        require(paths.isNotEmpty() && paths.size <= 128) { "No valid APK files supplied" }
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        var id = -1
        try {
            withContext(Dispatchers.IO) {
                val files = paths.map { path ->
                    File(path).canonicalFile.also { f ->
                        require((f.path.startsWith("/data/app/") || f.path.startsWith("/system/") || f.path.startsWith("/product/") || f.path.startsWith("/system_ext/")) && f.extension == "apk" && f.isFile && f.canRead()) { "APK source is inaccessible or outside permitted package directories" }
                    }
                }
                require(packageManager.getPackageArchiveInfo(files.first().path, PackageManager.PackageInfoFlags.of(0))?.packageName == pkg) { "APK package does not match selected app" }
                id = installer.createSession(params)
                sessionId = id
                installer.openSession(id).use { session ->
                    files.forEachIndexed { index, file ->
                        ensureActive()
                        session.openWrite(if (index == 0) "base.apk" else "split_$index.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                    }
                    session.commit(statusCallback(pkg, "install").intentSender)
                }
            }
            // Keep this foreground-capable Activity until the installer callback.
            // Terminal broadcast results never need a background activity launch.
        } catch (e: Exception) { if (id >= 0) runCatching { installer.abandonSession(id) }; throw e }
    }
    fun confirmInstallation(consentIntent: Intent) { consent.launch(consentIntent) }
    override fun onDestroy() {
        val nonce=intent.getStringExtra("nonce")
        if(nonce!=null && activeWorkOperations[nonce]?.get() === this) activeWorkOperations.remove(nonce)
        super.onDestroy()
    }
}

class OperationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra("installer", false)) {
            val pendingResult=goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try { if(BridgeAuth.verifyReply(context,intent)) intent.getStringExtra("nonce")?.let { OperationMailbox.pending.remove(it)?.complete(intent) } } finally { pendingResult.finish() }
            }
            return
        }
        if(!context.getSystemService(UserManager::class.java).isManagedProfile || !context.getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(context.packageName)) return
        val nonce=intent.getStringExtra("nonce").orEmpty()
        val active=activeWorkOperations[nonce]?.get()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val consent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (consent != null && active != null && !active.isFinishing) {
                try { active.confirmInstallation(consent); return } catch (_: Exception) { /* Return an explicit failure below. */ }
            }
            val id=intent.getIntExtra("session",-1)
            if(id>=0)runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        }
        val parent = intent.getParcelableExtra("parent_callback", PendingIntent::class.java) ?: return
        val success = status == PackageInstaller.STATUS_SUCCESS
        val result = Intent().putExtra("success", success).putExtra("profile", context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle()))
            .putExtra("message", if (success) if (intent.getStringExtra("operation") == "delete") "Work-profile app deleted; personal data is untouched" else "App installed in a separate work profile" else intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation/deletion cancelled or blocked by Android. Retry with the work operation in the foreground.")
        BridgeAuth.signReply(context,result,nonce)
        runCatching { parent.send(context, 0, result) }
        active?.finish()
        activeWorkOperations.remove(nonce)
        PendingIntent.getBroadcast(context,nonce.hashCode(),Intent(context,OperationReceiver::class.java).setAction("INSTALL.$nonce"),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)?.cancel()
    }
}
