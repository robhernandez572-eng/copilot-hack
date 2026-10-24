package com.lexi.rfid;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

/**
 * Proxmark3 Easy helper.
 *
 * Confirms the phone can see and open the PM3 over USB, and shows a quick
 * reference. It deliberately does NOT pretend to run hf/lf commands: the
 * Proxmark3 speaks a binary packet protocol (PacketCommandNG) that only the
 * desktop Iceman client encodes, so real work happens on a PC. This screen
 * proves the link works and tells you exactly how to drive it.
 */
public class ProxmarkActivity extends AppCompatActivity {

    private TextView tvStatus, tvInfo;
    private UsbDeviceManager usbManager;

    private static final String REFERENCE =
        "PROXMARK3 EASY — how to actually use it\n"
      + "────────────────────────────────────────\n\n"
      + "The Proxmark3 does NOT speak text over serial. Its USB link carries a\n"
      + "binary command protocol that only the desktop 'proxmark3' (Iceman) client\n"
      + "can encode. So this phone app can confirm the device and open its port,\n"
      + "but commands like 'hf search' run from the PC client.\n\n"
      + "CRASH-ON-PLUG FIX (Linux):\n"
      + "  ModemManager grabs /dev/ttyACM0 and resets the PM3 (looks like a crash).\n"
      + "  sudo tee /etc/udev/rules.d/77-mm-proxmark-ignore.rules <<'EOF'\n"
      + "  ATTRS{idVendor}==\"9ac4\", ATTRS{idProduct}==\"4b8f\", ENV{ID_MM_DEVICE_IGNORE}=\"1\"\n"
      + "  EOF\n"
      + "  sudo udevadm control --reload-rules && sudo udevadm trigger\n"
      + "  # if still flaky:  sudo systemctl mask ModemManager\n\n"
      + "PERMISSIONS (Linux):\n"
      + "  sudo usermod -aG dialout $USER     # Arch: uucp ; then log out/in\n\n"
      + "INSTALL CLIENT (Iceman fork):\n"
      + "  git clone https://github.com/RfidResearchGroup/proxmark3.git\n"
      + "  cd proxmark3 && sed -i 's/^PLATFORM=.*/PLATFORM=PM3GENERIC/' \\\n"
      + "     Makefile.platform  # PM3 Easy = 512KB = PM3GENERIC\n"
      + "  make -j$(nproc) && sudo make install\n\n"
      + "FLASH (matched fw+client), then connect:\n"
      + "  pm3-flash-all          # hold button while plugging to force bootloader\n"
      + "  pm3 -p /dev/ttyACM0\n\n"
      + "FIRST COMMANDS (your own cards only):\n"
      + "  hw status      device self-report\n"
      + "  hw tune        antenna check (LF ~20-40kV, HF ~10-20V)\n"
      + "  lf search      sweep 125 kHz (EM4100/HID/T5577...)\n"
      + "  hf search      sweep 13.56 MHz (MIFARE/NTAG/iCLASS...)\n"
      + "  hf mf autopwn  recover keys + dump a MIFARE Classic you own\n\n"
      + "Use only on cards/systems you own or are authorized to test.\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proxmark);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Proxmark3 Easy");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvStatus = findViewById(R.id.tvPmStatus);
        tvInfo = findViewById(R.id.tvPmInfo);
        Button btn = findViewById(R.id.btnPmConnect);
        tvInfo.setText(REFERENCE);

        usbManager = new UsbDeviceManager(this, new UsbDeviceManager.UsbListener() {
            @Override public void onDeviceConnected(UsbDevice device, UsbDeviceManager.DeviceType type) {
                tvStatus.setText("Linked: " + (type == UsbDeviceManager.DeviceType.PROXMARK3
                        ? "Proxmark3 Easy" : "USB serial device")
                    + "  VID=" + String.format("%04X", device.getVendorId())
                    + " PID=" + String.format("%04X", device.getProductId()));
                tvStatus.setTextColor(0xFFFFFFFF);
                probeEndpoints(device);
            }
            @Override public void onDeviceDisconnected(UsbDevice device) {
                tvStatus.setText("Proxmark3 — disconnected");
            }
            @Override public void onPermissionDenied(UsbDevice device) {
                tvStatus.setText("USB permission denied");
            }
            @Override public void onDataReceived(String data) {}
            @Override public void onError(String error) { tvStatus.setText("Error: " + error); }
        });
        usbManager.register();

        btn.setOnClickListener(v -> connect());
        connect();
    }

    private void connect() {
        List<UsbDevice> devs = usbManager.findSupportedDevices();
        UsbDevice pm3 = null;
        for (UsbDevice d : devs) {
            if (usbManager.classify(d) == UsbDeviceManager.DeviceType.PROXMARK3) { pm3 = d; break; }
        }
        if (pm3 == null) {
            // not auto-classified — try any serial device
            if (!devs.isEmpty()) pm3 = devs.get(0);
            else {
                List<UsbDevice> all = usbManager.findAllDevices();
                if (!all.isEmpty()) pm3 = all.get(0);
            }
        }
        if (pm3 == null) {
            tvStatus.setText("No Proxmark3 found — plug it in via USB-OTG, then tap Connect");
        } else {
            tvStatus.setText("Requesting access: VID " + String.format("%04X", pm3.getVendorId())
                + " PID " + String.format("%04X", pm3.getProductId()));
            usbManager.requestPermissionAndConnect(pm3);
        }
    }

    /** Confirm the CDC bulk endpoints exist — proves the phone can talk to the port. */
    private void probeEndpoints(UsbDevice device) {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection c = um.openDevice(device);
        if (c == null) { tvStatus.append("  (open failed)"); return; }
        int in = 0, out = 0;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) in++; else out++;
                }
            }
        }
        c.close();
        tvStatus.append("\nBulk endpoints: " + in + " in / " + out + " out — "
            + (in > 0 && out > 0 ? "link OK. Drive it from the PC client below."
                                 : "no bulk endpoints (bootloader mode?)."));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usbManager.unregister();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
