package org.parallelnest.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.tegufy.blackbox.BlackBoxCore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.parallelnest.app.core.*
import org.parallelnest.app.engine.api.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Experimental adapter: imported APK + real guest lifecycle + durable registry. */
class GeneralRuntimeEngine(private val context: Context, private val registry: Registry) : CloneEngine {
    private val lock = Mutex()
    private val box get() = BlackBoxCore.get()
    private val policy = context.getSharedPreferences("general-runtime", Context.MODE_PRIVATE)
    private val compat = "general_experimental"
    override val changes = SelectedRuntime.changes

    override fun status() = EngineState("general_experimental", SelectedRuntime.ready, true,
        SelectedRuntime.error?.let { "Runtime initialization failed: ${it.message}" }
            ?: if(!SelectedRuntime.ready) {
                if (SelectedRuntime.startupDelayed) "Android is taking longer to start the runtime. The dashboard remains available; readiness will refresh automatically."
                else "Starting the experimental app runtime…"
            }
            else "Experimental app runtime. Compatibility varies by app. Guests share the host UID.",
        linkedMapOf("App selection" to "Installed launcher apps; no package allowlist",
            "Execution" to "Actual guest Activity resume required before registration",
            "Data" to "Independent virtual user for each clone",
            "Split APKs" to "Complete installed APK sets are imported; compatibility under evaluation",
            "Google sign-in / Integrity / hardware" to "Not verified; no fabricated success responses",
            "Device coverage" to "Android 15 emulator; physical ARM64 verification pending"))

