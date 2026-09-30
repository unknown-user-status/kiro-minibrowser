package com.kiro.minibrowser;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JsResult;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * BrowserEngine — programmable WebView wrapper (Java build of the Kotlin design).
 *
 * 1. WebView lifecycle + settings (JS, DOM storage, UA, remote debugging)
 * 2. JS injection into loaded pages (DOM interaction)
 * 3. Network interception (shouldInterceptRequest + user rules)
 * 4. Navigation & scroll control
 * 5. Event log + request log for the automation controller
 */
@SuppressWarnings("deprecation")
@SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
public class BrowserEngine {
    public final WebView webview;
    private final android.app.Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<String> eventLog = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> requestLog = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<InterceptRule> interceptRules = new CopyOnWriteArrayList<>();
    private final JsBridge jsBridge = new JsBridge();
    private VirtualCursor vcursor;
    private java.util.function.Consumer<String> urlUpdateCallback;
    private volatile String currentUrl = "about:blank";
    private final AtomicBoolean pageReady = new AtomicBoolean(false);

    /** Rule for network interception (block or replace body). */
    public static class InterceptRule {
        public final Pattern urlPattern;
        public final byte[] responseBody;
        public final String contentType;
        public final boolean blockRequest;
        public InterceptRule(Pattern p, byte[] body, String contentType, boolean block) {
            this.urlPattern = p; this.responseBody = body;
            this.contentType = contentType; this.blockRequest = block;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public BrowserEngine(android.app.Activity activity) {
        this.activity = activity;
        this.webview = new WebView(activity);
        setupWebview();
        this.vcursor = new VirtualCursor(activity);
    }

    /** Attach overlay kursor ke parent WebView (dipanggil dari MainActivity). */
    public void attachCursorOverlay(android.view.ViewGroup container) {
        if (vcursor != null) {
            container.addView(vcursor.getOverlay(), new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            vcursor.showStatus("MiniBrowser siap — automasi aktif");
        }
    }

    public VirtualCursor getVirtualCursor() { return vcursor; }

    /** Callback untuk update URL bar di UI saat navigasi. */
    public void setUrlUpdateCallback(java.util.function.Consumer<String> cb) {
        this.urlUpdateCallback = cb;
    }

    private void notifyUrlUpdate(String url) {
        if (urlUpdateCallback != null) {
            mainHandler.post(new Runnable() { @Override public void run() {
                urlUpdateCallback.accept(url);
            }});
        }
    }

    private void setupWebview() {
        try {
        WebSettings s = webview.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36 MiniBrowser/1.0");
        WebView.setWebContentsDebuggingEnabled(true);

        webview.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                logEvent("NAV_REQUEST " + url);
                return false;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                pageReady.set(false); currentUrl = url; logEvent("PAGE_START " + url);
                notifyUrlUpdate(url);
            }
            @Override public void onPageFinished(WebView v, String url) {
                pageReady.set(true); currentUrl = url;
                logEvent("PAGE_DONE " + url);
                notifyUrlUpdate(url);
                AutomationServer.notifyEvent("page_loaded", url);
            }
            /** NETWORK INTERCEPTION — monitor / block / replace. */
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String url = reqUrl(req);
                requestLog.offer(reqMethod(req) + " " + url);
                AutomationServer.notifyEvent("request", url);
                for (InterceptRule rule : interceptRules) {
                    if (rule.urlPattern.matcher(url).find()) {
                        if (rule.blockRequest) {
                            logEvent("BLOCKED " + url);
                            return emptyResponse();
                        }
                        if (rule.responseBody != null) {
                            logEvent("REPLACED " + url);
                            return new WebResourceResponse(rule.contentType, "utf-8",
                                new ByteArrayInputStream(rule.responseBody));
                        }
                    }
                }
                return null;
            }
        });

