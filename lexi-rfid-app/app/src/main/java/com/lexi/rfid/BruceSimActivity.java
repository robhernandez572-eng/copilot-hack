package com.lexi.rfid;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bruce / HaleHound OS SIMULATOR.
 *
 * A cosmetic, phone-only demo of the firmware's menu system. Nothing here
 * touches real radios — a phone has no CC1101 / NRF24 / PN532 / IR hardware,
 * and Android blocks raw WiFi/BLE. Every leaf action shows a clearly labelled
 * simulated screen with fake demo data so you can explore the UI/flow only.
 */
public class BruceSimActivity extends AppCompatActivity {

    /** A menu node: either a submenu (children non-empty) or an action leaf. */
    static class Node {
        final String title;
        final String glyph;
        final List<Node> children = new ArrayList<>();
        String simText; // shown when it's a leaf action

        Node(String glyph, String title) { this.glyph = glyph; this.title = title; }

        Node child(String glyph, String title) {
            Node n = new Node(glyph, title);
            children.add(n);
            return this;
        }
        Node leaf(String glyph, String title, String simText) {
            Node n = new Node(glyph, title);
            n.simText = simText;
            children.add(n);
            return this;
        }
        boolean isLeaf() { return children.isEmpty(); }
    }

    private final Deque<Node> stack = new ArrayDeque<>();
    private Node root;
    private TextView tvPath;
    private RecyclerView recycler;
    private MenuAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Bruce OS — Simulator");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(0xFF000000);

        // Simulation banner
        TextView banner = new TextView(this);
        banner.setText("▓ SIMULATION — no hardware ▓  UI demo only");
        banner.setTextColor(0xFF000000);
        banner.setBackgroundColor(0xFFFFC107);
        banner.setPadding(24, 12, 24, 12);
        banner.setGravity(Gravity.CENTER);
        banner.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        banner.setTextSize(12);
        rootView.addView(banner, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Current path
        tvPath = new TextView(this);
        tvPath.setTextColor(0xFF00FF41);
        tvPath.setBackgroundColor(0xFF0A0A0A);
        tvPath.setPadding(24, 16, 24, 16);
        tvPath.setTypeface(Typeface.MONOSPACE);
        tvPath.setTextSize(13);
        rootView.addView(tvPath, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Menu list
        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        rootView.addView(recycler, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootView);

        root = buildMenu();
        stack.push(root);
        adapter = new MenuAdapter();
        recycler.setAdapter(adapter);
        render();
    }

    private void render() {
        Node cur = stack.peek();
        StringBuilder path = new StringBuilder();
        // Build breadcrumb from bottom of stack up
        List<Node> asList = new ArrayList<>(stack);
        for (int i = asList.size() - 1; i >= 0; i--) {
            path.append(asList.get(i).title);
            if (i > 0) path.append(" › ");
        }
        tvPath.setText("/ " + path);
        adapter.notifyDataSetChanged();
    }

    private void openNode(Node n) {
        if (n.isLeaf()) {
            showSimScreen(n);
        } else {
            stack.push(n);
            render();
        }
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.pop();
            render();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    private void showSimScreen(Node leaf) {
        String body = "▓▓▓ SIMULATION ▓▓▓\n"
            + "This is a UI demo. It does NOT use any radio.\n"
            + "Real operation needs an ESP32 board + hardware.\n"
            + "────────────────────────────\n\n"
            + (leaf.simText != null ? leaf.simText : "(demo screen)");

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFF00FF41);
        tv.setTextSize(12);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);

        new AlertDialog.Builder(this)
            .setTitle(leaf.glyph + "  " + leaf.title)
            .setView(tv)
            .setPositiveButton("Back to menu", null)
            .show();
    }

