package com.kiro.minibrowser;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * MainActivity — hosts BrowserEngine + AutomationServer.
 *
 * Lifecycle:
 *  onCreate -> build engine, wire UI, start HTTP automation API
 *  onPause  -> engine.onPause() (WebView pauses timers/JS)
 *  onResume -> engine.onResume()
 *  onDestroy-> server.stop() + engine.destroy() (prevents WebView leak)
 *
 * Automation entry from ADB:
 *   adb forward tcp:18080 tcp:8080
 *   curl http://127.0.0.1:18080/navigate -d '{"url":"https://example.com"}'
 */
public class MainActivity extends Activity {
    private BrowserEngine engine;

    private static void logPub(String msg) {
        try {
            java.io.File f = new java.io.File(
                "/storage/emulated/0/Download/minibrowser_log.txt");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(new java.util.Date() + " | " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logPub("onCreate START");
        try {
        setContentView(R.layout.activity_main);
        logPub("setContentView OK");

        WebView webview = findViewById(R.id.webview);
        engine = new BrowserEngine(this);
        // Replace the placeholder WebView with our configured engine view
        android.view.ViewGroup parent = (android.view.ViewGroup) webview.getParent();
        int idx = parent.indexOfChild(webview);
        parent.removeView(webview);
        parent.addView(engine.webview, idx,
            new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        // Attach virtual cursor overlay (di atas WebView)
        engine.attachCursorOverlay(parent);

        logPub("engine created, starting server");
        AutomationServer.start(engine);
        logPub("server start() called");
        // Start foreground service agar Android tidak freeze app saat background
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(new android.content.Intent(this, AutomationService.class));
            } else {
                startService(new android.content.Intent(this, AutomationService.class));
            }
            logPub("foreground service started");
        } catch (Throwable t) {
            logPub("fg service error: " + t.getMessage());
        }

        EditText urlBar = findViewById(R.id.urlBar);
        // Update URL bar saat load (biar user lihat URL aktif)
        engine.setUrlUpdateCallback(new java.util.function.Consumer<String>() {
            @Override public void accept(String url) {
                urlBar.setText(url);
            }
        });
        Button goBtn = findViewById(R.id.goBtn);
        goBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            String url = urlBar.getText().toString().trim();
            if (!url.isEmpty()) engine.loadUrl(url);
        }});
        urlBar.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                engine.loadUrl(urlBar.getText().toString().trim());
                return true;
            }
        });

        // Handle deep-link Intents: minibrowser://open?url=...
        android.content.Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            String url = intent.getData().getQueryParameter("url");
            if (url != null) engine.loadUrl(url);
        }
        } catch (Throwable t) {
            android.util.Log.e("MiniBrowser", "CRASH onCreate", t);
            logPub("CRASH: " + t.getClass().getName() + ": " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            logPub("STACK: " + sw.toString().substring(0, Math.min(2000, sw.toString().length())));
            android.widget.Toast.makeText(this, "CRASH: " + t.getMessage(),
                android.widget.Toast.LENGTH_LONG).show();
            // Fallback: buka layout tanpa WebView config
        }
    }

    @Override protected void onPause() { super.onPause(); engine.onPause(); }
    @Override protected void onResume() { super.onResume(); engine.onResume(); }

    @Override protected void onDestroy() {
        AutomationServer.stop();
        engine.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (engine != null && engine.webview.canGoBack()) engine.goBack();
        else super.onBackPressed();
    }
}
