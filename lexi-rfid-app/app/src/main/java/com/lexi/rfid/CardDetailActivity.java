package com.lexi.rfid;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class CardDetailActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private boolean writeMode = false;

    private CardData card;
    private Button btnWriteToTag, btnCancelWrite;
    private TextView tvWriteStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_detail);

        String uid = getIntent().getStringExtra("uid");
        String name = getIntent().getStringExtra("name");
        String rawData = getIntent().getStringExtra("rawData");
        String type = getIntent().getStringExtra("type");
        String technology = getIntent().getStringExtra("technology");
        String timestamp = getIntent().getStringExtra("timestamp");

        // Reconstruct CardData for writing
        CardData.CardType ct = CardData.CardType.NFC_OTHER;
        if ("MIFARE Classic".equals(technology)) ct = CardData.CardType.NFC_MIFARE;
        else if ("NDEF".equals(technology)) ct = CardData.CardType.NFC_NDEF;
        else if (technology != null && technology.startsWith("ISO-DEP")) ct = CardData.CardType.NFC_ISO_DEP;
        card = new CardData(uid != null ? uid : "", rawData != null ? rawData : "", ct,
            technology != null ? technology : "");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(name);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvWriteStatus = findViewById(R.id.tvWriteStatus);
        btnWriteToTag = findViewById(R.id.btnWriteToTag);
        btnCancelWrite = findViewById(R.id.btnCancelWrite);

        TextView tvUid = findViewById(R.id.tvDetailUid);
        TextView tvType = findViewById(R.id.tvDetailType);
        TextView tvTech = findViewById(R.id.tvDetailTech);
        TextView tvTime = findViewById(R.id.tvDetailTime);
        TextView tvRaw = findViewById(R.id.tvDetailRaw);
        Button btnCopyUid = findViewById(R.id.btnCopyUid);
        Button btnCopyRaw = findViewById(R.id.btnCopyRaw);

        tvUid.setText(uid != null ? uid : "—");
        tvType.setText(type != null ? type : "—");
        tvTech.setText(technology != null ? technology : "—");
        tvTime.setText(timestamp != null ? timestamp : "—");
        tvRaw.setText(rawData != null && !rawData.isEmpty() ? rawData : "No additional data");

        btnCopyUid.setOnClickListener(v -> copyToClipboard("UID", uid));
        btnCopyRaw.setOnClickListener(v -> copyToClipboard("Card Data", rawData));

        btnWriteToTag.setOnClickListener(v -> startWriteMode());
        btnCancelWrite.setOnClickListener(v -> stopWriteMode());

        // Set up NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null) {
            pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE);
            intentFilters = new IntentFilter[]{
                new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
                new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
            };
        } else {
            btnWriteToTag.setEnabled(false);
            btnWriteToTag.setText("NFC not available");
        }
    }

    private void startWriteMode() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Enable NFC in Settings first", Toast.LENGTH_LONG).show();
            return;
        }
        writeMode = true;
        tvWriteStatus.setVisibility(View.VISIBLE);
        btnWriteToTag.setVisibility(View.GONE);
        btnCancelWrite.setVisibility(View.VISIBLE);
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
        vibrate();
    }

    private void stopWriteMode() {
        writeMode = false;
        tvWriteStatus.setVisibility(View.GONE);
        btnWriteToTag.setVisibility(View.VISIBLE);
        btnCancelWrite.setVisibility(View.GONE);
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (writeMode && nfcAdapter != null) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!writeMode) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) performWrite(tag);
        }
    }

    private void performWrite(Tag tag) {
        tvWriteStatus.setText("Writing...");
        new Thread(() -> {
            String result = NfcHelper.writeTag(tag, card);
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

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(100);
        } catch (Exception ignored) {}
    }

    private void copyToClipboard(String label, String text) {
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
