package com.lexi.rfid;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chameleon Mini / Chameleon Ultra terminal activity.
 * Also supports LEXI 125 KHz serial commands.
 *
 * Protocol: CDC serial at 115200 baud, plain ASCII commands terminated with \r\n
 * Common Chameleon commands:
 *   VERSION        - firmware version
 *   CONFIG?        - list available modes
 *   CONFIG=<mode>  - set emulation mode (e.g. MF_CLASSIC_1K)
 *   UID?           - read current UID
 *   UID=<hex>      - set UID (e.g. AABBCCDD)
 *   BUTTON=NONE|UID_RANDOM|UID_LEFT_INCREMENT|CYCLE_SETTINGS
 *   UPLOAD         - receive card dump (XModem)
 *   DOWNLOAD       - send card dump (XModem)
 *   RESET          - reboot device
 *   HELP           - list all commands
 */
public class ChameleonActivity extends AppCompatActivity {

    private TextView tvTerminal;
    private EditText etCommand;
    private Button btnSend, btnConnect, btnHelp, btnVersion, btnUidQuery, btnConfigQuery;
    private TextView tvDeviceInfo;

    private UsbDeviceManager usbManager;
    private UsbDevice currentDevice;
    private UsbDeviceConnection rawConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint epOut, epIn;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean reading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chameleon);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Chameleon / LEXI USB Terminal");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvTerminal = findViewById(R.id.tvTerminal);
        etCommand = findViewById(R.id.etCommand);
        btnSend = findViewById(R.id.btnSend);
        btnConnect = findViewById(R.id.btnConnect);
        btnHelp = findViewById(R.id.btnCmdHelp);
        btnVersion = findViewById(R.id.btnCmdVersion);
        btnUidQuery = findViewById(R.id.btnCmdUid);
        btnConfigQuery = findViewById(R.id.btnCmdConfig);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);

        tvTerminal.setMovementMethod(new ScrollingMovementMethod());

        usbManager = new UsbDeviceManager(this, new UsbDeviceManager.UsbListener() {
            @Override
            public void onDeviceConnected(UsbDevice device, UsbDeviceManager.DeviceType type) {
                currentDevice = device;
                String label = type == UsbDeviceManager.DeviceType.CHAMELEON_ULTRA ? "Chameleon Ultra"
                    : type == UsbDeviceManager.DeviceType.CHAMELEON_MINI ? "Chameleon Mini"
                    : type == UsbDeviceManager.DeviceType.LEXI_RFID ? "LEXI RFID Device"
                    : "USB Serial Device";
                tvDeviceInfo.setText(label + " connected");
                appendLog("Connected: " + label + "\nVID=" + String.format("%04X", device.getVendorId())
                    + " PID=" + String.format("%04X", device.getProductId()));
                setControlsEnabled(true);
                openRawUsb(device);
            }
            @Override
            public void onDeviceDisconnected(UsbDevice device) {
                currentDevice = null;
                reading = false;
                tvDeviceInfo.setText("Device disconnected");
                appendLog("Device disconnected.");
                setControlsEnabled(false);
            }
            @Override
            public void onPermissionDenied(UsbDevice device) {
                Toast.makeText(ChameleonActivity.this, "USB permission denied", Toast.LENGTH_SHORT).show();
                appendLog("Permission denied for device.");
            }
            @Override
            public void onDataReceived(String data) { appendLog(data); }
            @Override
            public void onError(String error) { appendLog("Error: " + error); }
        });
        usbManager.register();

        btnConnect.setOnClickListener(v -> scanAndConnect());
        btnSend.setOnClickListener(v -> sendUserCommand());
        btnHelp.setOnClickListener(v -> sendSerial("HELP\r\n"));
        btnVersion.setOnClickListener(v -> sendSerial("VERSION\r\n"));
        btnUidQuery.setOnClickListener(v -> sendSerial("UID?\r\n"));
        btnConfigQuery.setOnClickListener(v -> sendSerial("CONFIG?\r\n"));

        setControlsEnabled(false);
        scanAndConnect();
    }

    private void scanAndConnect() {
        List<UsbDevice> devices = usbManager.findSupportedDevices();
        if (devices.isEmpty()) {
            appendLog("No supported USB device found.\nConnect your Chameleon or LEXI device via USB-OTG.");
            tvDeviceInfo.setText("No device — connect via USB-OTG");
        } else {
            UsbDevice dev = devices.get(0);
            appendLog("Found: " + dev.getProductName() + " VID=" +
                String.format("%04X", dev.getVendorId()) + " PID=" + String.format("%04X", dev.getProductId()));
            usbManager.requestPermissionAndConnect(dev);
        }
    }

    private void openRawUsb(UsbDevice device) {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        rawConnection = um.openDevice(device);
        if (rawConnection == null) { appendLog("Failed to open USB connection"); return; }

        // Find the first bulk-transfer interface
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            rawConnection.claimInterface(iface, true);
            UsbEndpoint out = null, in = null;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_OUT) out = ep;
                    else in = ep;
                }
            }
            if (out != null && in != null) {
                usbInterface = iface;
                epOut = out;
                epIn = in;
                break;
            }
        }
        if (epOut == null) { appendLog("No bulk endpoints found — device may need CDC-ACM driver"); return; }
        startReading();
    }

    private void startReading() {
        reading = true;
        executor.submit(() -> {
            byte[] buf = new byte[64];
            while (reading && rawConnection != null) {
                int n = rawConnection.bulkTransfer(epIn, buf, buf.length, 200);
                if (n > 0) {
                    String chunk = new String(buf, 0, n);
                    mainHandler.post(() -> appendLog(chunk));
                }
            }
        });
    }

    private void sendUserCommand() {
        String cmd = etCommand.getText().toString().trim();
        if (cmd.isEmpty()) return;
        appendLog("> " + cmd);
        sendSerial(cmd + "\r\n");
        etCommand.setText("");
    }

    private void sendSerial(String cmd) {
        if (rawConnection == null || epOut == null) {
            appendLog("Not connected");
            return;
        }
        executor.submit(() -> {
            byte[] data = cmd.getBytes();
            rawConnection.bulkTransfer(epOut, data, data.length, 1000);
        });
    }

    private void appendLog(String text) {
        mainHandler.post(() -> {
            tvTerminal.append(text.endsWith("\n") ? text : text + "\n");
            // Auto-scroll
            int scroll = tvTerminal.getLayout() == null ? 0 :
                tvTerminal.getLayout().getLineTop(tvTerminal.getLineCount()) - tvTerminal.getHeight();
            if (scroll > 0) tvTerminal.scrollTo(0, scroll);
        });
    }

    private void setControlsEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        btnHelp.setEnabled(enabled);
        btnVersion.setEnabled(enabled);
        btnUidQuery.setEnabled(enabled);
        btnConfigQuery.setEnabled(enabled);
        etCommand.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        reading = false;
        usbManager.unregister();
        if (rawConnection != null) rawConnection.close();
        executor.shutdownNow();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
