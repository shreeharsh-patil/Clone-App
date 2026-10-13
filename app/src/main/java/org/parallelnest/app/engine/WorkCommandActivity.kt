package org.parallelnest.app.engine

import android.app.PendingIntent
import android.app.ActivityOptions
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
import org.parallelnest.app.core.*
import java.io.File

class WorkCommandActivity : ComponentActivity() {
    private var callback: PendingIntent? = null
    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return } // Never replay an installation on rotation.
        callback = intent.getParcelableExtra("callback", PendingIntent::class.java)
        val authentic = callback?.let { it.creatorPackage == packageName && (it.creatorUid % 100_000) == (Process.myUid() % 100_000) } == true
        if (!authentic || !getSystemService(UserManager::class.java).isManagedProfile || !dpm.isProfileOwnerApp(packageName) || !BridgeAuth.verify(this, intent)) { finish(); return }
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
                        check(dpm.setPackagesSuspended(admin(this@WorkCommandActivity), arrayOf(pkg), false).isEmpty()) { "Android refused to resume this app" }
                        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: error("App has no launcher activity in the work profile")
                        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        reply(true, "Launched in the work profile")
                    }
                    "freeze" -> {
                        check(dpm.setPackagesSuspended(admin(this@WorkCommandActivity), arrayOf(pkg), true).isEmpty()) { "Android cannot suspend this app (it may be critical or a profile administrator)" }
                        reply(true, "Work app suspended; launch resumes it")
                    }
                    "delete" -> {
                        // Only this profile's PackageInstaller is used. Never the parent's.
                        packageManager.packageInstaller.uninstall(pkg, statusCallback(pkg, "delete").intentSender)
                        finish()
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
    private fun reply(success: Boolean, message: String, extra: Intent = Intent()) {
        extra.putExtra("success", success).putExtra("message", message).putExtra("profile", getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle()))
        runCatching { callback?.send(this, 0, extra) }
        finish()
    }
    private fun statusCallback(pkg: String, op: String): PendingIntent {
        val nonce = intent.getStringExtra("nonce")!!
        val target = Intent(this, OperationReceiver::class.java).setAction("INSTALL.$nonce").putExtra("installer", true).putExtra("package", pkg).putExtra("operation", op).putExtra("parent_callback", callback)
        // Explicit mutable callback required by PackageInstaller on target 35+.
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 35) options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        target.setClass(this, InstallerResultActivity::class.java)
        return PendingIntent.getActivity(this, nonce.hashCode(), target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, options.toBundle())
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
                installer.openSession(id).use { session ->
                    files.forEachIndexed { index, file ->
                        ensureActive()
                        session.openWrite(if (index == 0) "base.apk" else "split_$index.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                    }
                    session.commit(statusCallback(pkg, "install").intentSender)
                }
            }
            finish()
        } catch (e: Exception) { if (id >= 0) runCatching { installer.abandonSession(id) }; throw e }
    }
}

class OperationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra("installer", false)) {
            intent.getStringExtra("nonce")?.let { OperationMailbox.pending.remove(it)?.complete(intent) }
            return
        }
        // Installer callbacks go through a private Activity, keeping consent launches foreground.
    }
}

class InstallerResultActivity : ComponentActivity() {
    private val consent = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val consent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (consent != null) {
                try { this.consent.launch(consent); return } catch (_: Exception) { /* Return an explicit failure below. */ }
            }
        }
        val parent = intent.getParcelableExtra("parent_callback", PendingIntent::class.java) ?: run { finish(); return }
        val success = status == PackageInstaller.STATUS_SUCCESS
        val result = Intent().putExtra("success", success).putExtra("profile", context.getSystemService(UserManager::class.java).getSerialNumberForUser(Process.myUserHandle()))
            .putExtra("message", if (success) if (intent.getStringExtra("operation") == "delete") "Work-profile app deleted; personal data is untouched" else "App installed in a separate work profile" else intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation/deletion cancelled or blocked by Android")
        runCatching { parent.send(context, 0, result) }
        finish()
    }
}
