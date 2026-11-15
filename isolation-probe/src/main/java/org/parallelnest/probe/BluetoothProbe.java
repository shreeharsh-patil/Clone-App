package org.parallelnest.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Exercises platform callbacks inside the actual Activity, installed or hosted. */
@SuppressLint("MissingPermission")
final class BluetoothProbe {
    static final int PERMISSION_REQUEST = 5;
    private static final String[] PERMISSIONS = {
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN
    };
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    private final Activity activity;
    private final boolean hosted;
    private final TextView status;
    private final LinearLayout devices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private BluetoothGatt connection;

    BluetoothProbe(Activity activity, LinearLayout layout) {
        this.activity = activity;
        hosted = activity.getIntent().hasExtra("virtual_instance");
        status = new TextView(activity);
        devices = new LinearLayout(activity);
        devices.setOrientation(LinearLayout.VERTICAL);
        addButton(layout, "Run BLE scan", this::startScan);
        addButton(layout, "Stop Bluetooth test", this::stop);
        layout.addView(status);
        layout.addView(devices);
        show("Select a discovered device to read its standard BLE Battery Level characteristic.");
    }

    private void startScan() {
        stop();
        if (!authorized()) {
            if (hosted) {
                show("Enable Bluetooth for this instance in Clone Details and allow Android Nearby devices.");
            } else {
                activity.requestPermissions(PERMISSIONS, PERMISSION_REQUEST);
            }
            return;
        }
        try {
            BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                show("Bluetooth is off or unavailable. Check Android Bluetooth settings.");
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                show("Android returned no BLE scanner.");
                return;
            }
            scan = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    if (scan != this || !authorized() || seen.size() >= 16) return;
                    BluetoothDevice device = result.getDevice();
                    if (!seen.add(device.getAddress())) return;
                    String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                    addButton(devices, "Read battery · " + (name == null ? device.getAddress() : name),
                        () -> connect(device));
                    show("Received advertisements from " + seen.size() + " devices.");
                }

                @Override public void onScanFailed(int code) {
                    if (scan == this) finish("Android BLE scan failed (" + code + ").");
                }
            };
            scanner.startScan(scan);
            show("Waiting for Android BLE scan callbacks…");
            main.postDelayed(() -> {
                endScan();
                show("BLE scan finished. Received advertisements from " + seen.size() + " devices.");
            }, 12_000);
        } catch (IllegalStateException | SecurityException e) {
            finish("Unable to start BLE scan: " + e.getMessage());
        }
    }

    private void connect(BluetoothDevice device) {
        endScan();
        closeConnection();
        main.removeCallbacksAndMessages(null);
        if (!authorized()) {
            finish("Nearby devices authorization is required.");
            return;
        }
        BluetoothGattCallback callbacks = new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int code, int state) {
                handle(gatt, () -> {
                    if (code != BluetoothGatt.GATT_SUCCESS || state != BluetoothProfile.STATE_CONNECTED) {
                        finish("GATT disconnected or failed (" + code + ").");
                    } else if (gatt.discoverServices()) {
                        show("GATT connected. Discovering services…");
                    } else {
                        finish("Android rejected GATT service discovery.");
                    }
                });
            }

            @Override public void onServicesDiscovered(BluetoothGatt gatt, int code) {
                handle(gatt, () -> {
                    BluetoothGattService service = gatt.getService(BATTERY_SERVICE);
                    BluetoothGattCharacteristic level = service == null ? null : service.getCharacteristic(BATTERY_LEVEL);
                    if (code != BluetoothGatt.GATT_SUCCESS) finish("GATT service discovery failed (" + code + ").");
                    else if (level == null) finish("GATT connected. This device lacks the standard Battery Level characteristic.");
                    else if (!gatt.readCharacteristic(level)) finish("Android rejected the Battery Level read.");
                });
            }

            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                                       byte[] value, int code) {
                handle(gatt, () -> {
                    if (code == BluetoothGatt.GATT_SUCCESS && characteristic.getUuid().equals(BATTERY_LEVEL)
                        && value.length == 1 && (value[0] & 0xff) <= 100) {
                        finish("GATT Battery Level received: " + (value[0] & 0xff) + "%.");
                    } else {
                        finish("Battery Level read failed or returned invalid data (" + code + ").");
                    }
                });
            }
        };
        try {
            connection = device.connectGatt(activity, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (connection == null) {
                finish("Android returned no GATT connection.");
                return;
            }
            show("Waiting for Android GATT connection callback…");
            main.postDelayed(() -> finish("GATT test timed out."), 20_000);
        } catch (IllegalStateException | SecurityException e) {
            finish("GATT connection failed: " + e.getMessage());
        }
    }

    private void handle(BluetoothGatt gatt, Runnable callback) {
        main.post(() -> {
            if (connection != gatt) return;
            try {
                if (authorized()) callback.run();
                else finish("Nearby devices authorization was removed.");
            } catch (IllegalStateException | SecurityException e) {
                finish("GATT operation failed: " + e.getMessage());
            }
        });
    }

    void permissionResult() {
        if (authorized()) startScan();
        else show("Android Nearby devices permission was denied.");
    }

    private boolean authorized() {
        for (String permission : PERMISSIONS) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    void stop() {
        finish("Bluetooth test stopped.");
        devices.removeAllViews();
        seen.clear();
    }

    private void finish(String message) {
        main.removeCallbacksAndMessages(null);
        endScan();
        closeConnection();
        show(message);
    }

    private void endScan() {
        ScanCallback callback = scan;
        scan = null;
        try {
            if (scanner != null && callback != null) scanner.stopScan(callback);
        } catch (IllegalStateException | SecurityException e) {
            android.util.Log.w("BluetoothProbe", "Android could not stop scanning", e);
        } finally {
            scanner = null;
        }
    }

    private void closeConnection() {
        BluetoothGatt gatt = connection;
        connection = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException e) {
                android.util.Log.w("BluetoothProbe", "Android revoked access during GATT cleanup", e);
            }
        }
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    private void show(String message) {
        status.setText(message);
    }
}
