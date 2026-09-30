package com.kiro.minibrowser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BrowserEngine — programmable WebView wrapper.
 *
 * Responsibilities:
 *  1. WebView lifecycle + settings (JS, DOM storage, zoom, UA)
 *  2. JS injection into loaded pages (DOM interaction)
 *  3. Network interception (shouldInterceptRequest)
 *  4. Navigation control (back/forward/reload/scroll)
 *  5. Event log for automation controller polling
 *
 * Kotlin-style null-safety + sealed result emulated in Java-compatible Kotlin.
 * NOTE: This file is Kotlin source; compiled with kotlinc when available.
 *       Fallback: identical Java file BrowserEngine.java is included (see src-java/).
 */
@SuppressLint("SetavaScriptEnabled", "AddJavascriptInterface")
class BrowserEngine(private val activity: android.app.Activity) {

    val webview: android.webkit.WebView = android.webkit.WebView(activity)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventLog = ConcurrentLinkedQueue<String>()
    private val requestLog = ConcurrentLinkedQueue<String>()
    private val interceptRules = java.util.concurrent.CopyOnWriteArrayList<InterceptRule>()
    private val jsBridge = JsBridge()
    private var currentUrl: String = "about:blank"
    private var pageReady = AtomicBoolean(false)

    /** Rule for network interception (block/modify/replace). */
    data class InterceptRule(
        val urlPattern: Regex,
        val responseBody: ByteArray? = null,
        val contentType: String = "text/plain",
        val statusCode: Int = 200,
        val blockRequest: Boolean = false
    )

    /** Result of a network request capture (for monitoring). */
    data class CapturedRequest(
        val url: String, val method: String, val headers: Map<String,String>,
        val timestamp: Long = System.currentTimeMillis()
    )

    init { setupWebview() }

    @SuppressLint("SetavaScriptEnabled")
    private fun setupWebview() = webview.apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36 MiniBrowser/1.0"
        // Enable remote debugging via chrome://inspect during development
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)

        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean {
                logEvent("NAV_REQUEST $url")
                return false   // let WebView handle it
            }

            override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: Bitmap?) {
                pageReady.set(false)
                currentUrl = url
                logEvent("PAGE_START $url")
            }

            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                pageReady.set(true)
                currentUrl = url
                logEvent("PAGE_DONE $url")
                // Notify automation server that a page load completed
                AutomationServer.notifyEvent("page_loaded", url)
            }

            /**
             * NETWORK INTERCEPTION — monitor, block, or replace responses.
             * Return non-null WebResourceResponse to short-circuit the request.
             */
            override fun shouldInterceptRequest(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val url = request.url.toString()
                requestLog.offer("${request.method} $url")
                AutomationServer.notifyEvent("request", "$url")

                // Check user-defined intercept rules
                for (rule in interceptRules) {
                    if (rule.urlPattern.containsMatchIn(url)) {
                        if (rule.blockRequest) {
                            logEvent("BLOCKED $url")
                            return emptyResponse()
                        }
                        rule.responseBody?.let { body ->
                            logEvent("REPLACED $url")
                            return android.webkit.WebResourceResponse(
                                rule.contentType, "utf-8",
                                ByteArrayInputStream(body)
                            )
                        }
                    }
                }
                return null   // pass through normally
            }
        }

        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                logEvent("CONSOLE [${message.messageLevel()}] ${message.message()}")
                return true
            }
            override fun onJsAlert(view: android.webkit.WebView, url: String?, msg: String?,
                result: android.webkit.JsResult): Boolean {
                logEvent("JS_ALERT $msg")
                result.confirm()
                return true
            }
            override fun onJsConfirm(view: android.webkit.WebView, url: String?, msg: String?,
                result: android.webkit.JsResult): Boolean {
                logEvent("JS_CONFIRM $msg")
                result.confirm()
                return true
            }
        }

        // Expose JsBridge to page JS under window.Android
        addJavascriptInterface(jsBridge, "Android")
    }

    private fun emptyResponse(): android.webkit.WebResourceResponse =
        android.webkit.WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun logEvent(msg: String) {
        eventLog.offer(msg)
        if (eventLog.size > 500) eventLog.poll()   // cap memory
    }

    // ─── NAVIGATION ────────────────────────────────────────────────────────

    fun loadUrl(url: String) = mainHandler.post {
        val fixed = if (!url.startsWith("http") && !url.startsWith("file://")) "https://$url" else url
        logEvent("LOAD $fixed")
        webview.loadUrl(fixed)
    }

    fun goBack() = mainHandler.post { if (webview.canGoBack()) webview.goBack() }
    fun goForward() = mainHandler.post { if (webview.canGoForward()) webview.goForward() }
    fun reload() = mainHandler.post { webview.reload() }
    fun stopLoading() = mainHandler.post { webview.stopLoading() }

    // ─── SCROLL CONTROL ───────────────────────────────────────────────────

    fun scrollTo(x: Int, y: Int) = mainHandler.post { webview.scrollTo(x, y) }
    fun scrollBy(dx: Int, dy: Int) = mainHandler.post { webview.scrollBy(dx, dy) }
    fun scrollToEnd() = mainHandler.post {
        val max = webview.contentHeight * resources.displayMetrics.densityDpi / 160
        webview.scrollTo(0, max.toInt())
    }
    fun scrollToTop() = mainHandler.post { webview.scrollTo(0, 0) }

    // ─── JS INJECTION (DOM INTERACTION) ───────────────────────────────────

    /**
     * Evaluate arbitrary JS in the page context. Result delivered via callback.
     * Works on API 19+ (evaluateJavascript). Falls back to loadUrl("javascript:") on older.
     */
    fun evaluateJs(script: String, callback: ((String) -> Unit)? = null) {
        mainHandler.post {
            webview.evaluateJavascript(script) { result ->
                callback?.invoke(result ?: "null")
                AutomationServer.notifyEvent("js_result", result ?: "null")
            }
        }
    }

    /** Click element by CSS selector. */
    fun clickElement(selector: String) = evaluateJs(JsBridge.jsClick(selector))

    /** Fill input field by CSS selector. */
    fun fillField(selector: String, value: String) = evaluateJs(JsBridge.jsFill(selector, value))

    /** Extract structured text from selector. */
    fun extractText(selector: String) = evaluateJs(JsBridge.jsExtract(selector))

    /** Inject a full <script> tag into page (for longer scripts). */
    fun injectScript(jsCode: String) {
        val wrapped = "(function(){var s=document.createElement('script');" +
            "s.textContent=${jsCode.replace("\"", "\\\"").replace("\n", "\\n")};" +
            "document.head.appendChild(s);})()"
        evaluateJs(wrapped)
    }

    // ─── INTERCEPT RULES ──────────────────────────────────────────────────

    fun addInterceptRule(rule: InterceptRule) { interceptRules.add(rule) }
    fun clearInterceptRules() { interceptRules.clear() }

    // ─── STATE / LOG ACCESS ───────────────────────────────────────────────

    fun getUrl(): String = currentUrl
    fun getTitle(): String = webview.title ?: ""
    fun isPageReady(): Boolean = pageReady.get()
    fun getEventLog(): List<String> = eventLog.toList()
    fun getRequestLog(): List<String> = requestLog.toList()
    fun clearLogs() { eventLog.clear(); requestLog.clear() }

    /** Viewport sizing for programmatic control. */
    fun setViewportSize(widthPx: Int, heightPx: Int) {
        mainHandler.post {
            webview.layoutParams = android.view.ViewGroup.LayoutParams(widthPx, heightPx)
            webview.requestLayout()
        }
    }

    fun screenshot(callback: (Bitmap?) -> Unit) {
        mainHandler.post {
            val bmp = Bitmap.createBitmap(
                webview.width, webview.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            webview.draw(canvas)
            callback(bmp)
        }
    }

    fun destroy() {
        mainHandler.post {
            webview.apply {
                loadUrl("about:blank")
                onPause()
                removeAllViews()
                destroy()
            }
        }
    }

    /** Memory management — call when app goes to background or between sessions. */
    fun onPause() = webview.onPause()
    fun onResume() = webview.onResume()

    private val resources: android.content.res.Resources get() = activity.resources

    companion object {
        const val TAG = "BrowserEngine"
    }
}

