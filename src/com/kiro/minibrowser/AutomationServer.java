package com.kiro.minibrowser;

import android.graphics.Bitmap;
import android.util.Log;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AutomationServer — lightweight local HTTP API (port 8080) exposing
 * step-by-step browser commands to external controllers.
 * Binds loopback only. Minimal HTTP/1.1 on raw ServerSocket (no sun.* deps).
 *
 * Endpoints:
 *   GET  /health                    {"ok":true,"url":"..."}
 *   GET  /state                     url/title/ready/counts
 *   POST /navigate   {"url":...}
 *   POST /click      {"selector":"#btn"}
 *   POST /fill       {"selector":...,"value":...}
 *   POST /extract    {"selector":"div.item"}
 *   POST /js         {"script":"..."}
 *   POST /scroll     {"dx":0,"dy":500} | {"to":"end"|"top"}
 *   POST /nav        {"action":"back"|"forward"|"reload"|"stop"}
 *   POST /intercept  {"pattern":"\\.css$","block":true}
 *   GET  /intercept                  (rule count)
 *   GET  /events                     (event log tail)
 *   GET  /requests                   (captured request log)
 *   POST /viewport   {"w":400,"h":800}
 *   GET  /screenshot                 (PNG bytes)
 *   POST /clear                      (clear logs)
 */
