package org.parallelnest.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NearbyDevice(val address: String, val name: String, val bond: Int) {
    val status: String get() = when(bond) {
        BluetoothDevice.BOND_BONDED -> "Paired"
        BluetoothDevice.BOND_BONDING -> "Pairing · confirm on both devices"
        else -> "Not paired"
    }
}
data class BluetoothState(val supported: Boolean = false, val permitted: Boolean = false, val scanPermitted: Boolean = false, val enabled: Boolean = false,
                          val turningOn: Boolean = false, val scanning: Boolean = false, val devices: List<NearbyDevice> = emptyList(), val message: String? = null)

/** Foreground, user-requested classic discovery. No service, polling, persistence or device uploads. */
class BluetoothController(private val context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutable = MutableStateFlow(BluetoothState(supported=adapter!=null))
    val state = mutable.asStateFlow()
    private val found = linkedMapOf<String,NearbyDevice>()
    private var registered = false
    private var ownsDiscovery = false
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var deadline: Job? = null
    private var pollingJob: Job? = null

    fun hasPermission(): Boolean {
        if (adapter == null) return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasScanPermission(): Boolean {
        if (adapter == null) return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            val admin = context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            val location = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            admin && location
        }
    }

    private val receiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> if(ownsDiscovery && hasPermission()) {
                    runCatching {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)?.let { device ->
                            if(found.size<64 || found.containsKey(device.address)) found[device.address]=entry(device)
                        }
                    }.onFailure { mutable.update { s -> s.copy(message="Device details unavailable; check Nearby devices permission") } }
                    refresh()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if(ownsDiscovery) {
                    ownsDiscovery=false;deadline?.cancel()
                    mutable.update { it.copy(scanning=false,message="Scan finished. Only discoverable classic Bluetooth devices appear here.") }
                    refresh()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val stateExtra = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (stateExtra) {
                        BluetoothAdapter.STATE_TURNING_ON -> {
                            mutable.update { it.copy(turningOn = true, message = "Turning on Bluetooth…") }
                            startPollingForState()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            pollingJob?.cancel()
                            mutable.update { it.copy(turningOn = false, enabled = true, message = null) }
                            refresh()
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            stopScan()
                            mutable.update { it.copy(turningOn = false, enabled = false, message = "Turning off Bluetooth…") }
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            pollingJob?.cancel()
                            stopScan()
                            mutable.update { it.copy(turningOn = false, enabled = false, message = null) }
                            refresh()
                        }
                        else -> refresh()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refresh()
            }
        }
    }

    fun startObserving() {
        if(!registered) {
            // Bluetooth broadcasts originate from a privileged Bluetooth UID,
            // rather than the system UID; these platform actions are protected.
            context.registerReceiver(receiver,IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },Context.RECEIVER_EXPORTED)
            registered=true
        }
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun entry(device: BluetoothDevice) = NearbyDevice(device.address,device.name?.take(120) ?: "Unnamed device",device.bondState)

    @SuppressLint("MissingPermission")
    fun refresh() {
        val hwEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val stateCode = runCatching { adapter?.state }.getOrNull()
        val isTurningOn = stateCode == BluetoothAdapter.STATE_TURNING_ON
        if(!hasPermission()) {
            mutable.update {
                it.copy(
                    permitted = false,
                    scanPermitted = hasScanPermission(),
                    enabled = hwEnabled,
                    turningOn = isTurningOn,
                    devices = emptyList(),
                    scanning = false
                )
            }
            return
        }
        runCatching {
            val enabled = hwEnabled || stateCode == BluetoothAdapter.STATE_ON
            if(!enabled && !isTurningOn) stopScan()
            val devices = if(enabled) {
                val paired = adapter?.bondedDevices?.associate { it.address to entry(it) } ?: emptyMap()
                found.mapValues { (address,value) ->
                    val remoteBond = runCatching { adapter?.getRemoteDevice(address)?.bondState }.getOrNull() ?: value.bond
                    value.copy(bond = remoteBond)
                } + paired
            } else emptyMap()
            mutable.update {
                it.copy(
                    permitted = true,
                    scanPermitted = hasScanPermission(),
                    enabled = enabled,
                    turningOn = isTurningOn,
                    devices = devices.values.sortedWith(compareByDescending<NearbyDevice>{it.bond==BluetoothDevice.BOND_BONDED}.thenBy{it.name.lowercase()})
                )
            }
        }.onFailure { mutable.update { s -> s.copy(message="Bluetooth state unavailable: ${it.message}") } }
    }

    fun onEnableRequested() {
        mutable.update { it.copy(turningOn = true, message = "Turning on Bluetooth…") }
        startPollingForState()
    }

    fun onEnableResult(accepted: Boolean) {
        if (accepted) {
            mutable.update { it.copy(turningOn = true, message = "Turning on Bluetooth…") }
            startPollingForState()
        } else {
            refresh()
            if (adapter?.isEnabled != true) {
                mutable.update { it.copy(turningOn = false, message = "Could not turn on Bluetooth. Please enable it in Bluetooth settings.") }
            }
        }
    }

    private fun startPollingForState() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            for (i in 0 until 20) {
                delay(300)
                val state = runCatching { adapter?.state }.getOrNull()
                val isNowOn = adapter?.isEnabled == true || state == BluetoothAdapter.STATE_ON
                if (isNowOn) {
                    refresh()
                    mutable.update { it.copy(turningOn = false, enabled = true, message = null) }
                    return@launch
                }
            }
            refresh()
            mutable.update { it.copy(turningOn = false) }
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if(!hasPermission() || !hasScanPermission()) { refresh();return }
        runCatching {
            check(adapter?.isEnabled==true) { "Turn Bluetooth on before scanning" }
            check(!adapter.isDiscovering) { "Another Bluetooth discovery is active; try again after it finishes" }
            found.clear();refresh()
            check(adapter.startDiscovery()) { "Android could not start discovery. Try Bluetooth settings or another device." }
            ownsDiscovery=true
            mutable.update { it.copy(scanning=true,message="Scanning for discoverable classic Bluetooth devices…") }
            deadline?.cancel()
            deadline=scope.launch { delay(15_000); stopScan();mutable.update { it.copy(message="Scan finished. Make the other device discoverable and scan again if needed.") } }
        }.onFailure { mutable.update { s -> s.copy(message=it.message ?: "Discovery failed",scanning=false) } }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        deadline?.cancel();deadline=null
        val wasScanning=ownsDiscovery
        if(ownsDiscovery && hasScanPermission()) runCatching { adapter?.cancelDiscovery() }
        ownsDiscovery=false
        mutable.update { it.copy(scanning=false,message=if(wasScanning) "Scan stopped" else it.message) }
    }

    @SuppressLint("MissingPermission")
    fun pair(address: String) {
        if(!hasPermission()) { refresh();return }
        stopScan()
        runCatching {
            check(mutable.value.devices.any { it.address==address }) { "Device is no longer in this list; scan again" }
            check(adapter?.isEnabled==true) { "Turn Bluetooth on first" }
            val device=adapter.getRemoteDevice(address)
            check(device.bondState==BluetoothDevice.BOND_NONE && device.createBond()) { "Android could not start pairing; use Bluetooth settings" }
            mutable.update { it.copy(message="Pairing requested. Confirm any Android pairing prompt on both devices.") }
            refresh()
        }.onFailure { mutable.update { s -> s.copy(message=it.message ?: "Pairing failed") } }
    }

    fun stopObserving() {
        stopScan()
        pollingJob?.cancel()
        if(registered) { context.unregisterReceiver(receiver);registered=false }
    }

    fun dispose() { stopObserving();scope.cancel() }
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