/**
 * JsBridge — JS interface exposed to page as `window.Android`.
 * Provides secure DOM helpers callable from injected scripts.
 */
class JsBridge {
    private val results = ConcurrentLinkedQueue<String>()

    /** Called from JS: Android.log("msg") — log from injected script to event log. */
    @android.webkit.JavascriptInterface
    fun log(msg: String) { Log.d("JsBridge", msg) }

    /** Called from JS: Android.result(json) — deliver extraction result back. */
    @android.webkit.JavascriptInterface
    fun result(json: String) { results.offer(json) }

    fun drainResults(): List<String> {
        val out = mutableListOf<String>()
        while (true) { val r = results.poll() ?: break; out.add(r) }
        return out
    }

    companion object {
        /** JS snippet: click first matching element. */
        fun jsClick(selector: String) = """
            (function(){
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (el) { el.click(); return 'CLICKED:' + el.tagName; }
                return 'NOT_FOUND';
            })()
        """.trimIndent()

        /** JS snippet: set value of input field. */
        fun jsFill(selector: String, value: String) = """
            (function(){
                var el = document.querySelector('${selector.replace("'", "\\'")}');
                if (!el) return 'NOT_FOUND';
                el.focus();
                el.value = '${value.replace("'", "\\'").replace("\n", "\\n")}';
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return 'FILLED';
            })()
        """.trimIndent()

        /** JS snippet: extract textContent (or attribute) from all matches. */
        fun jsExtract(selector: String) = """
            (function(){
                var els = document.querySelectorAll('${selector.replace("'", "\\'")}');
                var out = [];
                for (var i = 0; i < els.length; i++) {
                    out.push({text: els[i].innerText || els[i].textContent || '',
                              tag: els[i].tagName, href: els[i].href || ''});
                }
                return JSON.stringify(out);
            })()
        """.trimIndent()

        /** JS snippet: scroll page. */
        fun jsScroll(pixels: Int) = "window.scrollBy(0, $pixels); 'SCROLLED';"

        /** JS snippet: wait for element (poll every 200ms, max 10s). */
        fun jsWaitFor(selector: String) = """
            (function(){
                return new Promise(function(resolve){
                    var el = document.querySelector('$selector');
                    if (el) { resolve('FOUND'); return; }
                    var tries = 0;
                    var iv = setInterval(function(){
                        el = document.querySelector('$selector');
                        if (el || ++tries >= 50) { clearInterval(iv); resolve(el ? 'FOUND' : 'TIMEOUT'); }
                    }, 200);
                });
            })()
        """.trimIndent()
    }
}
