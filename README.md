# MiniBrowser APK — Programmable Android WebView for Automation

Custom Android Mini-Browser APK designed for **automated software testing** and
**authorized data extraction**. Built on standard `WebView` with a fully
programmable scripting interface exposed over a **local HTTP API**.

**APK:** `~/minibrowser.apk` (25 KB, signed v2+v3, minSdk 24 / target 34)

---

## 1. Install & Run

```bash
# via adb (from Termux or desktop)
adb install -r ~/minibrowser.apk
adb shell am start -n com.kiro.minibrowser/.MainActivity

# Expose the local automation API to adb-side controllers
adb forward tcp:18080 tcp:8080
curl http://127.0.0.1:18080/health
# → {"ok":true,"url":"about:blank"}
```

Or install the APK manually on the device (Settings → Install unknown apps).

---

## 2. Architecture

```
┌─────────────────────────────────────────────────┐
│  External controller (curl / Python / ADB / CI) │
└──────────────────┬──────────────────────────────┘
                   │ HTTP 127.0.0.1:8080
┌──────────────────▼──────────────────────────────┐
│  AutomationServer  (raw ServerSocket, 4 threads)│
│  • /navigate /click /fill /extract /js          │
│  • /scroll /nav /intercept /viewport            │
│  • /events /requests /screenshot /state         │
└──────────────────┬──────────────────────────────┘
                   │ (in-process calls)
┌──────────────────▼──────────────────────────────┐
│  BrowserEngine (programmable WebView wrapper)   │
│  • JS injection · DOM interaction               │
│  • Network interception (shouldInterceptRequest)│
│  • Navigation + scroll + viewport control       │
│  • Event / request log (capped at 500)          │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│  WebView (Chromium) — renders page              │
│  window.Android (JsBridge) exposed to page JS   │
└─────────────────────────────────────────────────┘

AutomationService (foreground) keeps process alive
during long sessions (START_STICKY, notification).
```

**Files** (`src/com/kiro/minibrowser/`):

| File | Responsibility |
|---|---|
| `BrowserEngine.java` | WebView wrapper: settings, JS injection, network interception, nav/scroll control, event log |
| `JsBridge.java` | JS helpers exposed to page as `window.Android` (click/fill/extract/wait) |
| `AutomationServer.java` | Local HTTP API (ServerSocket, loopback-only, 4-thread pool) |
| `MainActivity.java` | Hosts engine + server; wires UI; handles deep-link Intents |
| `AutomationService.java` | Foreground service keeping process alive during long sessions |
| `BrowserEngine.kt` | Kotlin reference design (not compiled here — no kotlinc in Termux) |

---

## 3. Automation API

Base URL: `http://127.0.0.1:8080` (loopback only — never exposed to network).

### Endpoints

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/health` | — | `{"ok":true,"url":"..."}` |
| GET | `/state` | — | `{"url","title","ready","events","requests"}` |
| POST | `/navigate` | `{"url":"https://example.com"}` | `{"ok":true,"url":"..."}` |
| POST | `/click` | `{"selector":"#submit"}` | `{"ok":true,"clicked":"BUTTON"}` |
| POST | `/fill` | `{"selector":"#email","value":"a@b.c"}` | `{"ok":true}` |
| POST | `/extract` | `{"selector":"div.item"}` | `{"ok":true,"note":"result in /events js_result"}` |
| POST | `/js` | `{"script":"document.title"}` | `{"ok":true}` (result → `/events`) |
| POST | `/scroll` | `{"dx":0,"dy":500}` or `{"to":"end"}`/`{"to":"top"}` | `{"ok":true}` |
| POST | `/nav` | `{"action":"back"\|"forward"\|"reload"\|"stop"}` | `{"ok":true,"action":"..."}` |
| POST | `/intercept` | `{"pattern":"\\.css$","block":true}` | `{"ok":true,"rules":N}` |
| GET | `/intercept` | — | `{"rules":N}` |
| GET | `/events` | — | `["PAGE_START ...", "PAGE_DONE ...", "JS_RESULT ..."]` |
| GET | `/requests` | — | `["GET https://..."]` |
| POST | `/viewport` | `{"w":400,"h":800}` | `{"ok":true}` |
| GET | `/screenshot` | — | `image/png` bytes |
| POST | `/clear` | — | `{"ok":true}` (clear logs) |

### Example automation session (curl)

```bash
# 1. Navigate
curl -X POST http://127.0.0.1:18080/navigate -d '{"url":"https://news.ycombinator.com"}'

# 2. Wait for page ready
until curl -s http://127.0.0.1:18080/state | grep -q '"ready":true'; do sleep 0.3; done

# 3. Extract all story titles
curl -X POST http://127.0.0.1:18080/extract -d '{"selector":".titleline > a"}'

# 4. Read results from event log
curl -s http://127.0.0.1:18080/events | tail -5

# 5. Fill a form field & click submit
curl -X POST http://127.0.0.1:18080/fill -d '{"selector":"input[name=q]","value":"termux"}'
curl -X POST http://127.0.0.1:18080/click -d '{"selector":"input[type=submit]"}'

# 6. Scroll to bottom
curl -X POST http://127.0.0.1:18080/scroll -d '{"to":"end"}'

