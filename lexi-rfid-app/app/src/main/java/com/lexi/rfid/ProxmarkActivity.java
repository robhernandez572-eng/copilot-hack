package com.lexi.rfid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Proxmark3 Easy console, backed by the REAL Iceman client running in Termux.
 *
 * The PM3 speaks a binary protocol, so this app can't drive it directly. Instead
 * it uses Termux's documented RUN_COMMAND bridge: we ask Termux to run
 *   pm3 -p /dev/ttyACM0 -c "<command>"
 * and Termux returns stdout/stderr, which we display. That means you get the
 * real client's real readouts.
 *
 * Prerequisites (one-time, see Setup Help):
 *   1. Install Termux (F-Droid build), build the proxmark3 client in it.
 *   2. In Termux: echo "allow-external-apps=true" >> ~/.termux/termux.properties
 *   3. Grant this app the com.termux.permission.RUN_COMMAND permission.
 *   4. Termux's pm3 must be able to reach the USB port (root + usb_acm, or the
 *      termux-usb fd path — edit the invocation line accordingly).
 */
public class ProxmarkActivity extends AppCompatActivity {

    private static final String RESULT_ACTION = "com.lexi.rfid.PM3_RESULT";
    private static final String TERMUX_PKG = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";

    private TextView tvStatus, tvOut;
    private EditText etInvoke, etCmd;
    private ScrollView svOut;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int reqCode = 100;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            Bundle result = intent.getBundleExtra("result");
            if (result == null) { append("\n[no result bundle returned from Termux]\n"); return; }
            String stdout = result.getString("stdout", "");
            String stderr = result.getString("stderr", "");
            int exit = result.getInt("exitCode", -1);
            String errmsg = result.getString("errmsg", "");
            if (!stdout.isEmpty()) append(stdout.endsWith("\n") ? stdout : stdout + "\n");
            if (!stderr.isEmpty()) append("[stderr] " + stderr + "\n");
            if (!errmsg.isEmpty()) append("[termux] " + errmsg + "\n");
            append("[exit " + exit + "]\n");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proxmark);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Proxmark3 (Termux)");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvStatus = findViewById(R.id.tvPmStatus);
        tvOut = findViewById(R.id.tvPmOut);
        etInvoke = findViewById(R.id.etPmInvoke);
        etCmd = findViewById(R.id.etPmCmd);
        svOut = findViewById(R.id.svPmOut);
        tvOut.setMovementMethod(new ScrollingMovementMethod());

        ContextCompat.registerReceiver(this, resultReceiver,
            new IntentFilter(RESULT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

        findViewById(R.id.btnPmRun).setOnClickListener(v -> runCmd(etCmd.getText().toString().trim()));
        etCmd.setOnEditorActionListener((v, a, e) -> { runCmd(etCmd.getText().toString().trim()); return true; });
        findViewById(R.id.btnPmCommands).setOnClickListener(v -> showCommandList());
        findViewById(R.id.btnPmSetup).setOnClickListener(v -> showSetup());
        findViewById(R.id.btnPmClear).setOnClickListener(v -> tvOut.setText(""));

        boolean termux = isTermuxInstalled();
        tvStatus.setText(termux
            ? "Termux detected. Enter a command or tap Command List. Output appears below."
            : "Termux NOT installed. Tap 'Setup Help' — the console needs Termux + the pm3 client.");
        tvStatus.setTextColor(termux ? 0xFFFFFFFF : 0xFFFFC107);
        append("Proxmark3 console — commands run via the real Iceman client in Termux.\n"
            + "Readouts come back here. First time? Tap 'Setup Help'.\n"
            + "────────────────────────────────────────\n");
    }

    private boolean isTermuxInstalled() {
        try { getPackageManager().getPackageInfo(TERMUX_PKG, 0); return true; }
        catch (Exception e) { return false; }
    }

    private void runCmd(String cmd) {
        if (cmd.isEmpty()) return;
        if (!isTermuxInstalled()) { showSetup(); return; }
        String invoke = etInvoke.getText().toString().trim();
        if (invoke.isEmpty()) invoke = "pm3 -p /dev/ttyACM0 -c";
        // full shell line: pm3 -p /dev/ttyACM0 -c "hw status"
        String shellLine = invoke + " \"" + cmd.replace("\"", "\\\"") + "\"";
        append("\npm3> " + cmd + "\n");

        Intent i = new Intent();
        i.setClassName(TERMUX_PKG, TERMUX_SERVICE);
        i.setAction("com.termux.RUN_COMMAND");
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", shellLine});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

        // Result PendingIntent: explicit-by-package + MUTABLE so Termux can fill it
        // (Android 14 forbids MUTABLE *implicit* intents; package-scoped is allowed).
        Intent resultIntent = new Intent(RESULT_ACTION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(this, reqCode++, resultIntent, flags);
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);

        try {
            ContextCompat.startForegroundService(this, i);
            append("[sent to Termux — waiting for readout…]\n");
        } catch (Exception e) {
            append("[ERROR] Could not reach Termux: " + e.getMessage()
                + "\nEnable it: Termux > add 'allow-external-apps=true' to ~/.termux/termux.properties,"
                + " and grant this app the RUN_COMMAND permission. See Setup Help.\n");
        }
    }

    private void append(String s) {
        main.post(() -> {
            tvOut.append(s);
            svOut.post(() -> svOut.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // ── Command board ───────────────────────────────────────────────────────
    private static final String[][] COMMANDS = {
        {"hw status",            "Device self-report (fw, flash, FPGA, temp)"},
        {"hw version",           "Client + firmware versions (confirm they match)"},
        {"hw tune",              "Antenna tuning — LF ~20-40kV, HF ~10-20V"},
        {"lf search",            "Sweep 125 kHz: EM4100/HID/Indala/T5577…"},
        {"lf em 410x reader",    "Read an EM4100 125 kHz tag ID"},
        {"lf hid read",          "Read an HID Prox 125 kHz card"},
        {"lf t55xx detect",      "Detect a T5577 writable LF tag"},
        {"hf search",            "Sweep 13.56 MHz: MIFARE/NTAG/iCLASS/15693…"},
        {"hf 14a info",          "ISO14443-A tag info (ATQA/SAK/UID)"},
        {"hf mf info",           "MIFARE Classic info"},
        {"hf mf chk --1k",       "Test the default key dictionary on a 1k card"},
        {"hf mf autopwn",        "Recover keys + dump a MIFARE Classic you own"},
        {"hf mfu info",          "MIFARE Ultralight / NTAG info"},
        {"hf iclass info",       "iCLASS tag info"},
        {"hf 15 info",           "ISO15693 tag info"},
        {"data plot",            "Open the sample buffer plot (if supported)"},
    };

    private void showCommandList() {
        String[] items = new String[COMMANDS.length];
        for (int i = 0; i < COMMANDS.length; i++) items[i] = COMMANDS[i][0] + "\n   " + COMMANDS[i][1];
        new AlertDialog.Builder(this)
            .setTitle("Proxmark3 commands")
            .setItems(items, (d, which) -> {
                String cmd = COMMANDS[which][0];
                etCmd.setText(cmd);
                runCmd(cmd);
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void showSetup() {
        String s =
            "ONE-TIME TERMUX SETUP\n"
          + "─────────────────────\n"
          + "1. Install Termux from F-Droid (NOT the old Play Store build).\n"
          + "2. In Termux, build the Iceman client:\n"
          + "   pkg update && pkg install git build-essential cmake readline \\\n"
          + "       libusb clang pkg-config\n"
          + "   git clone https://github.com/RfidResearchGroup/proxmark3\n"
          + "   cd proxmark3 && make -j$(nproc) && make install\n"
          + "3. Allow this app to drive Termux:\n"
          + "   echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n"
          + "   (then restart Termux)\n"
          + "4. Grant permission: Android Settings > Apps > LEXI RFID >\n"
          + "   Permissions — enable Termux RUN_COMMAND. (Or Termux may prompt.)\n\n"
          + "USB ACCESS (the catch):\n"
          + "  Termux's pm3 must reach the port.\n"
          + "  • Rooted phone w/ usb_acm kernel module: port is /dev/ttyACM0.\n"
          + "    Keep the invocation:  pm3 -p /dev/ttyACM0 -c\n"
          + "  • Not rooted: use the termux-usb fd path. Install Termux:API,\n"
          + "    'pkg install termux-api', then wrap with termux-usb and set the\n"
          + "    invocation to the usb: transport per the proxmark3 termux_notes.\n\n"
          + "Then type a command or tap Command List. Use only on cards you own.";
        new AlertDialog.Builder(this)
            .setTitle("Setup Help")
            .setMessage(s)
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(resultReceiver); } catch (Exception ignored) {}
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
