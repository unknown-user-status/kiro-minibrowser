# MiniBrowser APK — Release Notes

## v6 (current) — e78f356a
- Virtual cursor (panah putih, animasi 350ms ease-out)
- Highlight box ungu pada elemen target
- Status bubble (🌐 navigate / 👆 click / ✏️ fill / ⬇️ scroll)
- URL bar update otomatis saat navigasi
- **Foreground service** — app tidak di-freeze MIUI saat background
- Fix /events (js_result masuk event log)
- Bind 0.0.0.0 → akses via IP LAN

## Install
Download `minibrowser.apk` → install (izinkan unknown source)

## Test
```bash
am start -n com.kiro.minibrowser/.MainActivity
sleep 15
# IP LAN HP, contoh:
curl http://10.169.222.85:8080/health
```