# 7. Screenshot
curl -o shot.png http://127.0.0.1:18080/screenshot

# 8. Intercept & block all images (speed up scraping)
curl -X POST http://127.0.0.1:18080/intercept -d '{"pattern":"\\.(png\|jpg\|gif)$","block":true}'
```

### Python controller example

```python
import requests, time

BASE = "http://127.0.0.1:18080"

def nav(url, timeout=15):
    requests.post(f"{BASE}/navigate", json={"url": url})
    for _ in range(timeout * 3):
        if requests.get(f"{BASE}/state").json().get("ready"):
            return True
        time.sleep(0.33)
    return False

def click(sel):  return requests.post(f"{BASE}/click",   json={"selector": sel}).json()
def fill(sel, v):return requests.post(f"{BASE}/fill",    json={"selector": sel, "value": v}).json()
def extract(sel):return requests.post(f"{BASE}/extract", json={"selector": sel}).json()
def js(code):    return requests.post(f"{BASE}/js",      json={"script": code}).json()
def events():    return requests.get(f"{BASE}/events").json()
def shot(path):
    open(path, "wb").write(requests.get(f"{BASE}/screenshot").content)

# Full flow
nav("https://example.com")
click("a[href='/domains']")
time.sleep(1)
print(events()[-3:])
shot("page.png")
```

---

## 4. Network Interception

`BrowserEngine.shouldInterceptRequest` is wired to a user-managed rule list.
Each rule has a `Pattern` (regex on URL) + an action:

- **`blockRequest=true`** → return an empty 200 response (request never hits network)
- **`responseBody != null`** → return the supplied bytes as the response body
- **otherwise** → pass through normally, but URL is logged to `/requests`

```bash
# Block all CSS + images
curl -X POST http://127.0.0.1:18080/intercept \
  -d '{"pattern":"\\.(css|png|jpg|gif|woff2?)$","block":true}'

# Replace a specific API response with a mock
curl -X POST http://127.0.0.1:18080/intercept \
  -d '{"pattern":"api\\.example\\.com/user","body":"{\"id\":1,\"name\":\"mock\"}"}'
```

**Monitoring:** every request (including subresources) is logged to `/requests`:

```bash
curl http://127.0.0.1:18080/requests
# ["GET https://example.com/", "GET https://example.com/style.css", ...]
```

---

## 5. Extending the API

To add a custom automation step:

1. **Add a branch** in `AutomationServer.handleConnection()`:
   ```java
   } else if ("/myaction".equals(path) && "POST".equals(method)) {
       String arg = jsonStr(body, "arg");
       // ... call engine methods or inject JS ...
       response = "{\"ok\":true}";
   }
   ```
2. **Add a JS helper** in `JsBridge` if it needs reusable DOM logic:
   ```java
   public static String jsMyAction(String selector) {
       return "(function(){ ... })()";
   }
   ```
3. **Expose it** in `BrowserEngine` as a typed method:
   ```java
   public void myAction(String selector) { evaluateJs(JsBridge.jsMyAction(selector), null); }
   ```
4. Rebuild: `bash build.sh` (see below).

---

## 6. Build from Source (Termux)

```bash
cd ~/minibrowser
bash build.sh        # aapt2 → javac → d8 → zip → zipalign → apksigner
# Output: build/minibrowser.apk + ~/minibrowser.apk
```

Toolchain (all available in Termux):
`aapt2` · `javac` (JDK 21) · `d8` · `zipalign` · `apksigner` · `keytool`

---

## 7. Memory & Stability Notes

- **WebView destroyed properly** in `onDestroy()` — prevents the classic
  WebView memory leak on long sessions.
- **Bounded event/request logs** (max 500 entries each) — no unbounded growth.
- **Foreground service** keeps the process alive (Android will not kill it
  mid-automation). Notification is low-importance (silent).
- **`START_STICKY`** — service restarts if the OS kills the process.
- **Loopback-only server** — no network exposure; `adb forward` is the
  recommended bridge to external controllers.
- **`settings.setMediaPlaybackRequiresUserGesture(false)`** — allows
  automated pages to autoplay (needed for some test scenarios).
- **Remote debugging enabled** (`WebView.setWebContentsDebuggingEnabled(true)`)
  — inspect via `chrome://inspect` on a connected desktop during development.

---

## 8. Security & Authorization

This tool is designed for **authorized** testing and data extraction only:
- The HTTP API binds to `127.0.0.1` — no external access.
- The `window.Android` JsBridge is only exposed to pages loaded in this app.
- Network interception rules are app-local and do not affect other apps.
- Always respect `robots.txt`, terms of service, and applicable law.

---

## 9. Known Limitations (Termux build)

- `com.sun.net.httpserver` is not in the Android SDK → replaced with a raw
  `ServerSocket` HTTP/1.1 handler (equivalent functionality, no external deps).
- Lambdas are desugared via anonymous inner classes (d8 works, but
  `LambdaMetafactory` is not in the android.jar stub) — identical behavior.
- Kotlin source (`BrowserEngine.kt`) is included as reference design but
  compiled via the equivalent Java file (Termux lacks `kotlinc`).
- `POST` body parsing uses a minimal regex-based JSON extractor (no Jackson/Gson).
- Screenshot requires the WebView to be attached & rendered (not background-only).