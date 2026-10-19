package org.parallelnest.app.engine.virtualization

import android.app.Activity
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import dalvik.system.DexClassLoader
import kotlinx.coroutines.*

/** Actual APK Activity hosted locally using public Instrumentation APIs, with known system-linkage limits. */
open class GuestHostActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val instrumentation = Instrumentation()
    private var guest: Activity? = null
    private var started = false
    private var resumed = false
    private var active = false
    private var guestResumed = false
    private var stopResult: PendingIntent? = null
    private var loading: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuestSession.activity = this
        setContentView(TextView(this).apply { text = "Loading trusted guest…"; setPadding(24, 64, 24, 24) })
        load(savedInstanceState?.getBundle("guest"))
    }
    private fun load(saved: Bundle?) { loading?.cancel(); loading = scope.launch {
        try {
            val id = intent.getStringExtra("instance") ?: error("Missing guest identity")
            val root = GuestStorage(this@GuestHostActivity).instance(id)
            val (meta, pkg) = withContext(Dispatchers.IO) { GuestPackageManager(this@GuestHostActivity).read(root) }
            val appInfo = requireNotNull(pkg.applicationInfo).apply {
                sourceDir = GuestStorage.child(GuestStorage.child(root, "apk"), meta.optString("apk", "base.apk")).path
                publicSourceDir = sourceDir; dataDir = root.path
            }
            val loader = DexClassLoader(appInfo.sourceDir, GuestStorage.child(root, "code_cache").path, null, classLoader.parent)
            val context = GuestContext(this@GuestHostActivity, root, appInfo, loader, packageManager.getResourcesForApplication(appInfo))
            val application = Instrumentation.newApplication(android.app.Application::class.java, context)
            context.guestApplication = application
            instrumentation.callApplicationOnCreate(application)
            val info = pkg.activities.orEmpty().first { it.name == meta.getString("entry") }
            val target = Intent().setClassName(pkg.packageName, info.name).putExtra("virtual_instance", id)
            // Public Activity hosting still needs the real host task token for input/system callbacks.
            // A fabricated Binder can render a view but fails when Android validates Activity identity.
            val token = withTimeout(5_000) {
                while ((window.decorView.layoutParams as? android.view.WindowManager.LayoutParams)?.token == null) delay(16)
                (window.decorView.layoutParams as android.view.WindowManager.LayoutParams).token
            }
            val activity = instrumentation.newActivity(loader.loadClass(info.name), context, token, application, target, info, "Guest probe", this@GuestHostActivity, id, null)
            guest = activity
            activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            instrumentation.callActivityOnCreate(activity, saved)
            // Nested DecorViews produce cyclic accessibility parents. Host the Activity's
            // actual content inside the one real system Window instead.
            val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            content.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            content.importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            (content.parent as? ViewGroup)?.removeView(content)
            setContentView(content)
            synchronizeLifecycle()
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) failure(e) else throw e
        } catch (e: Throwable) { failure(e) }
    } }
    private fun synchronizeLifecycle() {
        val activity = guest ?: return
        try {
            if (started && !active) { instrumentation.callActivityOnStart(activity); active = true }
            if (resumed && !guestResumed) {
                instrumentation.callActivityOnResume(activity)
                guestResumed = true
                val result = intent.getParcelableExtra("result", PendingIntent::class.java)
                if (result != null) {
                    result.send(this, 0, Intent().putExtra("success", true).putExtra("instance", intent.getStringExtra("instance")).putExtra("message", "Guest Activity created and resumed"))
                    intent.removeExtra("result")
                }
            }
        } catch (e: Throwable) { failure(e) }
    }
    private fun failure(error: Throwable) {
        android.util.Log.e("GuestRuntime", "Guest Activity failed", error)
        runCatching { intent.getParcelableExtra("result", PendingIntent::class.java)?.send(this, 0, Intent().putExtra("success", false).putExtra("instance", intent.getStringExtra("instance")).putExtra("message", "Guest Activity failed: ${error.javaClass.simpleName}: ${error.message}")) }
        intent.removeExtra("result")
        setContentView(TextView(this).apply { text = "Guest compatibility error\n${error.javaClass.simpleName}: ${error.message}"; setPadding(24, 64, 24, 24) })
    }
    override fun onStart() { super.onStart(); started = true; synchronizeLifecycle() }
    override fun onRestart() { super.onRestart(); guest?.let { runCatching { instrumentation.callActivityOnRestart(it) }.onFailure(::failure) } }
    override fun onResume() { super.onResume(); resumed = true; synchronizeLifecycle() }
    override fun onPause() { resumed = false; if(guestResumed)guest?.let { runCatching { instrumentation.callActivityOnPause(it) } }; guestResumed = false; super.onPause() }
    override fun onStop() { started = false; if (active) guest?.let { runCatching { instrumentation.callActivityOnStop(it) } }; active = false; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { guest?.let { activity -> val state = Bundle(); runCatching { instrumentation.callActivityOnSaveInstanceState(activity, state) }; outState.putBundle("guest", state) }; super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        scope.cancel(); guest?.let { runCatching { instrumentation.callActivityOnDestroy(it) } }; guest = null
        if(GuestSession.activity === this)GuestSession.activity = null
        runCatching { stopResult?.send(this, 0, Intent().putExtra("success", true).putExtra("instance", intent.getStringExtra("instance")).putExtra("message", "Guest Activity stopped")) }
        super.onDestroy()
    }
    internal fun stopGuest(result: PendingIntent) { stopResult = result; finishAndRemoveTask() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (guestResumed) guest?.let { runCatching { instrumentation.callActivityOnPause(it) } }
        if (active) guest?.let { runCatching { instrumentation.callActivityOnStop(it) } }
        guest?.let { runCatching { instrumentation.callActivityOnDestroy(it) } }; guest = null; active = false; guestResumed = false
        setIntent(intent); load(null)
    }
}
class GuestSlot0Activity : GuestHostActivity()
class GuestSlot1Activity : GuestHostActivity()
