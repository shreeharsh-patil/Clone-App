package org.parallelnest.probe

import android.os.Bundle
import android.os.Process
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class ProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("probe", MODE_PRIVATE)
        val user = getSystemService(UserManager::class.java)
        setContent {
            var value by remember { mutableStateOf(prefs.getString("value", "").orEmpty()) }
            var saved by remember { mutableStateOf(prefs.getString("value", "(empty)").orEmpty()) }
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nest Isolation Probe", style = MaterialTheme.typography.headlineMedium)
                Text(if (user.isManagedProfile) "WORK PROFILE" else "PERSONAL PROFILE")
                Text("UID: ${Process.myUid()} · Serial: ${user.getSerialNumberForUser(Process.myUserHandle())}")
                OutlinedTextField(value, { value = it }, label = { Text("Independent persistent value") })
                Button(onClick = { prefs.edit().putString("value", value).commit(); saved = value }) { Text("Save value") }
                Text("Saved: $saved")
                Text("This value is stored in this Android user's private SharedPreferences. No network or cross-profile code is present.")
            } } }
        }
    }
}
