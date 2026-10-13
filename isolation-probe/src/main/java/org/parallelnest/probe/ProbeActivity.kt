package org.parallelnest.probe

import android.os.Bundle
import android.os.Process
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class ProbeActivity : ComponentActivity() {
    private var bluetooth by mutableStateOf("Bluetooth has not been checked")
    private val nearbyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) inspectBluetooth() else bluetooth="Nearby devices denied in this profile"
    }
    private fun inspectBluetooth() {
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            bluetooth="Nearby devices denied in this profile"
            return
        }
        bluetooth=runCatching {
            val adapter=getSystemService(BluetoothManager::class.java)?.adapter
            if(adapter==null) "Bluetooth hardware unavailable"
            else "Bluetooth API accessible · ${if(adapter.isEnabled) "on" else "off"} · ${adapter.bondedDevices.size} paired devices"
        }.getOrElse { "Bluetooth check failed: ${it.message}" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("probe", MODE_PRIVATE)
        val user = getSystemService(UserManager::class.java)
        setContent {
            var value by remember { mutableStateOf(prefs.getString("value", "").orEmpty()) }
            var saved by remember { mutableStateOf(prefs.getString("value", "(empty)").orEmpty()) }
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Clone Apps Probe", style = MaterialTheme.typography.headlineMedium)
                Text(if (user.isManagedProfile) "WORK PROFILE" else "PERSONAL PROFILE")
                Text("UID: ${Process.myUid()} · Serial: ${user.getSerialNumberForUser(Process.myUserHandle())}")
                OutlinedTextField(value, { value = it }, label = { Text("Independent persistent value") })
                Button(onClick = { prefs.edit().putString("value", value).commit(); saved = value }) { Text("Save value") }
                Text("Saved: $saved")
                Text("This value is stored in this Android user's private SharedPreferences. It is not synchronized or uploaded. The current probe screen checks storage isolation and Bluetooth API access; additional hardware helpers in source are not completed acceptance tests.")
                Button(onClick={
                    if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED) inspectBluetooth()
                    else nearbyPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) { Text("Check Bluetooth") }
                Text(bluetooth)
                Text("Requests this app's Nearby devices consent and reads Android's adapter state and paired-device count. It does not scan, connect or upload device details.",style=MaterialTheme.typography.bodySmall)
            } } }
        }
    }
}
