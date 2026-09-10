package com.lexi.rfid;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.Collections;
import java.util.UUID;

/**
 * BLE manager for Chameleon Ultra (and any Nordic NUS-based device).
 *
 * Chameleon Ultra advertises the Nordic UART Service (NUS):
 *   Service  6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   TX char  6E400002-B5A3-F393-E0A9-E50E24DCCA9E  (write to device)
 *   RX char  6E400003-B5A3-F393-E0A9-E50E24DCCA9E  (notify from device)
 */
@SuppressLint("MissingPermission")
public class ChameleonBleManager {

    public static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_TX      = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_RX      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 12_000;

    public interface BleListener {
        void onDeviceFound(BluetoothDevice device, String name, int rssi);
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onDataReceived(String data);
        void onError(String message);
    }

    private final Context context;
    private final android.bluetooth.BluetoothAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BleListener listener;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic txChar;
    private BluetoothGattCharacteristic rxChar;
    private boolean scanning = false;

    public ChameleonBleManager(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = (bm != null) ? bm.getAdapter() : null;
    }

    public boolean isBluetoothAvailable() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isConnected() {
        return gatt != null && txChar != null;
    }

    // ── Scanning ──────────────────────────────────────────────────────────

    public void startScan(BleListener cb) {
        this.listener = cb;
        if (adapter == null || !adapter.isEnabled()) {
            cb.onError("Bluetooth is not available or not enabled");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            cb.onError("BLE scanner unavailable");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        // Scan for anything advertising NUS service (Chameleon Ultra does this)
        scanner.startScan(
            Collections.singletonList(
                new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(NUS_SERVICE))
                    .build()),
            settings,
            scanCallback);
        scanning = true;
        handler.postDelayed(this::stopScan, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        if (scanning && scanner != null) {
            scanning = false;
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        handler.removeCallbacksAndMessages(null);
    }

    public boolean isScanning() { return scanning; }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            String name = dev.getName();
            if (name == null) name = "Chameleon Ultra";
            final String finalName = name;
            handler.post(() -> {
                if (listener != null) listener.onDeviceFound(dev, finalName, result.getRssi());
            });
        }
        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            handler.post(() -> {
                if (listener != null) listener.onError("BLE scan failed (code " + errorCode + ")");
            });
        }
    };

    // ── GATT connection ───────────────────────────────────────────────────

    public void connect(BluetoothDevice device, BleListener cb) {
        this.listener = cb;
        stopScan();
        disconnect();
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    public void disconnect() {
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        txChar = null;
        rxChar = null;
    }

    public boolean sendCommand(String command) {
        if (gatt == null || txChar == null) return false;
        byte[] data = (command.endsWith("\r\n") ? command : command + "\r\n").getBytes();
        txChar.setValue(data);
        txChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        return gatt.writeCharacteristic(txChar);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                txChar = null;
                rxChar = null;
                gatt = null;
                handler.post(() -> { if (listener != null) listener.onDisconnected(); });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> {
                    if (listener != null) listener.onError("Service discovery failed");
                });
                return;
            }
            BluetoothGattService svc = g.getService(NUS_SERVICE);
            if (svc == null) {
                handler.post(() -> {
                    if (listener != null) listener.onError("Nordic UART Service not found on device");
                });
                return;
            }
            txChar = svc.getCharacteristic(NUS_TX);
            rxChar = svc.getCharacteristic(NUS_RX);
            // Enable notifications on RX
            if (rxChar != null) {
                g.setCharacteristicNotification(rxChar, true);
                BluetoothGattDescriptor desc = rxChar.getDescriptor(CCCD);
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(desc);
                }
            }
            handler.post(() -> {
                if (listener != null) listener.onConnected(g.getDevice());
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            String data = new String(c.getValue());
            handler.post(() -> {
                if (listener != null) listener.onDataReceived(data);
            });
        }
    };
}
