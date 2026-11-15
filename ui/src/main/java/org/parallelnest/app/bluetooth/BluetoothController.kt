package org.parallelnest.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NearbyDevice(val address: String, val name: String, val bond: Int) {
    val status: String get() = when (bond) {
        BluetoothDevice.BOND_BONDED -> "Paired"
        BluetoothDevice.BOND_BONDING -> "Pairing · confirm on both devices"
        else -> "Not paired"
    }
}

data class BluetoothState(
    val supported: Boolean = false,
    val permitted: Boolean = false,
    val scanPermitted: Boolean = false,
    val enabled: Boolean = false,
    val turningOn: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<NearbyDevice> = emptyList(),
    val message: String? = null
)

/** Owns foreground discovery only. Android handles pairing and accessory profile connections. */
@SuppressLint("MissingPermission")
class BluetoothController(private val context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutable = MutableStateFlow(BluetoothState(supported = adapter != null))
    val state = mutable.asStateFlow()
    private val found = linkedMapOf<String, NearbyDevice>()
    private var paired = emptyMap<String, NearbyDevice>()
    private var registered = false
    private var classicScanning = false
    private var bleScan: Pair<BluetoothLeScanner, ScanCallback>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var deadline: Job? = null
    private var scanError: String? = null

    fun hasPermission() = has(Manifest.permission.BLUETOOTH_CONNECT)
    fun hasScanPermission() = has(Manifest.permission.BLUETOOTH_SCAN)
    private fun has(permission: String) =
        adapter != null && context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> if (classicScanning && hasPermission() && hasScanPermission()) {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    runCatching { record(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name, "Unnamed device") }
                        .onFailure { note("Classic device details are unavailable.") }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (classicScanning) {
                    classicScanning = false
                    finishIfIdle()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> refresh()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (device != null && bond != BluetoothDevice.ERROR) {
                        found[device.address]?.let { found[device.address] = it.copy(bond = bond) }
                    }
                    refresh()
                }
            }
        }
    }

    fun startObserving() {
        if (!registered) {
            // These protected broadcasts may come from Android's separate Bluetooth UID.
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }, Context.RECEIVER_EXPORTED)
            registered = true
        }
        refresh()
    }

    fun refresh() {
        val permitted = hasPermission()
        val scanPermitted = hasScanPermission()
        val stateCode = try {
            adapter?.state
        } catch (e: SecurityException) {
            Log.w("BluetoothController", "Adapter state unavailable", e)
            null
        }
        val enabled = stateCode == BluetoothAdapter.STATE_ON
        if (!permitted || !scanPermitted || !enabled) clearScan()
        if (!permitted || !enabled) found.clear()
        paired = if (enabled && permitted) {
            try {
                adapter?.bondedDevices.orEmpty().associate { device ->
                    device.address to NearbyDevice(device.address, device.name?.take(120) ?: "Unnamed device", device.bondState)
                }
            } catch (e: SecurityException) {
                note("Paired device details are unavailable. Check Nearby devices permission.")
                emptyMap()
            }
        } else emptyMap()
        mutable.update { it.copy(permitted = permitted, scanPermitted = scanPermitted, enabled = enabled,
            turningOn = stateCode == BluetoothAdapter.STATE_TURNING_ON,
            scanning = classicScanning || bleScan != null) }
        publishDevices()
    }

    fun onEnableResult(accepted: Boolean) {
        refresh()
        note(if (!accepted && !state.value.enabled) "Bluetooth is off. You can turn it on in Android settings." else null)
    }

    fun onPermissionResult(granted: Boolean) {
        refresh()
        note(if (granted) null else "Android denied Nearby devices access. You can change it in App permissions.")
    }

    fun scan() {
        refresh()
        val bluetooth = adapter ?: return
        if (!registered || !state.value.enabled || !hasPermission() || !hasScanPermission()) return
        if (classicScanning || bleScan != null) return
        found.clear()
        publishDevices()
        scanError = null
        try {
            // Android Classic inquiry is shared across apps. Leave existing discovery running.
            classicScanning = !bluetooth.isDiscovering && bluetooth.startDiscovery()
            if (!classicScanning) scanError = "Classic discovery could not start or is already running."
        } catch (e: SecurityException) {
            scanError = "Android denied Classic discovery."
        }

        try {
            val scanner = bluetooth.bluetoothLeScanner
            if (scanner != null) {
                val callback = object : ScanCallback() {
                    override fun onScanResult(type: Int, result: ScanResult) {
                        if (bleScan?.second !== this || !hasPermission() || !hasScanPermission()) return
                        runCatching { record(result.device, result.scanRecord?.deviceName ?: result.device.name, "BLE device") }
                            .onFailure { note("BLE device details are unavailable.") }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (bleScan?.second !== this) return
                        bleScan = null
                        scanError = "Android BLE scan failed ($errorCode)."
                        if (classicScanning) note("$scanError Classic discovery continues.") else finishIfIdle()
                    }
                }
                bleScan = scanner to callback
                scanner.startScan(callback)
            }
        } catch (e: IllegalStateException) {
            bleScan = null
            scanError = "BLE scanner is unavailable."
        } catch (e: SecurityException) {
            bleScan = null
            scanError = "Android denied BLE scanning."
        }
        if (!classicScanning && bleScan == null) {
            note("Android could not start discovery. ${scanError.orEmpty()}")
            return
        }
        mutable.update { it.copy(scanning = true, message = scanError ?: "Scanning for nearby Bluetooth devices…") }
        deadline = scope.launch {
            delay(15_000)
            clearScan()
            finishIfIdle()
        }
    }

    private fun record(device: BluetoothDevice, name: String?, fallback: String) {
        val address = device.address
        if (found.size >= 64 && address !in found) return
        found[address] = NearbyDevice(address, name?.take(120) ?: found[address]?.name ?: fallback, device.bondState)
        publishDevices()
    }

    private fun publishDevices() {
        val devices = (found + paired).values
            .sortedWith(compareByDescending<NearbyDevice> { it.bond == BluetoothDevice.BOND_BONDED }.thenBy { it.name.lowercase() })
        mutable.update { it.copy(devices = devices) }
    }

    private fun finishIfIdle() {
        if (classicScanning || bleScan != null) return
        deadline?.cancel()
        deadline = null
        mutable.update { it.copy(scanning = false, message = "Scan finished. ${scanError.orEmpty()}".trim()) }
    }

    private fun clearScan() {
        deadline?.cancel()
        deadline = null
        if (classicScanning) {
            classicScanning = false
            runCatching { adapter?.cancelDiscovery() }
                .onFailure { Log.w("BluetoothController", "Could not cancel Classic discovery", it) }
        }
        val previousScan = bleScan
        bleScan = null
        previousScan?.let { (scanner, callback) ->
            runCatching { scanner.stopScan(callback) }
                .onFailure { Log.w("BluetoothController", "Could not stop BLE scan", it) }
        }
    }

    fun stopScan() {
        val wasScanning = classicScanning || bleScan != null
        clearScan()
        mutable.update { it.copy(scanning = false, message = if (wasScanning) "Scan stopped" else it.message) }
    }

    fun pair(address: String) {
        if (!hasPermission()) {
            refresh()
            return
        }
        stopScan()
        runCatching {
            check(state.value.devices.any { it.address == address }) { "Device is no longer listed. Scan again." }
            val device = checkNotNull(adapter).getRemoteDevice(address)
            check(device.bondState == BluetoothDevice.BOND_NONE && device.createBond()) { "Android could not start pairing. Try Bluetooth settings." }
            note("Pairing requested. Confirm Android’s pairing prompts on both devices.")
            refresh()
        }.onFailure { note(it.message ?: "Pairing failed") }
    }

    private fun note(message: String?) { mutable.update { it.copy(message = message) } }

    fun stopObserving() {
        stopScan()
        if (registered) {
            context.unregisterReceiver(receiver)
            registered = false
        }
    }

    fun dispose() {
        stopObserving()
        scope.cancel()
    }
}

/** Only a URI selected through Android's picker is delegated, with a temporary read grant. */
fun fileShareIntent(uri: Uri, mime: String): Intent {
    require(uri.scheme=="content") { "Select a file through Android's document picker" }
    return Intent(Intent.ACTION_SEND).apply {
        type=mime
        putExtra(Intent.EXTRA_STREAM,uri)
        clipData=ClipData.newRawUri("Selected file",uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
