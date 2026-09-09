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
 * Full USB serial terminal for Chameleon Mini, Chameleon Ultra, and LEXI RFID.
 *
 * Chameleon ASCII protocol (115200 baud, \r\n terminated):
 *   VERSION, HELP, CONFIG?, CONFIG=<mode>, UID?, UID=<hex>,
 *   UPLOAD, DOWNLOAD, RESET, BUTTON=<action>
 */
public class ChameleonActivity extends AppCompatActivity {

    private TextView tvTerminal, tvDeviceInfo;
    private EditText etCommand, etUidInput;
    private Button btnSend, btnConnect;

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

        tvTerminal   = findViewById(R.id.tvTerminal);
        etCommand    = findViewById(R.id.etCommand);
        etUidInput   = findViewById(R.id.etUidInput);
        btnSend      = findViewById(R.id.btnSend);
        btnConnect   = findViewById(R.id.btnConnect);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);

        tvTerminal.setMovementMethod(new ScrollingMovementMethod());

        usbManager = new UsbDeviceManager(this, new UsbDeviceManager.UsbListener() {
            @Override
            public void onDeviceConnected(UsbDevice device, UsbDeviceManager.DeviceType type) {
                currentDevice = device;
                String label = deviceLabel(type, device);
                tvDeviceInfo.setText(label + " — connected");
                appendLog("✔ " + label + " connected"
                    + "  VID=" + String.format("%04X", device.getVendorId())
                    + "  PID=" + String.format("%04X", device.getProductId()));
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
                appendLog("Permission denied.");
            }
            @Override public void onDataReceived(String data) { appendLog(data); }
            @Override public void onError(String error) { appendLog("Error: " + error); }
        });
        usbManager.register();

        // Quick commands
        btnConnect.setOnClickListener(v -> scanAndConnect());
        btnSend.setOnClickListener(v -> sendUserCommand());
        findViewById(R.id.btnCmdHelp).setOnClickListener(v -> sendSerial("HELP\r\n"));
        findViewById(R.id.btnCmdVersion).setOnClickListener(v -> sendSerial("VERSION\r\n"));
        findViewById(R.id.btnCmdUid).setOnClickListener(v -> sendSerial("UID?\r\n"));
        findViewById(R.id.btnCmdConfig).setOnClickListener(v -> sendSerial("CONFIG?\r\n"));
        findViewById(R.id.btnCmdReset).setOnClickListener(v -> sendSerial("RESET\r\n"));

        // Mode buttons
        findViewById(R.id.btnMode1k).setOnClickListener(v -> setMode("MF_CLASSIC_1K"));
        findViewById(R.id.btnMode4k).setOnClickListener(v -> setMode("MF_CLASSIC_4K"));
        findViewById(R.id.btnModeUl).setOnClickListener(v -> setMode("MF_ULTRALIGHT"));
        findViewById(R.id.btnModeSniff).setOnClickListener(v -> setMode("ISO14443A_SNIFF"));
        findViewById(R.id.btnModeOff).setOnClickListener(v -> setMode("NONE"));

        // UID setter
        findViewById(R.id.btnSetUid).setOnClickListener(v -> {
            String uid = etUidInput.getText().toString().trim().toUpperCase()
                .replaceAll("[^0-9A-F]", "");
            if (uid.isEmpty()) { Toast.makeText(this, "Enter a UID hex value", Toast.LENGTH_SHORT).show(); return; }
            appendLog("> UID=" + uid);
            sendSerial("UID=" + uid + "\r\n");
            etUidInput.setText("");
        });

        // IME send on keyboard action
        etCommand.setOnEditorActionListener((v, action, event) -> {
            sendUserCommand();
            return true;
        });

        setControlsEnabled(false);
        scanAndConnect();
    }

    private void setMode(String mode) {
        appendLog("> CONFIG=" + mode);
        sendSerial("CONFIG=" + mode + "\r\n");
    }

    private void scanAndConnect() {
        List<UsbDevice> devices = usbManager.findSupportedDevices();
        if (devices.isEmpty()) {
            appendLog("No supported USB device found.\nPlug in your device via USB-C adapter, then tap [Scan USB].");
            tvDeviceInfo.setText("No device — connect via USB-C adapter");
        } else {
            UsbDevice dev = devices.get(0);
            appendLog("Found: " + (dev.getProductName() != null ? dev.getProductName() : "USB Device")
                + "  VID=" + String.format("%04X", dev.getVendorId())
                + "  PID=" + String.format("%04X", dev.getProductId()));
            usbManager.requestPermissionAndConnect(dev);
        }
    }

    private void openRawUsb(UsbDevice device) {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        rawConnection = um.openDevice(device);
        if (rawConnection == null) { appendLog("Failed to open USB connection"); return; }

        // Find bulk-transfer interface (handles both CDC-ACM and raw serial)
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
        if (epOut == null) {
            appendLog("No bulk endpoints — device may need a different driver");
            return;
        }
        startReading();
        // Auto-query firmware version on connect
        mainHandler.postDelayed(() -> sendSerial("VERSION\r\n"), 500);
    }

    private void startReading() {
        reading = true;
        executor.submit(() -> {
            byte[] buf = new byte[256];
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
            appendLog("Not connected — tap [Scan USB] first");
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
            int scroll = tvTerminal.getLayout() == null ? 0 :
                tvTerminal.getLayout().getLineTop(tvTerminal.getLineCount()) - tvTerminal.getHeight();
            if (scroll > 0) tvTerminal.scrollTo(0, scroll);
        });
    }

    private void setControlsEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        etCommand.setEnabled(enabled);
        etUidInput.setEnabled(enabled);
        int[] modeIds = {R.id.btnMode1k, R.id.btnMode4k, R.id.btnModeUl,
                         R.id.btnModeSniff, R.id.btnModeOff, R.id.btnSetUid,
                         R.id.btnCmdHelp, R.id.btnCmdVersion, R.id.btnCmdUid,
                         R.id.btnCmdConfig, R.id.btnCmdReset};
        for (int id : modeIds) {
            View v = findViewById(id);
            if (v != null) v.setEnabled(enabled);
        }
    }

    private String deviceLabel(UsbDeviceManager.DeviceType type, UsbDevice device) {
        switch (type) {
            case CHAMELEON_ULTRA: return "Chameleon Ultra";
            case CHAMELEON_MINI:  return "Chameleon Mini";
            case LEXI_RFID:       return "LEXI RFID Device";
            case GENERIC_SERIAL:  return "USB Serial Device";
            default:
                String name = device.getProductName();
                return name != null ? name : "USB Device";
        }
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
