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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MagSpoof V5 (Electronic Cats) controller.
 *
 * Connects over USB CDC serial (bulk transfer).
 * Protocol (newline-terminated commands):
 *   %B...?       — set track 1 in RAM
 *   ;...?        — set track 2 in RAM
 *   p1           — play track 1
 *   p2           — play track 2
 *   p            — play alternating
 *   s            — save RAM → EEPROM
 *   d            — display RAM tracks
 *   e            — display EEPROM tracks
 *   h            — help
 */
public class MagSpoofActivity extends AppCompatActivity {

    private TextView tvDeviceInfo, tvLog;
    private EditText etTrack1, etTrack2;
    private Button btnPlayT1, btnPlayT2, btnPlayBoth,
                   btnSetTrack1, btnSetTrack2,
                   btnSaveEeprom, btnShowRam, btnShowEeprom,
                   btnHelp, btnConnect;

    private UsbDeviceManager usbManager;
    private UsbDeviceConnection rawConn;
    private UsbEndpoint epOut, epIn;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean reading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_magspoof);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("MagSpoof V5");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvDeviceInfo  = findViewById(R.id.tvMsDeviceInfo);
        tvLog         = findViewById(R.id.tvMsLog);
        etTrack1      = findViewById(R.id.etTrack1);
        etTrack2      = findViewById(R.id.etTrack2);
        btnPlayT1     = findViewById(R.id.btnPlayT1);
        btnPlayT2     = findViewById(R.id.btnPlayT2);
        btnPlayBoth   = findViewById(R.id.btnPlayBoth);
        btnSetTrack1  = findViewById(R.id.btnSetTrack1);
        btnSetTrack2  = findViewById(R.id.btnSetTrack2);
        btnSaveEeprom = findViewById(R.id.btnMsSaveEeprom);
        btnShowRam    = findViewById(R.id.btnMsShowRam);
        btnShowEeprom = findViewById(R.id.btnMsShowEeprom);
        btnHelp       = findViewById(R.id.btnMsHelp);
        btnConnect    = findViewById(R.id.btnMsConnect);

        tvLog.setMovementMethod(new ScrollingMovementMethod());

        usbManager = new UsbDeviceManager(this, new UsbDeviceManager.UsbListener() {
            @Override public void onDeviceConnected(UsbDevice device, UsbDeviceManager.DeviceType type) {
                tvDeviceInfo.setText("MagSpoof  VID="
                    + String.format("%04X", device.getVendorId())
                    + "  PID=" + String.format("%04X", device.getProductId())
                    + (device.getProductName() != null ? "  " + device.getProductName() : ""));
                tvDeviceInfo.setTextColor(0xFF00C853);
                appendLog("Connected — ready");
                setControlsEnabled(true);
                openRawUsb(device);
            }
            @Override public void onDeviceDisconnected(UsbDevice device) {
                tvDeviceInfo.setText("Device disconnected");
                tvDeviceInfo.setTextColor(0xFF29B6F6);
                appendLog("Disconnected.");
                reading = false;
                setControlsEnabled(false);
            }
            @Override public void onPermissionDenied(UsbDevice device) {
                Toast.makeText(MagSpoofActivity.this, "USB permission denied", Toast.LENGTH_SHORT).show();
            }
            @Override public void onDataReceived(String data) { appendLog(data); }
            @Override public void onError(String error) { appendLog("Error: " + error); }
        });
        usbManager.register();

        btnConnect.setOnClickListener(v -> scanAndConnect());
        btnSetTrack1.setOnClickListener(v -> sendTrack1());
        btnSetTrack2.setOnClickListener(v -> sendTrack2());
        btnPlayT1.setOnClickListener(v -> send("p1\n"));
        btnPlayT2.setOnClickListener(v -> send("p2\n"));
        btnPlayBoth.setOnClickListener(v -> send("p\n"));
        btnSaveEeprom.setOnClickListener(v -> send("s\n"));
        btnShowRam.setOnClickListener(v -> send("d\n"));
        btnShowEeprom.setOnClickListener(v -> send("e\n"));
        btnHelp.setOnClickListener(v -> send("h\n"));

        setControlsEnabled(false);
        appendLog("Tap 'Scan & Connect USB' to find MagSpoof V5.");
        appendLog("For Track 1 use format: %B<number>^<name>^<YYMM>?");
        appendLog("For Track 2 use format: ;<number>=<YYMM>?");
        scanAndConnect();
    }

    private void sendTrack1() {
        String t = etTrack1.getText().toString().trim();
        if (t.isEmpty()) { Toast.makeText(this, "Enter track 1 data", Toast.LENGTH_SHORT).show(); return; }
        if (!t.startsWith("%")) { t = "%" + t; }
        if (!t.endsWith("?")) { t = t + "?"; }
        appendLog("> " + t);
        send(t + "\n");
    }

    private void sendTrack2() {
        String t = etTrack2.getText().toString().trim();
        if (t.isEmpty()) { Toast.makeText(this, "Enter track 2 data", Toast.LENGTH_SHORT).show(); return; }
        if (!t.startsWith(";")) { t = ";" + t; }
        if (!t.endsWith("?")) { t = t + "?"; }
        appendLog("> " + t);
        send(t + "\n");
    }

    private void scanAndConnect() {
        List<UsbDevice> devices = usbManager.findSupportedDevices();
        if (devices.isEmpty()) {
            appendLog("No USB device found. Plug in MagSpoof V5 via USB-C adapter.");
            tvDeviceInfo.setText("No device — connect via USB-C adapter");
        } else {
            UsbDevice dev = devices.get(0);
            appendLog("Found: "
                + (dev.getProductName() != null ? dev.getProductName() : "USB Device")
                + "  VID=" + String.format("%04X", dev.getVendorId())
                + "  PID=" + String.format("%04X", dev.getProductId()));
            usbManager.requestPermissionAndConnect(dev);
        }
    }

    private void openRawUsb(UsbDevice device) {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        rawConn = um.openDevice(device);
        if (rawConn == null) { appendLog("Failed to open USB connection"); return; }

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            rawConn.claimInterface(iface, true);
            UsbEndpoint out = null, in = null;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_OUT) out = ep;
                    else in = ep;
                }
            }
            if (out != null && in != null) {
                epOut = out;
                epIn = in;
                break;
            }
        }
        if (epOut == null) {
            appendLog("No bulk endpoints found — check USB connection");
            return;
        }
        startReading();
        // Request firmware greeting
        mainHandler.postDelayed(() -> send("h\n"), 600);
    }

    private void startReading() {
        reading = true;
        executor.submit(() -> {
            byte[] buf = new byte[256];
            while (reading && rawConn != null) {
                int n = rawConn.bulkTransfer(epIn, buf, buf.length, 200);
                if (n > 0) {
                    String chunk = new String(buf, 0, n);
                    mainHandler.post(() -> appendLog(chunk));
                }
            }
        });
    }

    private void send(String cmd) {
        if (rawConn == null || epOut == null) {
            appendLog("Not connected — tap Scan & Connect USB");
            return;
        }
        executor.submit(() -> {
            byte[] data = cmd.getBytes();
            rawConn.bulkTransfer(epOut, data, data.length, 1000);
        });
    }

    private void appendLog(String text) {
        mainHandler.post(() -> {
            tvLog.append(text.endsWith("\n") ? text : text + "\n");
            int scroll = tvLog.getLayout() == null ? 0 :
                tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scroll > 0) tvLog.scrollTo(0, scroll);
        });
    }

    private void setControlsEnabled(boolean on) {
        btnPlayT1.setEnabled(on);
        btnPlayT2.setEnabled(on);
        btnPlayBoth.setEnabled(on);
        btnSetTrack1.setEnabled(on);
        btnSetTrack2.setEnabled(on);
        btnSaveEeprom.setEnabled(on);
        btnShowRam.setEnabled(on);
        btnShowEeprom.setEnabled(on);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        reading = false;
        usbManager.unregister();
        if (rawConn != null) rawConn.close();
        executor.shutdownNow();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
