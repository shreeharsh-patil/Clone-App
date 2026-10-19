package org.parallelnest.app

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.parallelnest.app.core.CloneInfo
import org.parallelnest.app.ui.*
import org.parallelnest.app.bluetooth.fileShareIntent

class MainActivity: ComponentActivity() {
    private val vm by viewModels<NestViewModel>()
    private val backup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let {
        lifecycleScope.launch { try {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(it)?.use { stream -> stream.write(vm.registry.backup().toByteArray()) } ?: error("Could not write document") }
            vm.report("Manager metadata exported")
        } catch(e:Exception){vm.report(e.message ?: "Export failed")} }
    } }
    private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        lifecycleScope.launch { try {
            val count=withContext(Dispatchers.IO) {
                val text=contentResolver.openInputStream(it)?.use { input -> val bytes=input.readNBytes(1_048_577);require(bytes.size<=1_048_576){"Backup exceeds 1 MiB"};bytes.toString(Charsets.UTF_8) } ?: error("Could not read document")
                vm.registry.restore(text,vm.profileEngine.installedEnvironmentKeys(),vm.engine.getInstances().filter { it.runtime!="missing" }.map { it.id }.toSet())
            };vm.report("Restored metadata for $count installed entries. App data was not restored.")
        }catch(e:Exception){vm.report(e.message ?: "Restore failed")} }
    } }
    private val diagnostics=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let {
        lifecycleScope.launch { try { withContext(Dispatchers.IO) {
            val text="Clone Apps ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE} API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nABIs ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n${vm.engine.status()}\nPrototype: first-party Activity hosting only. No physical hardware acceptance.\nSelected compatibility snapshot:\n${vm.state.value.report?.json() ?: "No legacy clone was checked"}\nNo credentials, Bluetooth addresses, location coordinates or protected app data included.\n"
            contentResolver.openOutputStream(it)?.use { stream -> stream.write(text.toByteArray()) } ?: error("Could not write report")
        };vm.report("Diagnostics exported") }catch(e:Exception){vm.report(e.message ?: "Export failed")} }
    } }
    private val bluetoothFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        runCatching { startActivity(Intent.createChooser(fileShareIntent(it,contentResolver.getType(it) ?: "application/octet-stream"),"Send selected file · choose Bluetooth")) }
            .onFailure { vm.report(it.message ?: "Android file sharing is unavailable") }
    } }
    private val startupPermissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
    }
    private fun requestStartupPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (checkSelfPermission(android.Manifest.permission.BODY_SENSORS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.BODY_SENSORS)
            }
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            startupPermissions.launch(permissionsToRequest.toTypedArray())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissions()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark=state.theme!="system" || isSystemInDarkTheme()
            MaterialTheme(colorScheme=if(dark)nestDarkColors() else lightColorScheme(primary=NestBlue,secondary=NestPurple)) {
                SideEffect { androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark } }
                NestUi(vm,UiActions(
                    version=BuildConfig.VERSION_NAME,debug=BuildConfig.DEBUG,
                    launch=vm::launch,
                    original={pkg -> runCatching { startActivity(packageManager.getLaunchIntentForPackage(pkg) ?: error("Original app is no longer installed")) }.onFailure { vm.report(it.message.orEmpty()) }},
                    bluetoothSettings={openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)},
                    bluetoothPermissions={openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true)},
                    bluetoothShare={bluetoothFile.launch(arrayOf("*/*"))},
                    backup={backup.launch("clone-apps-metadata.json")},restore={restore.launch(arrayOf("application/json"))},logs={diagnostics.launch("clone-apps-diagnostics.txt")},
                    repository={runCatching { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/shreeharsh-patil/Clone-App"))) }.onFailure { vm.report("No browser is available") }},
                    storage={openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)},shortcut=::pinShortcut
                ))
            }
        }
        if(savedInstanceState==null)handleShortcut()
    }
    private fun openSettings(action: String,app: Boolean=false) {
        runCatching { startActivity(if(app)Intent(action,android.net.Uri.fromParts("package",packageName,null)) else Intent(action)) }.onFailure { vm.report("This Android settings screen is unavailable") }
    }
    override fun onResume() { super.onResume();vm.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);handleShortcut() }
    private fun handleShortcut() {
        val id=intent.getStringExtra("instance") ?: return
        intent.removeExtra("instance")
        vm.launchShortcut(id)
    }
    private fun pinShortcut(row: CloneInfo) {
        val manager=getSystemService(ShortcutManager::class.java)
        if(!manager.isRequestPinShortcutSupported){vm.report("This launcher does not support shortcuts");return}
        val target=Intent(this,MainActivity::class.java).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("instance",row.id)
        manager.requestPinShortcut(ShortcutInfo.Builder(this,row.id).setShortLabel(row.name.take(40)).setIcon(Icon.createWithResource(this,R.mipmap.ic_launcher)).setIntent(target).build(),null)
    }
}
