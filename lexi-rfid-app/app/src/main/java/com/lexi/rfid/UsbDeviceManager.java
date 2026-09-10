package com.lexi.rfid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * USB device detection and connection manager.
 *
 * Detects:
 *   LEXI RFID/NFC Duplicator — CP210x (Silicon Labs), CH340/CH341 (Qinheng), FTDI FT232
 *   Chameleon Mini / Chameleon Ultra — VID 0x16D0
 *   Generic CDC-ACM serial devices
 *
 * Works over USB-C to USB-A adapters (the adapter is transparent; Android sees the
 * downstream device's VID/PID as-is).
 */
public class UsbDeviceManager {

    public static final String ACTION_USB_PERMISSION = "com.lexi.rfid.USB_PERMISSION";

    public interface UsbListener {
        void onDeviceConnected(UsbDevice device, DeviceType type);
        void onDeviceDisconnected(UsbDevice device);
        void onPermissionDenied(UsbDevice device);
        void onDataReceived(String data);
        void onError(String error);
    }

    public enum DeviceType {
        CHAMELEON_MINI,
        CHAMELEON_ULTRA,
        LEXI_RFID,
        GENERIC_SERIAL,
        UNKNOWN
    }

    // ── Silicon Labs CP210x family ────────────────────────────────────────
    private static final int VID_SILABS = 0x10C4;
    private static final int PID_CP2102 = 0xEA60;  // CP2102/CP2109
    private static final int PID_CP2104 = 0xEA70;  // CP2104
    private static final int PID_CP2108 = 0xEA71;  // CP2108
    private static final int PID_CP2110 = 0xEA80;  // CP2110
    private static final int PID_CP2112 = 0xEA90;  // CP2112

    // ── Qinheng CH340 / CH341 family ─────────────────────────────────────
    private static final int VID_QINHENG = 0x1A86;
    private static final int PID_CH340   = 0x7523;  // CH340
    private static final int PID_CH341   = 0x5523;  // CH341
    private static final int PID_CH341A  = 0x5512;  // CH341A
    private static final int PID_CH9102  = 0x55D4;  // CH9102 (newer variant)

    // ── FTDI ──────────────────────────────────────────────────────────────
    private static final int VID_FTDI   = 0x0403;
    private static final int PID_FT232  = 0x6001;
    private static final int PID_FT2232 = 0x6010;
    private static final int PID_FT4232 = 0x6011;
    private static final int PID_FT232H = 0x6014;
    private static final int PID_FT230X = 0x6015;

    // ── Prolific PL2303 ───────────────────────────────────────────────────
    private static final int VID_PROLIFIC = 0x067B;
    private static final int PID_PL2303   = 0x2303;

    // ── Chameleon Mini / Ultra ────────────────────────────────────────────
    private static final int VID_CHAMELEON        = 0x16D0;
    private static final int PID_CHAMELEON_MINI   = 0x04B2;
    private static final int PID_CHAMELEON_ULTRA  = 0x06C2;

    // ── Atmel/Microchip CDC-ACM (alternate Chameleon firmware) ────────────
    private static final int VID_ATMEL  = 0x03EB;
    private static final int PID_ATMEL_CDC = 0x2404;

    private final Context context;
    private final UsbManager usbManager;
    private final UsbListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private UsbDevice connectedDevice;
    private UsbDeviceConnection connection;
    private DeviceType connectedType = DeviceType.UNKNOWN;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (dev != null) openDevice(dev);
                } else {
                    if (dev != null) mainHandler.post(() -> listener.onPermissionDenied(dev));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && classify(dev) != DeviceType.UNKNOWN) {
                    requestPermissionAndConnect(dev);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev != null && connectedDevice != null &&
                        dev.getDeviceId() == connectedDevice.getDeviceId()) {
                    closeDevice();
                    mainHandler.post(() -> listener.onDeviceDisconnected(dev));
                }
            }
        }
    };

    public UsbDeviceManager(Context context, UsbListener listener) {
        this.context = context;
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void register() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void unregister() {
        try { context.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }

    /** All recognized devices currently connected. */
    public List<UsbDevice> findSupportedDevices() {
        List<UsbDevice> result = new ArrayList<>();
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice dev : devices.values()) {
            if (classify(dev) != DeviceType.UNKNOWN) result.add(dev);
        }
        return result;
    }

    /** Classify a USB device by VID/PID and class. Public for use in UI. */
    public DeviceType classify(UsbDevice dev) {
        int vid = dev.getVendorId();
        int pid = dev.getProductId();

        // Chameleon by VID/PID
        if (vid == VID_CHAMELEON) {
            if (pid == PID_CHAMELEON_MINI) return DeviceType.CHAMELEON_MINI;
            if (pid == PID_CHAMELEON_ULTRA) return DeviceType.CHAMELEON_ULTRA;
        }

        // Chameleon by product name (alternate firmware VIDs)
        String name = dev.getProductName();
        if (name != null) {
            String lc = name.toLowerCase();
            if (lc.contains("chameleon")) {
                return lc.contains("ultra") ? DeviceType.CHAMELEON_ULTRA : DeviceType.CHAMELEON_MINI;
            }
            if (lc.contains("lexi") || lc.contains("rfid") || lc.contains("nfc duplicat")) {
                return DeviceType.LEXI_RFID;
            }
        }

        // Known serial bridge chips used by LEXI and other RFID readers
        if (vid == VID_SILABS && (pid == PID_CP2102 || pid == PID_CP2104 ||
                pid == PID_CP2108 || pid == PID_CP2110 || pid == PID_CP2112)) {
            return DeviceType.LEXI_RFID;
        }
        if (vid == VID_QINHENG && (pid == PID_CH340 || pid == PID_CH341 ||
                pid == PID_CH341A || pid == PID_CH9102)) {
            return DeviceType.LEXI_RFID;
        }
        if (vid == VID_FTDI && (pid == PID_FT232 || pid == PID_FT2232 ||
                pid == PID_FT4232 || pid == PID_FT232H || pid == PID_FT230X)) {
            return DeviceType.LEXI_RFID;
        }
        if (vid == VID_PROLIFIC && pid == PID_PL2303) {
            return DeviceType.LEXI_RFID;
        }

        // Atmel CDC (alternate Chameleon / generic serial)
        if (vid == VID_ATMEL && pid == PID_ATMEL_CDC) return DeviceType.GENERIC_SERIAL;

        // Any device advertising CDC-ACM interface class
        if (dev.getInterfaceCount() > 0 && dev.getInterface(0).getInterfaceClass() == 0x02) {
            return DeviceType.GENERIC_SERIAL;
        }

        return DeviceType.UNKNOWN;
    }

    public void requestPermissionAndConnect(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            openDevice(device);
        } else {
            PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(device, pi);
        }
    }

    private void openDevice(UsbDevice device) {
        connectedType = classify(device);
        connectedDevice = device;
        connection = usbManager.openDevice(device);
        if (connection == null) {
            mainHandler.post(() -> listener.onError("Could not open USB device"));
            return;
        }
        mainHandler.post(() -> listener.onDeviceConnected(device, connectedType));
    }

    private void closeDevice() {
        if (connection != null) { try { connection.close(); } catch (Exception ignored) {} }
        connection = null;
        connectedDevice = null;
    }

    public boolean isConnected() { return connection != null; }
    public DeviceType getConnectedType() { return connectedType; }
    public UsbDevice getConnectedDevice() { return connectedDevice; }
}
