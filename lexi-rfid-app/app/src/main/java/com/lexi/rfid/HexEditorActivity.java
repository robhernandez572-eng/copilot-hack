package com.lexi.rfid;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hex editor for raw NFC card sector/block/page data.
 * Receives "rawData" intent extra, shows editable hex blocks,
 * returns modified "rawData" via setResult().
 */
public class HexEditorActivity extends AppCompatActivity {

    public static final String EXTRA_RAW_DATA = "rawData";
    public static final String EXTRA_TECHNOLOGY = "technology";

    private final List<HexBlock> blocks = new ArrayList<>();
    private HexBlockAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hex_editor);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Hex Editor");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        String rawData = getIntent().getStringExtra(EXTRA_RAW_DATA);
        String technology = getIntent().getStringExtra(EXTRA_TECHNOLOGY);

        parseBlocks(rawData, technology);

        RecyclerView rv = findViewById(R.id.rvHexBlocks);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HexBlockAdapter(blocks, this::showEditDialog);
        rv.setAdapter(adapter);

        Button btnSave = findViewById(R.id.btnHexSave);
        Button btnDiscard = findViewById(R.id.btnHexDiscard);

        btnSave.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_RAW_DATA, buildRawData());
            setResult(RESULT_OK, result);
            finish();
        });
        btnDiscard.setOnClickListener(v -> finish());
    }

    private void parseBlocks(String rawData, String technology) {
        if (rawData == null || rawData.isEmpty()) return;

        // MIFARE sector/block lines: S0B0: AABBCCDD...
        Pattern mifare = Pattern.compile("(S\\d+B\\d+): ([0-9A-Fa-f]+)");
        // Ultralight pages: P04: AABBCCDD
        Pattern ul = Pattern.compile("(P\\d+): ([0-9A-Fa-f]+)");

        boolean foundMifare = false, foundUl = false;
        for (String line : rawData.split("\n")) {
            Matcher m = mifare.matcher(line);
            if (m.find()) {
                blocks.add(new HexBlock(m.group(1), m.group(2).toUpperCase(), false));
                foundMifare = true;
                continue;
            }
            Matcher mu = ul.matcher(line);
            if (mu.find()) {
                int pageNum = Integer.parseInt(mu.group(1));
                boolean readOnly = (pageNum < 4);
                blocks.add(new HexBlock(mu.group(1), mu.group(2).toUpperCase(), readOnly));
                foundUl = true;
            }
        }

        // Mark MIFARE sector trailers (last block in each sector) as sensitive
        if (foundMifare) {
            for (HexBlock b : blocks) {
                String label = b.label;
                if (label.matches("S\\d+B3") || label.matches("S\\d+B15")) {
                    b.isSectorTrailer = true;
                }
            }
        }

        if (blocks.isEmpty()) {
            // Fallback: show raw data as a single read-only block
            blocks.add(new HexBlock("RAW", rawData.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(), true));
        }
    }

    private void showEditDialog(int position) {
        HexBlock block = blocks.get(position);
        if (block.readOnly) {
            Toast.makeText(this, "Page 0-3 are read-only (UID/config)", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit " + block.label);

        EditText et = new EditText(this);
        et.setText(block.hex);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(block.hex.length())});
        et.setHint("Hex bytes (e.g. AABBCCDD...)");
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setPadding(48, 24, 48, 24);

        if (block.isSectorTrailer) {
            builder.setMessage("⚠ Sector trailer — contains keys & access bits. Editing may lock the card.");
        }

        builder.setView(et);
        builder.setPositiveButton("Save", (d, w) -> {
            String val = et.getText().toString().toUpperCase().replaceAll("[^0-9A-F]", "");
            if (val.length() != block.hex.length()) {
                Toast.makeText(this, "Must be " + (block.hex.length() / 2) + " bytes (" + block.hex.length() + " hex chars)", Toast.LENGTH_LONG).show();
                return;
            }
            block.hex = val;
            adapter.notifyItemChanged(position);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private String buildRawData() {
        StringBuilder sb = new StringBuilder();
        for (HexBlock b : blocks) {
            if ("RAW".equals(b.label)) {
                sb.append(b.hex);
            } else {
                sb.append(b.label).append(": ").append(b.hex).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── Model ─────────────────────────────────────────────────────────────

    static class HexBlock {
        String label;
        String hex;
        boolean readOnly;
        boolean isSectorTrailer;

        HexBlock(String label, String hex, boolean readOnly) {
            this.label = label;
            this.hex = hex;
            this.readOnly = readOnly;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    interface OnBlockClickListener { void onBlockClick(int position); }

    static class HexBlockAdapter extends RecyclerView.Adapter<HexBlockAdapter.VH> {
        private final List<HexBlock> blocks;
        private final OnBlockClickListener listener;

        HexBlockAdapter(List<HexBlock> blocks, OnBlockClickListener listener) {
            this.blocks = blocks;
            this.listener = listener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_hex_block, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            HexBlock b = blocks.get(pos);
            h.tvLabel.setText(b.label);
            // Insert spaces every 2 chars for readability
            h.tvHex.setText(addSpaces(b.hex));
            h.tvAscii.setText(hexToAscii(b.hex));

            if (b.isSectorTrailer) {
                h.tvLabel.setTextColor(0xFFFF5722);
                h.itemView.setBackgroundColor(0xFFFFF3E0);
            } else if (b.readOnly) {
                h.tvLabel.setTextColor(0xFF9E9E9E);
                h.itemView.setBackgroundColor(0xFFF5F5F5);
            } else {
                h.tvLabel.setTextColor(0xFF1565C0);
                h.itemView.setBackgroundColor(0xFFFFFFFF);
            }

            h.itemView.setOnClickListener(v -> listener.onBlockClick(h.getAdapterPosition()));
        }

        @Override
        public int getItemCount() { return blocks.size(); }

        private String addSpaces(String hex) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) sb.append(' ');
                sb.append(hex, i, Math.min(i + 2, hex.length()));
            }
            return sb.toString();
        }

        private String hexToAscii(String hex) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < hex.length(); i += 2) {
                int b = Integer.parseInt(hex.substring(i, i + 2), 16);
                sb.append((b >= 32 && b < 127) ? (char) b : '.');
            }
            return sb.toString();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvLabel, tvHex, tvAscii;
            VH(View v) {
                super(v);
                tvLabel = v.findViewById(R.id.tvHexLabel);
                tvHex = v.findViewById(R.id.tvHexBytes);
                tvAscii = v.findViewById(R.id.tvHexAscii);
            }
        }
    }
}
