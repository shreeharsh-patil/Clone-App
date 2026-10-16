package org.parallelnest.app

import android.app.Activity
import android.app.Application
import android.util.AtomicFile
import android.util.Log
import com.tegufy.blackbox.BlackBoxCore
import com.tegufy.blackbox.app.BActivityThread
import com.tegufy.blackbox.app.configuration.AppLifecycleCallback
import com.tegufy.blackbox.app.configuration.ClientConfiguration
import me.weishu.reflection.Reflection
import org.json.JSONObject
import org.parallelnest.app.core.Registry
import org.parallelnest.app.engine.api.CloneEngine
import java.io.File

/** Loaded only in the separate, explicitly selected experimental application. */
object SelectedRuntime {
    @Volatile var error: Throwable? = null
        private set
    @Volatile var ready = false
        private set
    @Volatile var startupDelayed = false
        private set
    val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1)
    private lateinit var application: Application
    private var hostProcess = false
    lateinit var events: File
        private set

    fun attach(application: Application) {
        this.application = application
        hostProcess = Application.getProcessName() == application.packageName
        events = File(application.applicationInfo.dataDir, "files/general-runtime-events")
        // The dashboard can render before the separate runtime process starts.
        // Provider startup must never hold the host's Application.attach thread.
        if (!hostProcess) initialize()
    }

    private fun initialize() {
        try {
            check(Reflection.unseal(application) == 0) { "Android framework reflection is unavailable" }
            BlackBoxCore.get().doAttachBaseContext(application, object : ClientConfiguration() {
                override fun getHostPackageName() = application.packageName
                override fun isEnableDaemonService() = false
                override fun isEnableLauncherActivity() = false
                override fun isHideRoot() = false
                override fun getLogSenderChatId(): String? = null
            })
            BlackBoxCore.get().addAppLifecycleCallback(object : AppLifecycleCallback() {
                override fun onActivityResumed(activity: Activity) {
                    // A successful launch request is insufficient. Report a real
                    // guest lifecycle callback, bound to the pending request.
                    val user = BActivityThread.getUserId()
                    val request = File(events, "$user.request.json")
                    if (!request.isFile) return
                    runCatching {
                        val expected = JSONObject(request.readText())
                        if (expected.getString("package") != activity.packageName || activity.isFinishing) return
                        val reply = expected.put("activity", activity.javaClass.name)
                            .put("resumed", android.os.SystemClock.elapsedRealtime())
                        events.mkdirs()
                        val output = AtomicFile(File(events, "$user.reply.json"))
                        val stream = output.startWrite()
                        try { stream.write(reply.toString().toByteArray()); output.finishWrite(stream) }
                        catch (t: Throwable) { output.failWrite(stream); throw t }
                    }.onFailure { Log.e("GeneralRuntime", "Cannot report guest lifecycle", it) }
                }
            })
        } catch (t: Throwable) { error = t; Log.e("GeneralRuntime", "Initialization failed", t); changes.tryEmit(Unit) }
    }

    fun start() {
        if (hostProcess) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!ready && error == null) {
                    // Keep accepting a late service connection. A busy Android
                    // boot must not permanently poison this process's runtime.
                    startupDelayed = true
                    changes.tryEmit(Unit)
                }
            }, 30_000)
            Thread({
                initialize()
                completeStartup()
            }, "CloneRuntimeStartup").start()
        } else completeStartup()
    }

    private fun completeStartup() {
        if (error == null) try {
            BlackBoxCore.get().doCreate()
            // Query the actual virtual package service, not an original-app fallback.
            BlackBoxCore.get().isInstalled("org.parallelnest.runtime.readiness", 0)
            ready = true
        } catch (t: Throwable) { error = t; Log.e("GeneralRuntime", "Service startup failed", t) }
        changes.tryEmit(Unit)
    }

    fun engine(application: Application, registry: Registry): CloneEngine = GeneralRuntimeEngine(application, registry)
}
