package org.parallelnest.app.engine.virtualization

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.parallelnest.app.core.*
import org.parallelnest.app.engine.api.*
import java.util.UUID

/** Original, deliberately limited Activity runtime. No third-party virtualization engine is bundled. */
class VirtualContainerEngine(private val context: Context, private val registry: Registry) : CloneEngine {
    private val storage = GuestStorage(context)
    private val packages = GuestPackageManager(context)
    private val runtime = GuestRuntimeManager(context)
    private val operations = Mutex()
    companion object {
        const val reason = "First-party Activity hosting prototype. Ordinary third-party apps, native guests, Services, Providers and hardware bridges are unsupported. Guests share the host UID."
    }
    override fun status() = EngineState("virtual", true, true, reason, linkedMapOf(
        "APK Activity" to "First-party, matching-signed probe only", "Storage" to "Per-instance files, preferences, SQLite and cache",
        "Processes" to "Two lazy host subprocess slots; one foreground Activity per slot", "Native/splits" to "Unsupported",
        "Services / Receivers / Providers / jobs" to "Unsupported", "Camera / microphone / Bluetooth / location / notifications" to "No guest bridge; denied",
        "Play Services / Integrity / accounts" to "Unsupported"))
    override suspend fun listSupportedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }.filter { it.packageName != context.packageName }.map { app ->
                val failure = runCatching { packages.installed(app.packageName) }.exceptionOrNull()
                AppInfo(app.packageName, app.loadLabel(pm).toString(), listOf(app.sourceDir) + app.splitSourceDirs.orEmpty(), failure == null,
                    failure?.message ?: "First-party guest Activity; no hardware or background component bridges", app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
            }.sortedWith(compareByDescending<AppInfo> { it.supported }.thenBy { it.label.lowercase() })
    }
    override suspend fun createInstance(app: AppInfo, name: String, progress: (CreationStage) -> Unit): OperationResult = operations.withLock {
        withContext(Dispatchers.IO) {
            progress(CreationStage.CHECKING)
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "Name must be 1–80 characters" }
            packages.installed(app.packageName)
            val rows = registry.snapshot()
            check(rows.size < 512) { "512 instance limit reached" }
            val user = (rows.filter { it.engine == "virtual" }.maxOfOrNull { it.virtualUser } ?: -1) + 1
            val number = (rows.filter { it.engine == "virtual" && it.packageName == app.packageName }.maxOfOrNull { it.number } ?: 0) + 1
            check(number <= 512 && user <= 1_000_000_000) { "Instance identity limit reached" }
            val id = UUID.randomUUID().toString()
            progress(CreationStage.PREPARING)
            val root = storage.prepare(id)
            try {
                val metadata = packages.import(app.packageName, root)
                packages.write(root, metadata)
                progress(CreationStage.VERIFYING)
                val launched = runtime.launch(id, user)
                check(launched.success) { launched.message }
                val now = System.currentTimeMillis()
                val row = CloneInfo(id, app.packageName, if(number == 1)name.trim() else "${name.trim().take(72)} $number", user, now, now, number=number, engine="virtual", virtualUser=user, runtime="ready", compatibility="probe_only", versionCode=metadata.getLong("version"), abi="bytecode")
                withContext(NonCancellable) { registry.update { it + row } }
                progress(CreationStage.REGISTERED)
                OperationResult(true, "Probe clone $number executed its guest Activity", id)
            } catch(e: Throwable) {
                withContext(NonCancellable) { runtime.stop(id, user); storage.delete(id) }
                throw e
            }
        }
    }
    private suspend fun actual(row: CloneInfo): CloneInfo = registry.snapshot().singleOrNull { it.id == row.id && it.engine == "virtual" } ?: error("Guest instance is unavailable")
    override suspend fun launchInstance(instance: CloneInfo): OperationResult = operations.withLock {
        withContext(Dispatchers.IO) {
            val row = actual(instance)
            val metadata = try { packages.read(storage.instance(row.id)).first }
            catch(e: Exception) {
                if(e is CancellationException)throw e
                registry.update { rows -> rows.map { if(it.id==row.id)it.copy(runtime="failed") else it } }
                return@withContext OperationResult(false, "Guest package verification failed: ${e.message}", row.id)
            }
            val result = runtime.launch(row.id, row.virtualUser)
            registry.update { rows -> rows.map { if(it.id == row.id) it.copy(runtime=if(result.success)"ready" else "failed", lastLaunch=if(result.success)System.currentTimeMillis() else it.lastLaunch,versionCode=if(result.success)metadata.getLong("version") else it.versionCode) else it } }
            result
        }
    }
    override suspend fun stopInstance(instance: CloneInfo): OperationResult = operations.withLock {
        withContext(Dispatchers.IO) { val row = actual(instance); runtime.stop(row.id, row.virtualUser).also { result ->
            if(result.success)registry.update { rows -> rows.map { if(it.id==row.id)it.copy(runtime="stopped") else it } }
        } }
    }
    override suspend fun deleteInstance(instance: CloneInfo): OperationResult = operations.withLock {
        withContext(Dispatchers.IO) {
            val row = actual(instance)
            val stopped = runtime.stop(row.id, row.virtualUser)
            check(stopped.success) { stopped.message }
            withContext(NonCancellable) { storage.delete(row.id); registry.update { rows -> rows.filterNot { it.id == row.id } } }
            OperationResult(true, "Deleted only ${row.name} and its private guest data", row.id)
        }
    }
    override suspend fun updateInstance(instance: CloneInfo): OperationResult = operations.withLock {
        withContext(Dispatchers.IO) {
            val row = actual(instance)
            val root = storage.instance(row.id)
            val previous = packages.read(root).first
            val imported = packages.import(row.packageName, root)
            check(imported.getLong("version") >= row.versionCode) { "Guest downgrades are unsupported" }
            val stopped = runtime.stop(row.id, row.virtualUser)
            check(stopped.success) { stopped.message }
            // Persist a non-ready state first. A crash during replacement never implies a verified update.
            registry.update { rows -> rows.map { if(it.id==row.id)it.copy(runtime="failed") else it } }
            try {
                packages.write(root, imported)
                val result = runtime.launch(row.id, row.virtualUser)
                check(result.success) { result.message }
                withContext(NonCancellable) { registry.update { rows -> rows.map { if(it.id==row.id)it.copy(runtime="ready",versionCode=imported.getLong("version"),lastLaunch=System.currentTimeMillis()) else it } } }
                OperationResult(true, "Guest APK verified; existing instance data retained", row.id)
            } catch(e: Throwable) {
                withContext(NonCancellable) { runtime.stop(row.id, row.virtualUser); packages.write(root, previous) }
                throw e
            }
        }
    }
    override suspend fun getInstances(): List<CloneInfo> = withContext(Dispatchers.IO) {
        registry.snapshot().filter { it.engine=="virtual" }.map { row -> if(storage.instance(row.id).resolve("package.json").isFile) row else row.copy(runtime="missing") }
    }
}
