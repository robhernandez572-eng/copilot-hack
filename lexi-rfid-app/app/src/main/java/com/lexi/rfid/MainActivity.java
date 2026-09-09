package com.lexi.rfid;

import android.Manifest;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // NFC
    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private boolean readMode = false;

    // Write mode (selected card from library to write)
    private CardData pendingWriteCard = null;
    private boolean writeMode = false;

    // Storage
    private CardStorage storage;
    private CardAdapter adapter;
    private List<CardData> cards;

    // Views
    private TextView tvStatus, tvScannerHint, tvSelectedCard;
    private TextView tvUsbStatus, tvUsbDetail;
    private RecyclerView recyclerView;
    private View emptyView;
    private View tabScanner, tabLibrary, tabUsb;
    private Button btnRead, btnWrite, btnScanUsb;
    private View layoutWritePicker;

    // USB
    private UsbDeviceManager usbDeviceManager;

    // BLE — Chameleon Ultra
    private ChameleonBleManager bleManager;
    private TextView tvBleStatus, tvBleDetail;
    private Button btnScanBle, btnDisconnectBle;
    private final List<BluetoothDevice> bleFoundDevices = new ArrayList<>();
    private final List<String> bleFoundNames = new ArrayList<>();

    private final ActivityResultLauncher<String[]> blePermLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
            boolean allGranted = true;
            for (Boolean v : grants.values()) if (!v) { allGranted = false; break; }
            if (allGranted) startBleScan();
            else Toast.makeText(this, "Bluetooth permission required for BLE scan", Toast.LENGTH_LONG).show();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new CardStorage(this);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        // Views
        tvStatus = findViewById(R.id.tvStatus);
        tvScannerHint = findViewById(R.id.tvScannerHint);
        tvSelectedCard = findViewById(R.id.tvSelectedCard);
        tvUsbStatus = findViewById(R.id.tvUsbStatus);
        tvUsbDetail = findViewById(R.id.tvUsbDetail);
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        tabScanner = findViewById(R.id.tabScanner);
        tabLibrary = findViewById(R.id.tabLibrary);
        tabUsb = findViewById(R.id.tabUsb);
        btnRead = findViewById(R.id.btnRead);
        btnWrite = findViewById(R.id.btnWrite);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        layoutWritePicker = findViewById(R.id.layoutWritePicker);
        tvBleStatus = findViewById(R.id.tvBleStatus);
        tvBleDetail = findViewById(R.id.tvBleDetail);
        btnScanBle = findViewById(R.id.btnScanBle);
        btnDisconnectBle = findViewById(R.id.btnDisconnectBle);

        // Library recycler
        cards = storage.loadCards();
        adapter = new CardAdapter(cards, new CardAdapter.OnCardClickListener() {
            @Override
            public void onCardClick(CardData card) {
                Intent intent = new Intent(MainActivity.this, CardDetailActivity.class);
                intent.putExtra("uid", card.getUid());
                intent.putExtra("name", card.getName());
                intent.putExtra("rawData", card.getRawData());
                intent.putExtra("type", card.getTypeLabel());
                intent.putExtra("technology", card.getTechnology());
                intent.putExtra("timestamp", card.getTimestamp());
                startActivity(intent);
            }
            @Override
            public void onCardLongClick(CardData card) {
                showDeleteDialog(card);
            }
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Bottom nav
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_scanner) {
                showTab(0);
            } else if (id == R.id.nav_library) {
                refreshLibrary();
                showTab(1);
            } else if (id == R.id.nav_usb) {
                showTab(2);
            }
            return true;
        });

        // NFC scanner buttons
        btnRead.setOnClickListener(v -> toggleReadMode());
        btnWrite.setOnClickListener(v -> {
            if (pendingWriteCard == null) {
                Snackbar.make(recyclerView,
                    "Open Card Library, tap a card, then use Write to NFC Tag",
                    Snackbar.LENGTH_LONG).show();
            } else {
                toggleWriteMode();
            }
        });

        // USB tab buttons
        btnScanUsb.setOnClickListener(v -> scanUsb());
        findViewById(R.id.btnOpenTerminal).setOnClickListener(v ->
            startActivity(new Intent(this, ChameleonActivity.class)));

        setupNfc();
        setupUsb();
        setupBle();
        updateEmptyView();
        showTab(0);
        handleIntent(getIntent());
        // Auto-scan for already-connected USB devices on startup
        scanUsb();
    }

    private void showTab(int index) {
        tabScanner.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        tabLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        tabUsb.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index != 0) stopReadMode();
    }

    // ── NFC READ ──────────────────────────────────────────────────────────

    private void setupNfc() {
        if (nfcAdapter == null) {
            tvStatus.setText("NFC not available on this device");
            btnRead.setEnabled(false);
            btnWrite.setEnabled(false);
            return;
        }
        pendingIntent = PendingIntent.getActivity(this, 0,
            new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE);
        intentFilters = new IntentFilter[]{
            new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        };
    }

    private void toggleReadMode() {
        if (readMode) {
            stopReadMode();
        } else {
            startReadMode();
        }
    }

    private void startReadMode() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Snackbar.make(tabScanner, "Please enable NFC in Settings", Snackbar.LENGTH_LONG).show();
            return;
        }
        writeMode = false;
        readMode = true;
        btnRead.setText("STOP Reading");
        btnRead.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0xFFF44336));
        tvStatus.setText("Hold any NFC card to back of phone...");
        tvStatus.setBackgroundColor(0xFF388E3C);
        tvScannerHint.setText("Waiting for NFC tag...");
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
    }

    private void stopReadMode() {
        readMode = false;
        btnRead.setText("READ Tag");
        btnRead.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0xFF1565C0));
        tvStatus.setText("Tap READ or WRITE below");
        tvStatus.setBackgroundColor(0xFF1565C0);
        tvScannerHint.setText("Tap READ, then hold any NFC card\nto the back of your phone");
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    private void toggleWriteMode() {
        if (writeMode) {
            stopWriteMode();
        } else {
            startWriteMode();
        }
    }

    private void startWriteMode() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Snackbar.make(tabScanner, "Please enable NFC in Settings", Snackbar.LENGTH_LONG).show();
            return;
        }
        readMode = false;
        writeMode = true;
        btnWrite.setText("STOP Writing");
        btnWrite.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0xFFF44336));
        tvStatus.setText("Hold a blank NFC tag to phone to write: "
            + (pendingWriteCard != null ? pendingWriteCard.getName() : ""));
        tvStatus.setBackgroundColor(0xFFE65100);
        tvScannerHint.setText("Waiting for blank tag...");
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
    }

    private void stopWriteMode() {
        writeMode = false;
        btnWrite.setText("WRITE Tag");
        btnWrite.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0xFF558B2F));
        tvStatus.setText("Tap READ or WRITE below");
        tvStatus.setBackgroundColor(0xFF1565C0);
        tvScannerHint.setText("Tap READ, then hold any NFC card\nto the back of your phone");
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    /** Called by CardAdapter long-press to queue a card for writing */
    public void queueCardForWrite(CardData card) {
        pendingWriteCard = card;
        btnWrite.setEnabled(true);
        tvSelectedCard.setText(card.getName() + " (" + card.getUid() + ")");
        layoutWritePicker.setVisibility(View.VISIBLE);
        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setSelectedItemId(R.id.nav_scanner);
        showTab(0);
        Snackbar.make(tabScanner, "Card queued — tap WRITE TAG to write it", Snackbar.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && (readMode || writeMode)) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
        }
        refreshLibrary();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            android.hardware.usb.UsbDevice dev =
                intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE);
            if (dev != null) {
                usbDeviceManager.requestPermissionAndConnect(dev);
                BottomNavigationView nav = findViewById(R.id.bottomNav);
                nav.setSelectedItemId(R.id.nav_usb);
                showTab(2);
            }
            return;
        }
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag == null) return;
            if (writeMode && pendingWriteCard != null) {
                performWrite(tag);
            } else if (readMode) {
                processRead(tag);
            }
        }
    }

    private void processRead(Tag tag) {
        try {
            vibrate();
            CardData card = NfcHelper.readTag(tag);
            storage.saveCard(card);
            refreshLibrary();
            tvStatus.setText("Read: " + card.getName() + " (" + card.getTypeLabel() + ")");
            tvStatus.setBackgroundColor(0xFF388E3C);
            tvScannerHint.setText("Card saved! Scan another or tap STOP.");
            // Enable write button now that we have a card
            pendingWriteCard = card;
            btnWrite.setEnabled(true);
            tvSelectedCard.setText(card.getName() + " (" + card.getUid() + ")");
            layoutWritePicker.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Saved: " + card.getUid(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            tvStatus.setText("Read error: " + e.getMessage());
            tvStatus.setBackgroundColor(0xFFF44336);
        }
    }

    private void performWrite(Tag tag) {
        tvStatus.setText("Writing...");
        new Thread(() -> {
            String result = NfcHelper.writeTag(tag, pendingWriteCard);
            runOnUiThread(() -> {
                vibrate();
                stopWriteMode();
                new AlertDialog.Builder(this)
                    .setTitle("Write Complete")
                    .setMessage(result)
                    .setPositiveButton("OK", null)
                    .show();
            });
        }).start();
    }

    // ── USB ───────────────────────────────────────────────────────────────

    private void setupUsb() {
        usbDeviceManager = new UsbDeviceManager(this, new UsbDeviceManager.UsbListener() {
            @Override public void onDeviceConnected(UsbDevice device, UsbDeviceManager.DeviceType type) {
                String label = deviceLabel(type, device);
                tvUsbStatus.setText(label + " — Connected");
                tvUsbStatus.setTextColor(0xFF00C853);
                tvUsbDetail.setText("VID " + String.format("%04X", device.getVendorId())
                    + "  PID " + String.format("%04X", device.getProductId())
                    + (device.getProductName() != null ? "\n" + device.getProductName() : ""));
            }
            @Override public void onDeviceDisconnected(UsbDevice device) {
                tvUsbStatus.setText("Device disconnected");
                tvUsbStatus.setTextColor(0xFFFFFFFF);
                tvUsbDetail.setText("Connect via USB-C → USB-A adapter");
            }
            @Override public void onPermissionDenied(UsbDevice device) {
                tvUsbStatus.setText("USB permission denied");
                tvUsbStatus.setTextColor(0xFFF44336);
            }
            @Override public void onDataReceived(String data) {}
            @Override public void onError(String error) {
                tvUsbDetail.setText(error);
            }
        });
        usbDeviceManager.register();
    }

    private void scanUsb() {
        tvUsbStatus.setText("Scanning...");
        tvUsbStatus.setTextColor(0xFFFFFFFF);
        List<UsbDevice> devices = usbDeviceManager.findSupportedDevices();
        if (devices.isEmpty()) {
            tvUsbStatus.setText("No device found");
            tvUsbDetail.setText("Make sure your device is plugged in via USB-C adapter");
        } else {
            UsbDevice dev = devices.get(0);
            UsbDeviceManager.DeviceType type = usbDeviceManager.classify(dev);
            tvUsbStatus.setText("Found: " + deviceLabel(type, dev));
            tvUsbDetail.setText("VID " + String.format("%04X", dev.getVendorId())
                + "  PID " + String.format("%04X", dev.getProductId()));
            usbDeviceManager.requestPermissionAndConnect(dev);
        }
    }

    private String deviceLabel(UsbDeviceManager.DeviceType type, UsbDevice dev) {
        switch (type) {
            case CHAMELEON_ULTRA: return "Chameleon Ultra";
            case CHAMELEON_MINI: return "Chameleon Mini";
            case LEXI_RFID: return "LEXI RFID Device";
            case GENERIC_SERIAL: return "USB Serial Device";
            default:
                String name = dev.getProductName();
                return name != null ? name : "USB Device";
        }
    }

    // ── BLE ───────────────────────────────────────────────────────────────

    private void setupBle() {
        bleManager = new ChameleonBleManager(this);
        btnScanBle.setOnClickListener(v -> requestBlePermissionsAndScan());
        btnDisconnectBle.setOnClickListener(v -> {
            bleManager.disconnect();
            tvBleStatus.setText("Disconnected");
            tvBleStatus.setTextColor(0xFFFFFFFF);
            tvBleDetail.setText("Tap Scan BLE to reconnect");
            btnDisconnectBle.setEnabled(false);
            btnScanBle.setEnabled(true);
        });
    }

    private void requestBlePermissionsAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasScan = ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean hasConnect = ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (hasScan && hasConnect) {
                startBleScan();
            } else {
                blePermLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                });
            }
        } else {
            boolean hasLoc = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (hasLoc) {
                startBleScan();
            } else {
                blePermLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            }
        }
    }

    private void startBleScan() {
        if (!bleManager.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth is off — please enable it", Toast.LENGTH_LONG).show();
            return;
        }
        bleFoundDevices.clear();
        bleFoundNames.clear();
        tvBleStatus.setText("Scanning for Chameleon Ultra...");
        tvBleStatus.setTextColor(0xFFFFFFFF);
        tvBleDetail.setText("BLE scan in progress (12 s)");
        btnScanBle.setEnabled(false);

        bleManager.startScan(new ChameleonBleManager.BleListener() {
            @Override public void onDeviceFound(BluetoothDevice device, String name, int rssi) {
                if (!bleFoundDevices.contains(device)) {
                    bleFoundDevices.add(device);
                    bleFoundNames.add(name + "  [" + rssi + " dBm]");
                }
                tvBleDetail.setText("Found " + bleFoundDevices.size() + " device(s)…");
                if (bleFoundDevices.size() == 1) showBlePickerDialog();
            }
            @Override public void onConnected(BluetoothDevice device) {
                bleManager.stopScan();
                tvBleStatus.setText("Connected: " + (device.getName() != null ? device.getName() : device.getAddress()));
                tvBleStatus.setTextColor(0xFF00C853);
                tvBleDetail.setText("Chameleon Ultra ready via BLE");
                btnScanBle.setEnabled(true);
                btnDisconnectBle.setEnabled(true);
            }
            @Override public void onDisconnected() {
                tvBleStatus.setText("BLE Disconnected");
                tvBleStatus.setTextColor(0xFFFFFFFF);
                tvBleDetail.setText("Tap Scan BLE to reconnect");
                btnDisconnectBle.setEnabled(false);
                btnScanBle.setEnabled(true);
            }
            @Override public void onDataReceived(String data) {}
            @Override public void onError(String message) {
                tvBleStatus.setText("BLE Error");
                tvBleDetail.setText(message);
                tvBleStatus.setTextColor(0xFFF44336);
                btnScanBle.setEnabled(true);
            }
        });

        // Auto-show picker after scan timeout
        tabUsb.postDelayed(() -> {
            bleManager.stopScan();
            btnScanBle.setEnabled(true);
            if (bleFoundDevices.isEmpty()) {
                tvBleStatus.setText("No Chameleon Ultra found");
                tvBleDetail.setText("Ensure device is powered on and in BLE range");
            } else {
                showBlePickerDialog();
            }
        }, 12_500);
    }

    private void showBlePickerDialog() {
        if (bleFoundDevices.isEmpty()) return;
        String[] items = bleFoundNames.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Select Chameleon Ultra")
            .setItems(items, (d, which) -> {
                BluetoothDevice chosen = bleFoundDevices.get(which);
                bleManager.stopScan();
                tvBleStatus.setText("Connecting…");
                tvBleDetail.setText(chosen.getAddress());
                btnScanBle.setEnabled(false);
                bleManager.connect(chosen, new ChameleonBleManager.BleListener() {
                    @Override public void onDeviceFound(BluetoothDevice device, String name, int rssi) {}
                    @Override public void onConnected(BluetoothDevice device) {
                        tvBleStatus.setText("Connected: " + (device.getName() != null ? device.getName() : device.getAddress()));
                        tvBleStatus.setTextColor(0xFF00C853);
                        tvBleDetail.setText("Chameleon Ultra ready via BLE");
                        btnScanBle.setEnabled(true);
                        btnDisconnectBle.setEnabled(true);
                    }
                    @Override public void onDisconnected() {
                        tvBleStatus.setText("BLE Disconnected");
                        tvBleStatus.setTextColor(0xFFFFFFFF);
                        tvBleDetail.setText("Tap Scan BLE to reconnect");
                        btnDisconnectBle.setEnabled(false);
                        btnScanBle.setEnabled(true);
                    }
                    @Override public void onDataReceived(String data) {}
                    @Override public void onError(String message) {
                        tvBleStatus.setText("BLE Error");
                        tvBleDetail.setText(message);
                        tvBleStatus.setTextColor(0xFFF44336);
                        btnScanBle.setEnabled(true);
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────

    private void refreshLibrary() {
        cards = storage.loadCards();
        adapter.updateCards(cards);
        updateEmptyView();
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(150);
        } catch (Exception ignored) {}
    }

    private void showDeleteDialog(CardData card) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Card")
            .setMessage("Delete \"" + card.getName() + "\"?")
            .setPositiveButton("Delete", (d, w) -> {
                storage.deleteCard(card.getUid());
                refreshLibrary();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateEmptyView() {
        if (cards.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_usb_terminal) {
            startActivity(new Intent(this, ChameleonActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_clear_all) {
            new AlertDialog.Builder(this)
                .setTitle("Clear All Cards")
                .setMessage("Delete all saved cards?")
                .setPositiveButton("Clear", (d, w) -> {
                    for (CardData c : cards) storage.deleteCard(c.getUid());
                    refreshLibrary();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
        }
        if (item.getItemId() == R.id.action_about) {
            new AlertDialog.Builder(this)
                .setTitle("LEXI RFID NFC Reader v1.1")
                .setMessage("READ — Scan any NFC card (MIFARE Classic, Ultralight, NDEF, ISO-DEP, NFC-A/B/F/V)\n\nWRITE — Clone a scanned card to a blank NFC tag\n\nUSB Device — LEXI RFID 125 KHz + Chameleon Mini/Ultra over USB-C adapter\n\nLong-press a card in Library to delete it.")
                .setPositiveButton("OK", null)
                .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usbDeviceManager.unregister();
        if (bleManager != null) {
            bleManager.stopScan();
            bleManager.disconnect();
        }
    }
}
