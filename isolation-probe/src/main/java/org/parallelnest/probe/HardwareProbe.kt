package org.parallelnest.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.job.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.location.*
import android.media.*
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.sqrt

/** Explicit, bounded first-party observations. Successful emulator APIs are not hardware acceptance. */
class HardwareProbe(private val context: Context) {
    private val mutable=MutableStateFlow<Map<String,String>>(emptyMap())
    val results=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var scanner: BluetoothLeScanner?=null
    private var advertiser: BluetoothLeAdvertiser?=null
    private var gatt: BluetoothGatt?=null
    private var timeout: Job?=null
    private val addresses=linkedSetOf<String>()
    private var first: BluetoothDevice?=null
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private var observing=false
    private var sensorManager=context.getSystemService(android.hardware.SensorManager::class.java)
    private var sensorTimeout: Job?=null
    private var activeTone: ToneGenerator?=null
    fun result(feature: String,message: String){mutable.update { it+(feature to "$message · UID ${Process.myUid()}") }}
    private val network=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network){result("Network callback","Android reported an available network")}
        override fun onLost(network:Network){result("Network callback","Android reported network loss")}
        override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities){result("Network callback","Wi-Fi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, cellular=${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}, VPN=${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")}
    }
    fun startObserving(){if(!observing){connectivity.registerDefaultNetworkCallback(network);observing=true}}
    fun stopObserving(){if(observing){connectivity.unregisterNetworkCallback(network);observing=false};stopRadio();sensorTimeout?.cancel();sensorManager.unregisterListener(sensor);activeTone?.release();activeTone=null}
    @SuppressLint("MissingPermission") fun classic() {
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
        result("Bluetooth Classic","Adapter on=${adapter.isEnabled}; paired devices=${adapter.bondedDevices.size}. No physical connection tested.")
    }
    private val scan=object:ScanCallback(){
        override fun onScanResult(type:Int,value:ScanResult){
            if(addresses.size<64)addresses+=value.device.address
            if(first==null)first=value.device
            result("BLE scan","Actual scan callback; ${addresses.size} unique devices. RSSI=${value.rssi}")
        }
        override fun onScanFailed(error:Int){result("BLE scan","Android rejected scan: code $error")}
    }
    @SuppressLint("MissingPermission") fun bleScan(){
        stopRadio();addresses.clear();first=null
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
        check(adapter.isEnabled){"Turn Bluetooth on first"}
        scanner=adapter.bluetoothLeScanner ?: error("Android does not expose a BLE scanner")
        scanner!!.startScan(emptyList(),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),scan)
        result("BLE scan","Android scan requested; waiting for real callbacks")
        timeout=scope.launch{delay(10_000);scanner?.stopScan(scan);scanner=null;result("BLE scan","Scan ended with ${addresses.size} observed devices; no fabricated rows")}
    }
    @SuppressLint("MissingPermission") fun connectObserved(){
        val device=first ?: error("Scan and choose a nearby BLE test accessory first; no accessory was observed")
        scanner?.stopScan(scan);scanner=null
        gatt?.close()
        gatt=device.connectGatt(context,false,object:BluetoothGattCallback(){
            override fun onConnectionStateChange(client:BluetoothGatt,status:Int,newState:Int){
                result("BLE connection","Android callback status=$status state=$newState")
                if(status==BluetoothGatt.GATT_SUCCESS && newState==BluetoothProfile.STATE_CONNECTED)client.discoverServices()
            }
            override fun onServicesDiscovered(client:BluetoothGatt,status:Int){result("BLE services","Android callback status=$status; services=${client.services.size}")}
        },BluetoothDevice.TRANSPORT_LE)
        timeout?.cancel();timeout=scope.launch{delay(15_000);gatt?.disconnect();gatt?.close();gatt=null;result("BLE connection","Test connection closed")}
    }
    private val advertise=object:AdvertiseCallback(){
        override fun onStartSuccess(settings:AdvertiseSettings){result("BLE advertise","Android confirmed advertising started")}
        override fun onStartFailure(error:Int){result("BLE advertise","Android rejected advertising: code $error")}
    }
    @SuppressLint("MissingPermission") fun advertise(){
        stopRadio()
        val adapter=context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
        advertiser=adapter.bluetoothLeAdvertiser ?: error("No advertising API is exposed on this device")
        advertiser!!.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER).setConnectable(false).setTimeout(10_000).build(),AdvertiseData.Builder().addServiceUuid(ParcelUuid.fromString("2d1cc6b7-3ef0-4ef2-91b6-1a7982346bac")).build(),advertise)
        timeout=scope.launch{delay(10_000);advertiser?.stopAdvertising(advertise);advertiser=null}
    }
    @SuppressLint("MissingPermission") private fun stopRadio(){timeout?.cancel();runCatching{scanner?.stopScan(scan)};scanner=null;runCatching{advertiser?.stopAdvertising(advertise)};advertiser=null;runCatching{gatt?.disconnect();gatt?.close()};gatt=null}
    @SuppressLint("MissingPermission") suspend fun location():String {
        val service=context.getSystemService(LocationManager::class.java)
        check(service.isLocationEnabled){"Location services are disabled in Android"}
        val precise=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val providers=service.allProviders
        val provider=if(precise && LocationManager.GPS_PROVIDER in providers)LocationManager.GPS_PROVIDER else if(LocationManager.NETWORK_PROVIDER in providers)LocationManager.NETWORK_PROVIDER else error("No suitable provider for this grant")
        val value=CompletableDeferred<Location?>();val signal=CancellationSignal()
        try {
            service.getCurrentLocation(provider,signal,context.mainExecutor){value.complete(it)}
            val actual=withTimeout(15_000){value.await()} ?: error("Provider returned no location; no coordinate is invented")
            return "Android $provider response: ${actual.latitude}, ${actual.longitude}; accuracy=${actual.accuracy}m; precise=$precise; mock=${actual.isMock}. Verify on real hardware."
        }finally{signal.cancel()}
    }
    @SuppressLint("MissingPermission") suspend fun microphone():String=withContext(Dispatchers.IO){
        val size=AudioRecord.getMinBufferSize(16_000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        check(size>0){"Android does not support the test recording format"}
        val recorder=AudioRecord(MediaRecorder.AudioSource.MIC,16_000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(size,32_000))
        try {
            check(recorder.state==AudioRecord.STATE_INITIALIZED){"Microphone initialization failed"}
            recorder.startRecording();val buffer=ShortArray(2048);var samples=0;var squares=0.0
            while(samples<32_000){ensureActive();val read=recorder.read(buffer,0,minOf(buffer.size,32_000-samples));check(read>0){"AudioRecord failed: $read"};for(i in 0 until read)squares+=buffer[i].toDouble()*buffer[i];samples+=read}
            buffer.fill(0)
            "AudioRecord returned $samples PCM samples; RMS=${sqrt(squares/samples).toInt()}. No recording saved; physical input remains unverified."
        }finally{runCatching{recorder.stop()};recorder.release()}
    }
    suspend fun https():String=withContext(Dispatchers.IO){
        val connection=URL("https://example.com/").openConnection() as HttpsURLConnection
        try{connection.connectTimeout=8_000;connection.readTimeout=8_000;val status=connection.responseCode;val bytes=connection.inputStream.use{it.readNBytes(16_384).size};"HTTPS response $status; $bytes bytes using Android's normal TLS/network policy"}finally{connection.disconnect()}
    }
    fun notifyUser(){
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("probe","Hardware probe",NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(301,NotificationCompat.Builder(context,"probe").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Nest probe notification").setContentText("Profile UID ${Process.myUid()}").setAutoCancel(true).build())
        result("Notifications","NotificationManager accepted notify(); check actual delivery, sound and actions in this profile")
    }
    fun schedule(){
        val result=context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(302,ComponentName(context,ProbeJobService::class.java)).setMinimumLatency(1500).setOverrideDeadline(30_000).build())
        check(result==JobScheduler.RESULT_SUCCESS){"Android refused this scheduled job"}
        this.result("Background task","JobScheduler accepted job 302. Actual callback is recorded separately when Android runs it.")
    }
    private val sensor=object:android.hardware.SensorEventListener{
        override fun onSensorChanged(event:android.hardware.SensorEvent){result("Sensor event","${event.sensor.name}: ${event.values.take(3).joinToString()}")}
        override fun onAccuracyChanged(sensor:android.hardware.Sensor?,accuracy:Int){}
    }
    fun sensors(){val value=sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: error("No accelerometer exposed");sensorManager.registerListener(sensor,value,android.hardware.SensorManager.SENSOR_DELAY_NORMAL);sensorTimeout?.cancel();sensorTimeout=scope.launch{delay(3000);sensorManager.unregisterListener(sensor)}}
    fun speaker(){val audio=context.getSystemService(AudioManager::class.java);val routes=audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString{it.type.toString()};activeTone?.release();val tone=ToneGenerator(AudioManager.STREAM_MUSIC,30);activeTone=tone;tone.startTone(ToneGenerator.TONE_PROP_BEEP,500);scope.launch{delay(600);if(activeTone===tone){tone.release();activeTone=null}};result("Audio output","Requested a real test tone. Android route types: $routes; listener must verify physical output/headset transitions")}
    fun dispose(){stopObserving();scope.cancel()}
}