        webview.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                logEvent("CONSOLE [" + m.messageLevel() + "] " + m.message());
                return true;
            }
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                logEvent("JS_ALERT " + msg); r.confirm(); return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                logEvent("JS_CONFIRM " + msg); r.confirm(); return true;
            }
        });

        // Expose JsBridge to page JS under window.Android
        webview.addJavascriptInterface(jsBridge, "Android");
        } catch (Throwable t) {
            android.util.Log.e("BrowserEngine", "setupWebview CRASH", t);
        }
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private String reqMethod(WebResourceRequest r) {
        try { return r.getMethod(); } catch (Throwable t) { return "GET"; }
    }
    private String reqUrl(WebResourceRequest r) { return r.getUrl().toString(); }

    private void logEvent(String msg) {
        eventLog.offer(msg);
        while (eventLog.size() > 500) eventLog.poll();   // cap memory
    }

    // ── NAVIGATION ────────────────────────────────────────────────────────
    public void loadUrl(String url) { mainHandler.post(new Runnable() { @Override public void run() {
        String fixed = (url == null || (!url.startsWith("http") && !url.startsWith("file://")))
            ? "https://" + (url == null ? "" : url) : url;
        logEvent("LOAD " + fixed);
        if (vcursor != null) vcursor.navigateIndicator(fixed);
        webview.loadUrl(fixed);
    }}); }
    public void goBack() { mainHandler.post(new Runnable() { @Override public void run() { if (webview.canGoBack()) webview.goBack(); } }); }
    public void goForward() { mainHandler.post(new Runnable() { @Override public void run() { if (webview.canGoForward()) webview.goForward(); } }); }
    public void reload() { mainHandler.post(new Runnable() { @Override public void run() { webview.reload(); } }); }
    public void stopLoading() { mainHandler.post(new Runnable() { @Override public void run() { webview.stopLoading(); } }); }

    // ── SCROLL CONTROL ────────────────────────────────────────────────────
    public void scrollTo(int x, int y) { mainHandler.post(new Runnable() { @Override public void run() { webview.scrollTo(x, y); } }); }
    public void scrollBy(int dx, int dy) { mainHandler.post(new Runnable() { @Override public void run() {
        webview.scrollBy(dx, dy);
        if (vcursor != null) vcursor.scrollIndicator(dy);
    } }); }
    public void scrollToTop() { mainHandler.post(new Runnable() { @Override public void run() {
        webview.scrollTo(0, 0);
        if (vcursor != null) vcursor.showStatus("⬆️ top");
    } }); }
    public void scrollToEnd() { mainHandler.post(new Runnable() { @Override public void run() {
        int max = webview.getContentHeight() * webview.getScale() > 0
            ? (int)(webview.getContentHeight() * webview.getScale()) : 0;
        webview.scrollTo(0, max);
    } }); }

    // ── JS INJECTION (DOM INTERACTION) ────────────────────────────────────
    /** Evaluate arbitrary JS in page context; result delivered via callback. */
    public void evaluateJs(String script, final java.util.function.Consumer<String> callback) {
        mainHandler.post(new Runnable() { @Override public void run() {
            webview.evaluateJavascript(script, new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String result) {
                    String r = result == null ? "null" : result;
                    if (callback != null) callback.accept(r);
                    AutomationServer.notifyEvent("js_result", r);
                }
            });
        }});
    }
    public void clickElement(String selector) {
        // Dapatkan posisi elemen via JS, lalu animasi kursor + highlight + klik
        String js = "(function(){var el=document.querySelector('" + selector.replace("'", "\\'") + "');" +
            "if(!el)return JSON.stringify({err:'NOT_FOUND'});" +
            "var r=el.getBoundingClientRect();" +
            "return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height,tag:el.tagName});" +
            "})()";
        evaluateJs(js, new java.util.function.Consumer<String>() {
            @Override public void accept(String result) {
                try {
                    // result berupa JSON string dengan quotes (mis. "{\"x\":10}")
                    String json = result.startsWith("\"") ? result.substring(1, result.length() - 1) : result;
                    json = json.replace("\\\"", "\"").replace("\\\\", "\\");
                    org.json.JSONObject obj = new org.json.JSONObject(json);
                    if (obj.has("err")) {
                        if (vcursor != null) vcursor.showStatus("❌ " + obj.getString("err") + ": " + selector);
                        // Fallback: klik via JS biasa
                        evaluateJs(JsBridge.jsClick(selector), null);
                        return;
                    }
                    final float x = (float) obj.getDouble("x");
                    final float y = (float) obj.getDouble("y");
                    final float w = (float) obj.getDouble("w");
                    final float h = (float) obj.getDouble("h");
                    final String tag = obj.getString("tag");
                    if (vcursor != null) vcursor.clickAt(x, y, w, h, "CLICK " + tag + " " + selector);
                    // Eksekusi klik setelah animasi (delay 400ms)
                    mainHandler.postDelayed(new Runnable() { @Override public void run() {
                        evaluateJs(JsBridge.jsClick(selector), null);
                    }}, 400);
                } catch (Exception e) {
                    evaluateJs(JsBridge.jsClick(selector), null);
                }
            }
        });
    }
    public void fillField(String selector, String value) {
        String js = "(function(){var el=document.querySelector('" + selector.replace("'", "\\'") + "');" +
            "if(!el)return JSON.stringify({err:'NOT_FOUND'});" +
            "var r=el.getBoundingClientRect();" +
            "return JSON.stringify({x:r.x,y:r.y,w:r.width,h:r.height});" +
            "})()";
        final String val = value;
        evaluateJs(js, new java.util.function.Consumer<String>() {
            @Override public void accept(String result) {
                try {
                    String json = result.startsWith("\"") ? result.substring(1, result.length() - 1) : result;
                    json = json.replace("\\\"", "\"").replace("\\\\", "\\");
                    org.json.JSONObject obj = new org.json.JSONObject(json);
                    if (obj.has("err")) {
                        if (vcursor != null) vcursor.showStatus("❌ NOT_FOUND: " + selector);
                        evaluateJs(JsBridge.jsFill(selector, val), null);
                        return;
                    }
                    final float x = (float) obj.getDouble("x");
                    final float y = (float) obj.getDouble("y");
                    final float w = (float) obj.getDouble("w");
                    final float h = (float) obj.getDouble("h");
                    if (vcursor != null) vcursor.fillAt(x, y, w, h, selector, val);
                    mainHandler.postDelayed(new Runnable() { @Override public void run() {
                        evaluateJs(JsBridge.jsFill(selector, val), null);
                    }}, 400);
                } catch (Exception e) {
                    evaluateJs(JsBridge.jsFill(selector, val), null);
                }
            }
        });
    }
    public void extractText(String selector) { evaluateJs(JsBridge.jsExtract(selector), null); }
    public void waitForElement(String selector) { evaluateJs(JsBridge.jsWaitFor(selector), null); }

    /** Inject a full <script> tag into the page (for longer scripts). */
    public void injectScript(String jsCode) {
        String wrapped = "(function(){var s=document.createElement('script');" +
            "s.textContent=" + jsCode.replace("\\", "\\\\").replace("\"", "\\\"")
                                     .replace("\n", "\\n") + ";" +
            "document.head.appendChild(s);})()";
        evaluateJs(wrapped, null);
    }

    // ── INTERCEPT RULES ───────────────────────────────────────────────────
    public void addInterceptRule(InterceptRule rule) { interceptRules.add(rule); }
    public void clearInterceptRules() { interceptRules.clear(); }
    public int interceptRuleCount() { return interceptRules.size(); }

    // ── STATE / LOG ACCESS ────────────────────────────────────────────────
    public String getUrl() { return currentUrl; }

    /** Thread-safe title read (WebView.getTitle() harus di main thread). */
    public String getTitle() {
        final String[] out = new String[1];
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            String t = webview.getTitle();
            out[0] = t == null ? "" : t;
        } else {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mainHandler.post(new Runnable() { @Override public void run() {
                String t = webview.getTitle();
                out[0] = t == null ? "" : t;
                latch.countDown();
            }});
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        }
        return out[0] == null ? "" : out[0];
    }

    public boolean isPageReady() { return pageReady.get(); }
    public List<String> getEventLog() { return new ArrayList<>(eventLog); }
    public List<String> getRequestLog() { return new ArrayList<>(requestLog); }
    public void clearLogs() { eventLog.clear(); requestLog.clear(); }

    /** Viewport sizing for programmatic control. */
    public void setViewportSize(int w, int h) { mainHandler.post(new Runnable() { @Override public void run() {
        webview.setLayoutParams(new android.view.ViewGroup.LayoutParams(w, h));
        webview.requestLayout();
    }}); }

    public void screenshot(final java.util.function.Consumer<Bitmap> callback) {
        mainHandler.post(new Runnable() { @Override public void run() {
            try {
                Bitmap bmp = Bitmap.createBitmap(
                    Math.max(webview.getWidth(), 1), Math.max(webview.getHeight(), 1),
                    Bitmap.Config.ARGB_8888);
                webview.draw(new Canvas(bmp));
                callback.accept(bmp);
            } catch (Throwable t) { callback.accept(null); }
        }});
    }

    /** Memory management — call when app goes to background or between sessions. */
    public void onPause() { webview.onPause(); }
    public void onResume() { webview.onResume(); }
    public void destroy() { mainHandler.post(new Runnable() { @Override public void run() {
        if (vcursor != null) vcursor.destroy();
        webview.loadUrl("about:blank");
        webview.onPause();
        webview.removeAllViews();
        webview.destroy();
    }}); }
}