public class AutomationServer {
    private static final int PORT = 8080;
    private static volatile ServerSocket server;
    private static volatile BrowserEngine engine;
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);

    private static final java.util.concurrent.ConcurrentLinkedQueue<String> apiEvents =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Called by BrowserEngine to push events into the automation pipeline. */
    public static void notifyEvent(String type, String data) {
        apiEvents.offer(type + " | " + (data == null ? "" : data.length() > 300 ? data.substring(0, 300) + "..." : data));
        while (apiEvents.size() > 200) apiEvents.poll();
    }

    /** Get API-level events (js_result, page_loaded, dsb). */
    public static List<String> getApiEvents() { return new ArrayList<>(apiEvents); }

    public static synchronized void start(BrowserEngine eng) {
        engine = eng;
        if (server != null) return;
        Thread t = new Thread(new Runnable() { @Override public void run() {
            try {
                server = new ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"));
                Log.i("AutomationServer", "HTTP API listening on http://127.0.0.1:" + PORT);
                while (server != null && !server.isClosed()) {
                    try {
                        Socket sock = server.accept();
                        pool.execute(new Runnable() { @Override public void run() { handleConnection(sock); } });
                    } catch (IOException e) {
                        if (server != null) Log.w("AutomationServer", "accept: " + e);
                    }
                }
            } catch (IOException e) {
                Log.e("AutomationServer", "start failed", e);
            }
        }}, "AutomationServer");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
            server = null;
        }
    }

    /** Handle one HTTP connection on the raw socket (minimal HTTP/1.1). */
    private static void handleConnection(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(s.getInputStream(), "UTF-8"));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int contentLen = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:"))
                    contentLen = Integer.parseInt(line.substring(15).trim());
            }
            char[] buf = new char[contentLen];
            int read = 0;
            while (read < contentLen) {
                int r = in.read(buf, read, contentLen - read);
                if (r < 0) break;
                read += r;
            }
            String body = new String(buf, 0, read);

            int status = 200;
            String contentType = "application/json; charset=utf-8";
            String response;

            try {
                if ("/health".equals(path)) {
                    response = "{\"ok\":true,\"url\":\"" + esc(engine != null ? engine.getUrl() : "") + "\"}";
                } else if ("/state".equals(path)) {
                    response = stateJson();
                } else if ("/navigate".equals(path) && "POST".equals(method)) {
                    String url = jsonStr(body, "url");
                    engine.loadUrl(url);
                    response = "{\"ok\":true,\"url\":\"" + esc(url) + "\"}";
                } else if ("/click".equals(path) && "POST".equals(method)) {
                    String sel = jsonStr(body, "selector");
                    engine.clickElement(sel);
                    response = "{\"ok\":true,\"clicked\":\"" + esc(sel) + "\"}";
                } else if ("/fill".equals(path) && "POST".equals(method)) {
                    engine.fillField(jsonStr(body, "selector"), jsonStr(body, "value"));
                    response = "{\"ok\":true}";
                } else if ("/extract".equals(path) && "POST".equals(method)) {
                    engine.extractText(jsonStr(body, "selector"));
                    response = "{\"ok\":true,\"note\":\"result in /events js_result\"}";
                } else if ("/js".equals(path) && "POST".equals(method)) {
                    engine.evaluateJs(jsonStr(body, "script"), null);
                    response = "{\"ok\":true}";
                } else if ("/scroll".equals(path) && "POST".equals(method)) {
                    String to = jsonStr(body, "to");
                    if ("end".equals(to)) engine.scrollToEnd();
                    else if ("top".equals(to)) engine.scrollToTop();
                    else engine.scrollBy(intOr(body, "dx", 0), intOr(body, "dy", 500));
                    response = "{\"ok\":true}";
                } else if ("/nav".equals(path) && "POST".equals(method)) {
                    String action = jsonStr(body, "action");
                    if ("back".equals(action)) engine.goBack();
                    else if ("forward".equals(action)) engine.goForward();
                    else if ("reload".equals(action)) engine.reload();
                    else if ("stop".equals(action)) engine.stopLoading();
                    else {
                        sendResponse(sock, 400, contentType,
                            "{\"error\":\"unknown action\"}".getBytes("UTF-8"));
                        return;
                    }
                    response = "{\"ok\":true,\"action\":\"" + action + "\"}";
                } else if ("/intercept".equals(path) && "POST".equals(method)) {
                    boolean block = body.contains("\"block\":true");
                    String pattern = jsonStr(body, "pattern");
                    engine.addInterceptRule(new BrowserEngine.InterceptRule(
                        Pattern.compile(pattern), null, "text/plain", block));
                    response = "{\"ok\":true,\"rules\":" + engine.interceptRuleCount() + "}";
                } else if ("/intercept".equals(path)) {
                    response = "{\"rules\":" + engine.interceptRuleCount() + "}";
                } else if ("/events".equals(path)) {
                    java.util.List<String> all = new ArrayList<>(engine.getEventLog());
                    all.addAll(getApiEvents());
                    response = listJson(all);
                } else if ("/requests".equals(path)) {
                    response = listJson(engine.getRequestLog());
                } else if ("/viewport".equals(path) && "POST".equals(method)) {
                    engine.setViewportSize(intOr(body, "w", 800), intOr(body, "h", 1200));
                    response = "{\"ok\":true}";
                } else if ("/screenshot".equals(path)) {
                    final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                    final Bitmap[] holder = new Bitmap[1];
                    engine.screenshot(new java.util.function.Consumer<Bitmap>() {
                        @Override public void accept(Bitmap bmp) {
                            holder[0] = bmp; latch.countDown();
                        }
                    });
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (holder[0] == null) {
                        sendResponse(sock, 500, contentType,
                            "{\"error\":\"screenshot failed\"}".getBytes("UTF-8"));
                        return;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    holder[0].compress(Bitmap.CompressFormat.PNG, 85, bos);
                    sendResponse(sock, 200, "image/png", bos.toByteArray());
                    return;
                } else if ("/clear".equals(path)) {
                    engine.clearLogs();
                    response = "{\"ok\":true}";
                } else {
                    status = 404;
                    response = "{\"error\":\"not found\",\"path\":\"" + esc(path) + "\"}";
                }
            } catch (Exception e) {
                status = 500;
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                response = "{\"error\":\"" + esc(msg) + "\"}";
            }

            sendResponse(sock, status, contentType, response.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w("AutomationServer", "conn: " + t);
        }
    }

    private static void sendResponse(Socket sock, int status, String ctype, byte[] body)
            throws IOException {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
            : status == 404 ? "Not Found" : "Server Error";
        OutputStream os = sock.getOutputStream();
        String hdr = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                     "Content-Type: " + ctype + "\r\n" +
                     "Content-Length: " + body.length + "\r\n" +
                     "Access-Control-Allow-Origin: *\r\n" +
                     "Connection: close\r\n\r\n";
        os.write(hdr.getBytes("UTF-8"));
        os.write(body);
        os.flush();
    }

    private static String stateJson() {
        return "{\"url\":\"" + esc(engine.getUrl()) + "\"" +
               ",\"title\":\"" + esc(engine.getTitle()) + "\"" +
               ",\"ready\":" + engine.isPageReady() +
               ",\"events\":" + engine.getEventLog().size() +
               ",\"requests\":" + engine.getRequestLog().size() + "}";
    }

    private static String listJson(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(items.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    /** Minimal JSON string-field extractor (no external deps). */
    private static String jsonStr(String json, String key) {
        if (json == null) return "";
        Matcher m = Pattern.compile(
            "\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (m.find()) return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        return "";
    }

    private static int intOr(String json, String key, int def) {
        if (json == null) return def;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