    // ── Menu tree (cosmetic mirror of Bruce / HaleHound) ────────────────────
    private Node buildMenu() {
        Node r = new Node("", "Main");

        Node wifi = new Node("📶", "WiFi");
        wifi.leaf("🔍", "WiFi Scan",
            "Scanning 2.4GHz…\n\n"
          + " #  SSID              RSSI  CH\n"
          + " 1  SIM_Cafe_Guest    -42   6\n"
          + " 2  SIM_Office_5G     -55  11\n"
          + " 3  SIM_HOME-2A4F     -61   1\n"
          + " 4  SIM_Printer_Net   -70   3\n\n"
          + "[demo list — not real networks]");
        wifi.leaf("📡", "Beacon Spam", "Broadcasting demo beacon frames…\n(SIMULATED — nothing transmitted)");
        wifi.leaf("✖", "Deauth", "Target: SIM_Office_5G\nStatus: SIMULATED — no packets sent.\nReal deauth is illegal without authorization.");
        wifi.leaf("🕸", "Evil Portal", "Captive portal template loaded (demo).\nNo AP started. No credentials captured.");
        wifi.leaf("🚗", "Wardriving", "GPS: (no fix — simulated)\nLogged 0 real APs. Demo mode.");
        wifi.leaf("👃", "Sniffer", "Packet sniffer view (demo frames only).");
        r.children.add(wifi);

        Node ble = new Node("🅱", "Bluetooth");
        ble.leaf("🔍", "BLE Scan",
            "Scanning BLE…\n\n"
          + " Name            MAC            RSSI\n"
          + " SIM_Earbuds     AA:BB:..:01    -48\n"
          + " SIM_Watch       AA:BB:..:02    -66\n"
          + " (unnamed)       AA:BB:..:03    -72\n\n"
          + "[demo devices]");
        ble.leaf("📡", "BLE Beacon Spam", "Advertising demo beacons…\n(SIMULATED)");
        ble.leaf("🍎", "iOS Spam", "SIMULATED — no advertising sent.");
        ble.leaf("🤖", "Android Spam", "SIMULATED — no advertising sent.");
        r.children.add(ble);

        Node rf = new Node("〰", "RF / SubGHz");
        rf.leaf("🔍", "Scan / Copy", "Listening 433.92 MHz (demo)…\nNo signal captured (SIMULATED).");
        rf.leaf("📊", "Spectrum", "[░░▂▃▅▂░░ demo spectrum ░░▃▅▂░░]\nSIMULATED sweep.");
        rf.leaf("▶", "Replay", "No captured frame. Demo only.");
        rf.leaf("〽", "Custom SubGHz", "Enter freq/mod (demo form). Nothing transmitted.");
        r.children.add(rf);

        Node rfid = new Node("💳", "RFID / NFC");
        rfid.leaf("🔍", "Read Tag", "Present tag to PN532…\nNo reader hardware (SIMULATED).\nTip: use the real NFC Scanner tab in this app!");
        rfid.leaf("🏷", "Read 125kHz", "LF antenna required (SIMULATED).");
        rfid.leaf("📋", "Clone", "Nothing to clone. Demo screen.");
        rfid.leaf("🎮", "Amiibo", "Amiibo dump list (demo, empty).");
        r.children.add(rfid);

        Node ir = new Node("🔦", "Infrared");
        ir.leaf("📺", "TV-B-Gone", "Blasting power-off codes… (SIMULATED — no IR LED)");
        ir.leaf("🔍", "Read IR", "Point remote at receiver… (no hardware, SIMULATED)");
        ir.leaf("▶", "Replay IR", "No captured code. Demo.");
        r.children.add(ir);

        Node nrf = new Node("📻", "NRF24");
        nrf.leaf("🔍", "Scan Channels", "[demo channel activity map]\nSIMULATED.");
        nrf.leaf("📊", "Analyzer", "2.4GHz analyzer (demo view).");
        r.children.add(nrf);

        Node others = new Node("⚙", "Others");
        others.leaf("🕐", "Clock", "12:34:56 (device time, demo).");
        others.leaf("🔳", "QR Codes", "QR generator (demo).");
        others.leaf("🎲", "Games", "Mini-games menu (demo).");
        others.leaf("📜", "Interpreter", "JS/Bruce script runner (demo, disabled).");
        r.children.add(others);

        Node cfg = new Node("🔧", "Config");
        cfg.leaf("💡", "Brightness", "Brightness slider (demo).");
        cfg.leaf("🔁", "Orientation", "Rotate UI (demo).");
        cfg.leaf("ℹ", "About",
            "Bruce OS Simulator\n"
          + "Cosmetic UI demo inside the LEXI app.\n"
          + "Firmware: HaleHound X ESP32-DIV X Bruce (CYD).\n"
          + "To run it for real, flash an ESP32 CYD board.");
        r.children.add(cfg);

        return r;
    }

    // ── Adapter ─────────────────────────────────────────────────────────────
    private class MenuAdapter extends RecyclerView.Adapter<MenuAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(TextView tv) { super(tv); this.tv = tv; }
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(BruceSimActivity.this);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(16);
            tv.setTextColor(0xFF00FF41);
            int p = (int) (16 * getResources().getDisplayMetrics().density);
            tv.setPadding(p, p, p, p);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setBackgroundColor(0xFF000000);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            Node cur = stack.peek();
            Node n = cur.children.get(pos);
            String arrow = n.isLeaf() ? "" : "  ›";
            h.tv.setText("  " + n.glyph + "  " + n.title + arrow);
            h.tv.setOnClickListener(v -> openNode(n));
        }

        @Override
        public int getItemCount() {
            Node cur = stack.peek();
            return cur == null ? 0 : cur.children.size();
        }
    }
}
