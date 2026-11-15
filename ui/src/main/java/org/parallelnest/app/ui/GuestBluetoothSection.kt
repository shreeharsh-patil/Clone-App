package org.parallelnest.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import org.parallelnest.app.core.CloneInfo
import org.parallelnest.app.engine.virtualization.VirtualContainerEngine

@Composable
internal fun GuestBluetoothSection(
    row: CloneInfo,
    vm: NestViewModel,
    state: NestState,
    appSettings: () -> Unit,
    profileGuide: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permissions = remember { arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) }
    val hasBle = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) }
    fun authorized() = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var hostAuthorized by remember { mutableStateOf(authorized()) }
    var permissionMessage by remember(row.id) { mutableStateOf<String?>(null) }
    var consent by remember(row.id) { mutableStateOf<Boolean?>(null) }
    var status by remember(row.id) { mutableStateOf("Checking the imported APK…") }
    val probe = row.compatibility == VirtualContainerEngine.COMPATIBILITY

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hostAuthorized = authorized()
        if (hostAuthorized) vm.setBluetoothConsent(row, true)
        else permissionMessage = "Android denied Nearby devices access. You can change it in App permissions."
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hostAuthorized = authorized()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(row.id, row.versionCode, state.bluetoothRevision) {
        if (probe) {
            try {
                consent = vm.bluetoothConsent(row)
                status = if (consent == null) "Update this probe with the Bluetooth test APK." else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consent = null
                status = "Could not read guest Bluetooth capability. ${e.message.orEmpty()}"
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Guest Bluetooth", style = MaterialTheme.typography.titleMedium)
            Text(if (hasBle) "Android reports Bluetooth LE hardware." else "Android reports no Bluetooth LE hardware.")
            if (!probe) {
                Text("Bluetooth mediation is unavailable in this runtime. Use a system-managed copy for apps that need Bluetooth.")
                TextButton(onClick = profileGuide) { Text("Separate app setup") }
            } else if (status.isNotEmpty()) {
                Text(status)
            } else {
                Text("The probe can scan for BLE devices and attempt to read a standard Battery Level characteristic.")
                Text(if (hostAuthorized) "Nearby devices authorization granted to Clone Apps." else "Clone Apps needs Android Nearby devices authorization for scanning and connecting.")
                Text(if (consent == true) "Bluetooth is allowed for this instance." else "Bluetooth is off for this instance.")
                permissionMessage?.let { Text(it) }
                Button(enabled = !state.busy && (hasBle || consent == true), onClick = {
                    permissionMessage = null
                    if (consent == true) vm.setBluetoothConsent(row, false)
                    else if (authorized()) vm.setBluetoothConsent(row, true)
                    else request.launch(permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray())
                }) { Text(if (consent == true) "Remove instance consent" else "Allow for this instance") }
                if (!hostAuthorized) TextButton(onClick = appSettings) { Text("App permissions") }
            }
        }
    }
}
