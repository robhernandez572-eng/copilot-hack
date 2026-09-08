package com.lexi.rfid;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class CardDetailActivity extends AppCompatActivity {

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

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(name);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

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
