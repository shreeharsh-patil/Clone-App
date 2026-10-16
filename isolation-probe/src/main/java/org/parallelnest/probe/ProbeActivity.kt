package org.parallelnest.probe

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*

/** Standalone hardware inspection, separate from the restricted guest fixture. */
class ProbeActivity : Activity() {
    private lateinit var result: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 64, 24, 24) }
        result = TextView(this).apply { text = "Bluetooth has not been checked" }
        layout.addView(Button(this).apply { text = "Check Bluetooth"; setOnClickListener {
            if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) inspect()
            else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } })
        layout.addView(result); setContentView(layout)
    }
    private fun inspect() {
        result.text = if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) "Nearby devices denied"
        else runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.let { "Bluetooth API accessible · ${if(it.isEnabled) "on" else "off"} · ${it.bondedDevices.size} paired devices" } ?: "Bluetooth hardware unavailable" }.getOrElse { "Bluetooth check failed: ${it.message}" }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); inspect() }
}
