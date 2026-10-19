package org.parallelnest.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.parallelnest.app.bluetooth.BluetoothController

@Composable fun BluetoothScreen(settings: () -> Unit, permissions: () -> Unit, shareFile: () -> Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val controller=remember { BluetoothController(context.applicationContext) }
    val state by controller.state.collectAsStateWithLifecycle()
    var pendingScan by remember { mutableStateOf(false) }
    val nearby=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.refresh()
        if(pendingScan && controller.hasScanPermission() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))controller.scan()
        pendingScan=false
    }
    val enable=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        controller.onEnableResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    val connectPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
    }
    val scanPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    DisposableEffect(lifecycle,controller) {
        val observer=LifecycleEventObserver { _,event ->
            when(event) {
                Lifecycle.Event.ON_START -> controller.startObserving()
                Lifecycle.Event.ON_STOP -> controller.stopObserving()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.startObserving()
        onDispose { lifecycle.removeObserver(observer);controller.dispose() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text("Bluetooth",style=MaterialTheme.typography.headlineLarge)
            Text("Find devices, pair through Android and send a file you choose.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(when { !state.supported -> "Bluetooth unavailable on this device"; !state.permitted -> "Nearby devices permission required"; state.turningOn -> "Turning on Bluetooth…"; state.enabled -> "Bluetooth is on"; else -> "Bluetooth is off" },style=MaterialTheme.typography.titleMedium)
                if(state.supported && !state.permitted) {
                    Text("Allow Nearby devices to view paired devices and request pairing. Discovery asks for its scan permission only when you use Scan. Device details stay on your phone.")
                    Button(onClick={if(connectPermissions.isNotEmpty()) nearby.launch(connectPermissions) else controller.refresh()}) { Text("Allow Nearby devices") }
                    TextButton(onClick=permissions) { Text("App permission settings") }
                } else if(state.supported) {
                    if(!state.enabled) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick={
                                    controller.onEnableRequested()
                                    runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { settings() }
                                },
                                enabled=!state.turningOn
                            ) { Text(if(state.turningOn) "Turning on…" else "Turn on Bluetooth") }
                            OutlinedButton(onClick=settings) { Text("Bluetooth settings") }
                        }
                    } else {
                        Button(onClick={if(state.scanning)controller.stopScan() else if(state.scanPermitted)controller.scan() else {pendingScan=true;nearby.launch(scanPermissions)}},modifier=Modifier.fillMaxWidth()) { Text(if(state.scanning) "Stop scan" else "Scan for devices") }
                    }
                    if(state.scanning || state.turningOn) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.enabled) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={controller.refresh()}){Text("Refresh devices")}
                            TextButton(onClick=settings){Text("Bluetooth settings")}
                        }
                    }
                }
                state.message?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
        }
        item {
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Send a file",style=MaterialTheme.typography.titleMedium)
                    Text("Choose a file, then select Bluetooth in Android's share menu and choose the receiving device. Android handles the transfer and its progress. Bluetooth sharing must be available on your device.")
                    OutlinedButton(onClick={controller.stopScan();shareFile()},modifier=Modifier.fillMaxWidth()){Text("Choose a file to send")}
                }
            }
        }
        if(state.permitted && state.enabled) {
            item { Text("Paired & discovered devices",style=MaterialTheme.typography.titleMedium) }
            if(state.devices.isEmpty()) item { Text(if(state.scanning) "Searching… Make the other device discoverable." else "No devices listed. Make the other device discoverable and scan, or open Bluetooth settings for accessory setup.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.devices,key={it.address}) { device ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(device.name,style=MaterialTheme.typography.titleMedium)
                        Text("${device.status}\n${device.address}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(device.bond==BluetoothDevice.BOND_NONE) {
                            OutlinedButton(onClick={controller.pair(device.address)}) { Text("Pair device") }
                        } else if(device.bond==BluetoothDevice.BOND_BONDED) {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick=settings) { Text("Connect in settings") }
                                TextButton(onClick=settings) { Text("Device settings") }
                            }
                        }
                    }
                }
            }
        }
        item { Text("Some accessories need their own companion app. Open Android Bluetooth settings if your device is not found. Files can be sent to devices that support Bluetooth file sharing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
