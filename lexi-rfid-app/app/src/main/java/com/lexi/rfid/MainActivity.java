package com.lexi.rfid;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private CardStorage storage;
    private CardAdapter adapter;
    private List<CardData> cards;
    private TextView tvStatus;
    private RecyclerView recyclerView;
    private View emptyView;
    private boolean scanMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storage = new CardStorage(this);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        tvStatus = findViewById(R.id.tvStatus);
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);

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

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> toggleScanMode());

        setupNfc();
        updateEmptyView();
        handleIntent(getIntent());
    }

    private void setupNfc() {
        if (nfcAdapter == null) {
            tvStatus.setText("NFC not available on this device");
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

    private void toggleScanMode() {
        scanMode = !scanMode;
        if (scanMode) {
            if (nfcAdapter == null || !nfcAdapter.isEnabled()) {
                Snackbar.make(recyclerView, "Please enable NFC in Settings", Snackbar.LENGTH_LONG).show();
                scanMode = false;
                return;
            }
            tvStatus.setText("Ready to scan — hold card to back of phone");
            tvStatus.setBackgroundColor(0xFF4CAF50);
        } else {
            tvStatus.setText("Tap the + button to start scanning");
            tvStatus.setBackgroundColor(0xFF2196F3);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && scanMode) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, null);
        }
        cards = storage.loadCards();
        adapter.updateCards(cards);
        updateEmptyView();
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
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) processTag(tag);
        }
    }

    private void processTag(Tag tag) {
        try {
            vibrate();
            CardData card = NfcHelper.readTag(tag);
            storage.saveCard(card);
            cards = storage.loadCards();
            adapter.updateCards(cards);
            updateEmptyView();
            tvStatus.setText("Scanned: " + card.getName() + " (" + card.getTypeLabel() + ")");
            tvStatus.setBackgroundColor(0xFF4CAF50);
            Toast.makeText(this, "Card saved: " + card.getUid(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            tvStatus.setText("Error reading card: " + e.getMessage());
            tvStatus.setBackgroundColor(0xFFF44336);
        }
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
                cards = storage.loadCards();
                adapter.updateCards(cards);
                updateEmptyView();
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
                    cards = storage.loadCards();
                    adapter.updateCards(cards);
                    updateEmptyView();
                })
                .setNegativeButton("Cancel", null)
                .show();
            return true;
        }
        if (item.getItemId() == R.id.action_about) {
            new AlertDialog.Builder(this)
                .setTitle("LEXI RFID NFC Reader")
                .setMessage("Compatible with:\n• MIFARE Classic (1K/4K)\n• MIFARE Ultralight\n• NDEF tags\n• ISO 14443-4 (ISO-DEP)\n• ISO 15693 (NFC-V)\n• NFC-A / NFC-B / NFC-F\n• 125 KHz LF (via LEXI USB device)\n\nHold card to back of phone to read.\nLong-press a saved card to delete.")
                .setPositiveButton("OK", null)
                .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
