package com.lexi.rfid;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Companion to a board already running the firmware.
 *
 * The firmware exposes a WiFi web UI; this just hosts it in a WebView. Join the
 * board's AP (or the same LAN) and enter its IP. All control logic lives in the
 * firmware — this is a browser window, nothing more.
 */
public class BoardWebActivity extends AppCompatActivity {

    private WebView web;
    private EditText etUrl;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_board_web);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Board Web UI");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        web = findViewById(R.id.webBoard);
        etUrl = findViewById(R.id.etBoardUrl);
        Button go = findViewById(R.id.btnBoardGo);
        TextView hint = findViewById(R.id.tvBoardHint);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return false; // keep navigation inside the WebView
            }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    hint.setText("Could not reach board. Check that you're on its WiFi and the IP is right.");
                }
            }
        });

        go.setOnClickListener(v -> load());
        etUrl.setOnEditorActionListener((v, a, e) -> { load(); return true; });
    }

    private void load() {
        String raw = etUrl.getText().toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter the board's IP or hostname", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        web.loadUrl(raw);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