    private fun eligibility(info: ApplicationInfo): String? {
        if (!File(info.sourceDir).isFile) return "Installed APK is unavailable"
        return runCatching {
            val abis = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).flatMap { path ->
                ZipFile(path).use { apk -> apk.entries().asSequence().map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .map { it.split('/')[1] }.toList() }
            }.toSet()
            val supported64 = Build.SUPPORTED_64_BIT_ABIS.toSet()
            if (abis.isNotEmpty() && abis.intersect(supported64).isEmpty())
                "APK set has no native libraries for this 64-bit process" else null
        }.getOrElse { "APK could not be read: ${it.message}" }
    }

    override suspend fun listSupportedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val failure = eligibility(info)
                AppInfo(info.packageName, info.loadLabel(pm).toString(), listOf(info.sourceDir) + info.splitSourceDirs.orEmpty(),
                    failure == null && SelectedRuntime.ready,
                    failure ?: SelectedRuntime.error?.message ?: if(!SelectedRuntime.ready) "Runtime is starting; the list will refresh when ready" else "Ready to clone",
                    info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.sortedBy { it.label.lowercase() }
    }

    override suspend fun createInstance(app: AppInfo, name: String, progress: (CreationStage) -> Unit): OperationResult = lock.withLock {
        withContext(Dispatchers.IO) {
            progress(CreationStage.CHECKING)
            check(SelectedRuntime.ready) { "The app runtime is not ready: ${SelectedRuntime.error?.message ?: "still starting"}" }
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "Name must be 1–80 characters" }
            val sourcePackage = context.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            val info = checkNotNull(sourcePackage.applicationInfo) { "Source package information unavailable" }
            eligibility(info)?.let { error(it) }
            val rows = registry.snapshot()
            check(rows.size < 512) { "512 instance limit reached" }
            val user = maxOf(policy.getInt("next_user", 1), (rows.maxOfOrNull { it.virtualUser } ?: 0).toInt() + 1)
            check(user < 1_000_000_000) { "Virtual user limit reached" }
            check(policy.edit().putInt("next_user", user + 1).commit()) { "Could not allocate instance identity" }
            val number = (rows.filter { it.packageName == app.packageName && it.engine == "virtual" }.maxOfOrNull { it.number } ?: 0) + 1
            val id = UUID.randomUUID().toString()
            val staging = java.nio.file.Files.createTempDirectory(context.codeCacheDir.toPath(), "clone-import-").toFile()
            try {
                progress(CreationStage.PREPARING)
                box.createUser(user)
                // Copy code into the runtime. The host never changes the source
                // APK, its signing identity, or the original app's data.
                val sources = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
                val names = sources.mapIndexed { index, source -> if(index==0) "base.apk" else File(source).name }
                check(names.distinct().size == sources.size) { "APK set has conflicting file names" }
                sources.zip(names).forEach { (source, target) ->
                    val destination = File(staging, target)
                    java.io.FileOutputStream(destination).use { output ->
                        check(destination.setReadOnly()) { "Cannot protect staged APK" }
                        File(source).inputStream().use { it.copyTo(output) }
                        output.fd.sync()
                    }
                }
                val result = box.installPackageAsUser(staging, user)
                check(result.success && box.isInstalled(app.packageName, user)) { result.msg ?: "APK import failed" }
                progress(CreationStage.VERIFYING)
                val now = System.currentTimeMillis()
                val version = sourcePackage.longVersionCode
                val row = CloneInfo(id, app.packageName, if (number == 1) name.trim() else "${name.trim().take(72)} $number", user.toLong(), now, now,
                    number = number, engine = "virtual", virtualUser = user.toLong(), runtime = "ready", compatibility = compat,
                    versionCode = version, abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a")
                withContext(NonCancellable) { registry.update { it + row } }
                progress(CreationStage.REGISTERED)
                OperationResult(true, "Clone $number created successfully", id)
            } catch (t: Throwable) {
                withContext(NonCancellable) { box.uninstallPackageAsUser(app.packageName, user); box.deleteUser(user) }
                throw t
            } finally {
                // Files.walk does not follow symlinks, unlike recursive File walking.
                runCatching { java.nio.file.Files.walk(staging.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
                } }.onFailure { android.util.Log.e("GeneralRuntime", "Cannot remove import staging files", it) }
            }
        }
    }

    private suspend fun actual(instance: CloneInfo) = registry.snapshot().singleOrNull {
        it.id == instance.id && it.engine == "virtual"
    } ?: error("Instance is not available in this runtime")

    override suspend fun getInstances() = withContext(Dispatchers.IO) {
        registry.snapshot().filter { it.engine == "virtual" }.map {
            if (!SelectedRuntime.ready || box.isInstalled(it.packageName, it.virtualUser.toInt())) it else it.copy(runtime = "missing")
        }
    }

    override suspend fun launchInstance(instance: CloneInfo): OperationResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val row = actual(instance)
            try {
                check(SelectedRuntime.ready) { "Runtime unavailable: ${SelectedRuntime.error?.message ?: "still starting"}" }
                val requested = withContext(Dispatchers.IO) { box.launchApk(row.packageName, row.virtualUser.toInt()) }
                check(requested) { "The guest has no launchable Activity" }
                registry.update { list -> list.map { if (it.id == row.id) it.copy(runtime = "ready", lastLaunch = System.currentTimeMillis()) else it } }
                OperationResult(true, "Guest Activity launched", row.id)
            } catch (t: Throwable) {
                withContext(NonCancellable) { registry.update { list -> list.map { if (it.id == row.id) it.copy(runtime = "failed") else it } } }
                OperationResult(false, t.message ?: "Launch failed", row.id)
            }
        }
    }

    override suspend fun stopInstance(instance: CloneInfo): OperationResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val row = actual(instance)
            box.stopPackage(row.packageName, row.virtualUser.toInt())
            registry.update { list -> list.map { if (it.id == row.id) it.copy(runtime = "stopped") else it } }
            OperationResult(true, "Clone stopped", row.id)
        }
    }

    override suspend fun deleteInstance(instance: CloneInfo): OperationResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val row = actual(instance)
            box.uninstallPackageAsUser(row.packageName, row.virtualUser.toInt())
            check(!box.isInstalled(row.packageName, row.virtualUser.toInt())) { "Clone removal failed; metadata retained" }
            withContext(NonCancellable) { box.deleteUser(row.virtualUser.toInt()); registry.update { it.filterNot { r -> r.id == row.id } } }
            OperationResult(true, "Clone and its private data deleted", row.id)
        }
    }
}
