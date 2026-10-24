package com.lexi.rfid;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class CardDetailActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private boolean writeMode = false;
    private boolean customKeyReadMode = false;
    private byte[] pendingKeyA = MifareClassic.KEY_DEFAULT;
    private byte[] pendingKeyB = MifareClassic.KEY_DEFAULT;

    private CardData card;
    private CardStorage storage;
    private Button btnWriteToTag, btnCancelWrite;
    private TextView tvWriteStatus, tvDetailRaw, tvNotes, tvCategory;

    private final ActivityResultLauncher<Intent> hexEditorLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String newRaw = result.getData().getStringExtra(HexEditorActivity.EXTRA_RAW_DATA);
                if (newRaw != null) {
                    card.setRawData(newRaw);
                    tvDetailRaw.setText(newRaw);
                    storage.saveCard(card);
                    Toast.makeText(this, "Hex changes saved", Toast.LENGTH_SHORT).show();
                }
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_detail);

        storage = new CardStorage(this);

        String uid       = getIntent().getStringExtra("uid");
        String name      = getIntent().getStringExtra("name");
        String rawData   = getIntent().getStringExtra("rawData");
        String type      = getIntent().getStringExtra("type");
        String technology= getIntent().getStringExtra("technology");
        String timestamp = getIntent().getStringExtra("timestamp");
        String notes     = getIntent().getStringExtra("notes");
        String category  = getIntent().getStringExtra("category");

        // Load the full card from storage so we have notes/category
        CardData stored = storage.findByUid(uid);
        CardData.CardType ct = CardData.CardType.NFC_OTHER;
        if (technology != null) {
            if (technology.startsWith("MIFARE Classic")) ct = CardData.CardType.NFC_MIFARE;
            else if (technology.equals("NDEF"))          ct = CardData.CardType.NFC_NDEF;
            else if (technology.startsWith("ISO-DEP"))   ct = CardData.CardType.NFC_ISO_DEP;
            else if (technology.startsWith("125"))       ct = CardData.CardType.LF_125KHZ;
        }
        if (stored != null) {
            card = stored;
        } else {
            card = new CardData(uid != null ? uid : "", rawData != null ? rawData : "", ct,
                technology != null ? technology : "");
            if (notes    != null) card.setNotes(notes);
            if (category != null) card.setCategory(category);
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(name != null ? name : card.getName());
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvWriteStatus = findViewById(R.id.tvWriteStatus);
        btnWriteToTag = findViewById(R.id.btnWriteToTag);
        btnCancelWrite = findViewById(R.id.btnCancelWrite);
        tvDetailRaw   = findViewById(R.id.tvDetailRaw);
        tvNotes       = findViewById(R.id.tvNotes);
        tvCategory    = findViewById(R.id.tvCategory);

        // Populate fields
        ((TextView) findViewById(R.id.tvDetailUid)).setText(card.getUid());
        ((TextView) findViewById(R.id.tvDetailType)).setText(type != null ? type : card.getTypeLabel());
        ((TextView) findViewById(R.id.tvDetailTech)).setText(technology != null ? technology : card.getTechnology());
        ((TextView) findViewById(R.id.tvDetailTime)).setText(timestamp != null ? timestamp : card.getTimestamp());
        tvDetailRaw.setText(card.getRawData().isEmpty() ? "No data" : card.getRawData());
        tvNotes.setText(card.getNotes().isEmpty() ? "(no notes)" : card.getNotes());
        tvCategory.setText(card.getCategory());

        // Copy buttons
        findViewById(R.id.btnCopyUid).setOnClickListener(v -> copyToClipboard("UID", card.getUid()));
        findViewById(R.id.btnCopyRaw).setOnClickListener(v -> copyToClipboard("Card Data", card.getRawData()));

        // Write
        btnWriteToTag.setOnClickListener(v -> startWriteMode());
        btnCancelWrite.setOnClickListener(v -> stopWriteMode());

        // Hex editor
        findViewById(R.id.btnHexEditor).setOnClickListener(v -> {
            Intent intent = new Intent(this, HexEditorActivity.class);
            intent.putExtra(HexEditorActivity.EXTRA_RAW_DATA, card.getRawData());
            intent.putExtra(HexEditorActivity.EXTRA_TECHNOLOGY, card.getTechnology());
            hexEditorLauncher.launch(intent);
        });

        // Custom Key re-read (only for MIFARE)
        Button btnCustomKeys = findViewById(R.id.btnCustomKeys);
        if (card.getCardType() == CardData.CardType.NFC_MIFARE) {
            btnCustomKeys.setVisibility(View.VISIBLE);
            btnCustomKeys.setOnClickListener(v -> showCustomKeyDialog());
        }

        // Edit notes
        findViewById(R.id.btnEditNotes).setOnClickListener(v -> showNotesDialog());

        // Edit category
        findViewById(R.id.btnEditCategory).setOnClickListener(v -> showCategoryDialog());

        // NFC setup
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
            btnCustomKeys.setEnabled(false);
        }
    }

    // ── Write mode ────────────────────────────────────────────────────────

    private void startWriteMode() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Enable NFC in Settings first", Toast.LENGTH_LONG).show();
            return;
        }
        writeMode = true;
        customKeyReadMode = false;
        tvWriteStatus.setText("Hold a blank NFC tag to back of phone...");
        tvWriteStatus.setBackgroundColor(0xFFFF9800);
        tvWriteStatus.setVisibility(View.VISIBLE);
        btnWriteToTag.setVisibility(View.GONE);
        btnCancelWrite.setVisibility(View.VISIBLE);
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
        vibrate();
    }

    private void stopWriteMode() {
        writeMode = false;
        customKeyReadMode = false;
        tvWriteStatus.setVisibility(View.GONE);
        btnWriteToTag.setVisibility(View.VISIBLE);
        btnCancelWrite.setVisibility(View.GONE);
        if (nfcAdapter != null) nfcAdapter.disableForegroundDispatch(this);
    }

    // ── Custom key read ───────────────────────────────────────────────────

    private void showCustomKeyDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom_keys, null);
        EditText etKeyA = dialogView.findViewById(R.id.etKeyA);
        EditText etKeyB = dialogView.findViewById(R.id.etKeyB);
        etKeyA.setText("FFFFFFFFFFFF");
        etKeyB.setText("FFFFFFFFFFFF");

        new AlertDialog.Builder(this)
            .setTitle("MIFARE Custom Keys")
            .setMessage("Enter Key A and Key B (12 hex chars each = 6 bytes).\nApp will try these first, then common defaults.")
            .setView(dialogView)
            .setPositiveButton("Scan with Keys", (d, w) -> {
                String rawA = etKeyA.getText().toString().toUpperCase().replaceAll("[^0-9A-F]", "");
                String rawB = etKeyB.getText().toString().toUpperCase().replaceAll("[^0-9A-F]", "");
                if (rawA.length() != 12 || rawB.length() != 12) {
                    Toast.makeText(this, "Keys must be exactly 12 hex chars (6 bytes)", Toast.LENGTH_LONG).show();
                    return;
                }
                pendingKeyA = hexStringToBytes(rawA);
                pendingKeyB = hexStringToBytes(rawB);
                startCustomKeyReadMode();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void startCustomKeyReadMode() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
            Toast.makeText(this, "Enable NFC in Settings first", Toast.LENGTH_LONG).show();
            return;
        }
        customKeyReadMode = true;
        writeMode = false;
        tvWriteStatus.setText("Hold the MIFARE card to back of phone to re-read...");
        tvWriteStatus.setBackgroundColor(0xFF7B1FA2);
        tvWriteStatus.setVisibility(View.VISIBLE);
        btnWriteToTag.setVisibility(View.GONE);
        btnCancelWrite.setVisibility(View.VISIBLE);
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
    }

    // ── Notes / Category ──────────────────────────────────────────────────

    private void showNotesDialog() {
        EditText et = new EditText(this);
        et.setText(card.getNotes());
        et.setHint("Add notes about this card...");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        et.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
            .setTitle("Card Notes")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                card.setNotes(et.getText().toString().trim());
                tvNotes.setText(card.getNotes().isEmpty() ? "(no notes)" : card.getNotes());
                storage.saveCard(card);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showCategoryDialog() {
        String[] categories = {"General", "Work", "Home", "Access Control", "Transport", "Hotel", "Test", "Other"};
        int current = 0;
        for (int i = 0; i < categories.length; i++) {
            if (categories[i].equals(card.getCategory())) { current = i; break; }
        }
        new AlertDialog.Builder(this)
            .setTitle("Card Category")
            .setSingleChoiceItems(categories, current, (d, which) -> {
                card.setCategory(categories[which]);
                tvCategory.setText(card.getCategory());
                storage.saveCard(card);
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── NFC callbacks ─────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        if ((writeMode || customKeyReadMode) && nfcAdapter != null) {
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
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) return;

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;

        if (writeMode) {
            performWrite(tag);
        } else if (customKeyReadMode) {
            performCustomKeyRead(tag);
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

    private void performCustomKeyRead(Tag tag) {
        tvWriteStatus.setText("Reading with custom keys...");
        byte[] ka = pendingKeyA;
        byte[] kb = pendingKeyB;
        new Thread(() -> {
            String newRaw = NfcHelper.readMifareWithKeys(tag, ka, kb);
            runOnUiThread(() -> {
                vibrate();
                stopWriteMode();
                card.setRawData(newRaw);
                tvDetailRaw.setText(newRaw);
                storage.saveCard(card);
                Toast.makeText(this, "Re-read complete with custom keys", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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

    private static byte[] hexStringToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
