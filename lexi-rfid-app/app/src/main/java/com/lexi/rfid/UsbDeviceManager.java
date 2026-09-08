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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Manages USB connections for:
 *   - LEXI RFID/NFC Duplicator (CP210x, CH340, or similar USB-serial bridge)
 *   - Chameleon Mini / Chameleon Ultra (CDC ACM)
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

    // Vendor/Product IDs
    private static final int VID_SILICON_LABS = 0x10C4;  // CP210x (used by many RFID readers)
    private static final int PID_CP2102 = 0xEA60;
    private static final int VID_QINHENG = 0x1A86;        // CH340/CH341
    private static final int PID_CH340 = 0x7523;
    private static final int VID_FTDI = 0x0403;
    private static final int PID_FT232 = 0x6001;
    private static final int VID_CHAMELEON = 0x16D0;       // Chameleon Mini/Ultra
    private static final int PID_CHAMELEON_MINI = 0x04B2;
    private static final int PID_CHAMELEON_ULTRA = 0x06C2;
    private static final int VID_CDC_ACM = 0x03EB;         // Atmel CDC (also used by some Chameleons)

    private final Context context;
    private final UsbManager usbManager;
    private final UsbListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private UsbDevice connectedDevice;
    private UsbDeviceConnection connection;
    private DeviceType connectedType = DeviceType.UNKNOWN;

    // Simple byte buffer for incoming serial data
    private final StringBuilder rxBuffer = new StringBuilder();

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
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(usbReceiver, filter);
    }

    public void unregister() {
        try { context.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }

    /** Returns all recognized RFID/Chameleon USB devices currently connected. */
    public List<UsbDevice> findSupportedDevices() {
        List<UsbDevice> result = new ArrayList<>();
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice dev : devices.values()) {
            if (getDeviceType(dev) != DeviceType.UNKNOWN) result.add(dev);
        }
        return result;
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

    private DeviceType getDeviceType(UsbDevice dev) {
        int vid = dev.getVendorId();
        int pid = dev.getProductId();
        if (vid == VID_CHAMELEON) {
            if (pid == PID_CHAMELEON_MINI) return DeviceType.CHAMELEON_MINI;
            if (pid == PID_CHAMELEON_ULTRA) return DeviceType.CHAMELEON_ULTRA;
        }
        // Check product name for Chameleon (some revisions use different VID)
        String name = dev.getProductName();
        if (name != null && name.toLowerCase().contains("chameleon")) {
            return name.toLowerCase().contains("ultra") ?
                DeviceType.CHAMELEON_ULTRA : DeviceType.CHAMELEON_MINI;
        }
        if ((vid == VID_SILICON_LABS && pid == PID_CP2102) ||
            (vid == VID_QINHENG && pid == PID_CH340) ||
            (vid == VID_FTDI && pid == PID_FT232)) {
            // Likely the LEXI device or another serial RFID reader
            return DeviceType.LEXI_RFID;
        }
        if (dev.getInterfaceCount() > 0) {
            // Check for CDC ACM class (0x02) — generic serial, possibly Chameleon alternate VID
            if (dev.getInterface(0).getInterfaceClass() == 0x02) {
                return DeviceType.GENERIC_SERIAL;
            }
        }
        return DeviceType.UNKNOWN;
    }

    private void openDevice(UsbDevice device) {
        connectedType = getDeviceType(device);
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

    /** Send a text command (adds \r\n automatically). */
    public void sendCommand(String cmd) {
        if (!isConnected()) {
            mainHandler.post(() -> listener.onError("No device connected"));
            return;
        }
        // For actual byte-level transfers the UsbSerial library handles this;
        // this method is a stub that wraps the command for the Chameleon protocol.
        // Integration via UsbSerial happens in ChameleonFragment.
        mainHandler.post(() -> listener.onDataReceived("CMD> " + cmd));
    }
}
