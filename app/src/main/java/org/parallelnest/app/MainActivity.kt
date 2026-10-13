package org.parallelnest.app

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.parallelnest.app.core.*
import org.parallelnest.app.ui.*

class MainActivity : FragmentActivity() {
    private val vm by viewModels<NestViewModel>()
    private var pinAction by mutableStateOf<(() -> Unit)?>(null)
    private var pinSetup by mutableStateOf(false)
    private val secure by lazy { SecureStore(this) }
    private val provision = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> vm.report(if(result.resultCode==RESULT_OK) "Profile setup completed. Resume the profile and refresh if needed." else "Profile setup cancelled or rejected by Android"); vm.refresh() }
    private val backup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let {
        lifecycleScope.launch { try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(it)?.use { stream -> stream.write(vm.registry.backup().toByteArray()) } ?: error("Could not write document") }; vm.report("Manager metadata exported") } catch(e:Exception){vm.report(e.message ?: "Export failed")} }
    } }
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
        lifecycleScope.launch { try {
            val count=withContext(Dispatchers.IO) {
                val text=contentResolver.openInputStream(it)?.use { input -> val bytes=input.readNBytes(1_048_577);require(bytes.size<=1_048_576){"Backup exceeds 1 MiB"};bytes.toString(Charsets.UTF_8) } ?: error("Could not read document")
                val available=vm.engine.installedEnvironmentKeys()
                vm.registry.restore(text,available)
            };vm.report("Restore processed $count installed entries; app data was not restored")
        } catch(e:Exception){vm.report(e.message ?: "Restore failed")} }
    } }
    private val diagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let {
        lifecycleScope.launch { try { withContext(Dispatchers.IO) {
            val state=vm.engine.status()
            val text="ParallelNest ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE} API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nABIs ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n$state\nVirtual engine: disabled/unverified\nNo app list, credentials or protected app data included.\n"
            contentResolver.openOutputStream(it)?.use { stream -> stream.write(text.toByteArray()) } ?: error("Could not write report")
        };vm.report("Diagnostics exported") }catch(e:Exception){vm.report(e.message ?: "Export failed")} }
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val dark=when(state.theme){"light"->false;"dark"->true;else->isSystemInDarkTheme()}
            val colors=if(state.dynamic) if(dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this) else if(dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme=colors) {
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                NestUi(vm,UiActions(
                    provision={runCatching{provision.launch(vm.engine.provisioningIntent())}.onFailure{vm.report(it.message ?: "No provisioning handler")}},
                    launch={row-> if(row.locked) authenticate{vm.launch(row)} else vm.launch(row)},
                    original={pkg->runCatching{startActivity(packageManager.getLaunchIntentForPackage(pkg) ?: error("Original app is no longer installed"))}.onFailure{vm.report(it.message.orEmpty())}},
                    profileSettings={runCatching{startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))}.onFailure{vm.report("Open Android settings to manage work profiles")}},
                    bluetoothSettings={runCatching{startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}.onFailure{vm.report("Open Android settings to pair your Bluetooth device")}},
                    lock={row,locked-> if(locked) {
                        val canAuth=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)==BiometricManager.BIOMETRIC_SUCCESS
                        if(canAuth || secure.hasPin()) authenticate{vm.lock(row,true)} else vm.report("Set an Android screen lock or fallback PIN before locking launches")
                    } else authenticate{vm.lock(row,false)}},
                    pinSetup={if(secure.hasPin()) authenticate{pinSetup=true} else {
                        if(getSystemService(KeyguardManager::class.java).isDeviceSecure) authenticate{pinSetup=true} else pinSetup=true
                    }},
                    backup={backup.launch("parallelnest-metadata.json")}, restore={restore.launch(arrayOf("application/json"))},logs={diagnostics.launch("parallelnest-diagnostics.txt")},shortcut={row->pinShortcut(row)}
                ))
                if(pinAction!=null || pinSetup) {
                    var value by remember { mutableStateOf("") }
                    var confirm by remember { mutableStateOf("") }
                    var working by remember { mutableStateOf(false) }
                    val setup=pinSetup
                    AlertDialog(onDismissRequest={if(!working){pinAction=null;pinSetup=false}},title={Text(if(setup) "Set fallback PIN" else "Unlock launch")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text(if(setup) "Use 6–32 digits. This PIN protects manager launches only." else "Enter your PIN. Five failures trigger a 30-second retry delay.")
                        OutlinedTextField(value,{value=it.filter(Char::isDigit).take(32)},label={Text("PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                        if(setup) OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(32)},label={Text("Confirm PIN")},visualTransformation=PasswordVisualTransformation())
                    }},confirmButton={TextButton(enabled=!working && if(setup) value.length>=6 && value==confirm else value.isNotEmpty(),onClick={
                        working=true
                        lifecycleScope.launch {
                            try {
                                if(setup){withContext(Dispatchers.IO){secure.setPin(value.toCharArray())};pinSetup=false;vm.report("Encrypted PIN verifier saved")}
                                else { val valid=withContext(Dispatchers.IO){secure.checkPin(value.toCharArray())};if(valid){val action=pinAction;pinAction=null;action?.invoke()}else vm.report("Incorrect PIN or retry delay active") }
                                value="";confirm=""
                            }catch(e:Exception){vm.report(e.message ?: "Authentication failed")}finally{working=false}
                        }
                    }){Text(if(setup) "Save" else "Unlock")}},dismissButton={TextButton(onClick={pinAction=null;pinSetup=false},enabled=!working){Text("Cancel")}})
                }
            }
        }
        if(savedInstanceState==null) handleShortcut()
    }
    override fun onResume() { super.onResume();vm.refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);handleShortcut() }
    private fun handleShortcut() {
        val id=intent.getStringExtra("instance") ?: return
        intent.removeExtra("instance")
        lifecycleScope.launch {
            val row=withContext(Dispatchers.IO){vm.engine.getInstances().find{it.id==id}}
            if(row==null)vm.report("Shortcut points to an unavailable work app") else if(row.locked)authenticate{vm.launch(row)} else vm.launch(row)
        }
    }
    private fun authenticate(action: () -> Unit) {
        val auth=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(BiometricManager.from(this).canAuthenticate(auth)!=BiometricManager.BIOMETRIC_SUCCESS){if(secure.hasPin())pinAction=action else vm.report("Set a device credential or fallback PIN first");return}
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){action()}
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence){if(secure.hasPin())pinAction=action else vm.report(errString.toString())}
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock ParallelNest launch").setSubtitle("Authenticate for this operation").setAllowedAuthenticators(auth).build())
    }
    private fun pinShortcut(row: CloneInfo) {
        val manager=getSystemService(ShortcutManager::class.java)
        if(!manager.isRequestPinShortcutSupported){vm.report("This launcher does not support pinned shortcuts");return}
        val intent=Intent(this,MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("instance",row.id)
        val shortcut=ShortcutInfo.Builder(this,row.id).setShortLabel(row.name.take(40)).setIcon(Icon.createWithResource(this,R.drawable.ic_nest)).setIntent(intent).build()
        manager.requestPinShortcut(shortcut,null)
    }
}
