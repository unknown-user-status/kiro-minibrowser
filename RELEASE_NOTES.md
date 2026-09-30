# MiniBrowser APK — Release Notes

## v8 (current) — 0ac4ebb2 — CRASH FIX ANDROID 14
- **Fix crash "forced close"**: tambah permission FOREGROUND_SERVICE_DATA_SYNC (wajib API 34)
- **Foreground service bekerja**: app tetap hidup di background (60+ detik terverifikasi)
- Virtual cursor (panah putih, animasi 350ms ease-out)
- Highlight box ungu pada elemen target
- Status bubble (🌐 navigate / 👆 click / ✏️ fill / ⬇️ scroll / ❌ error)
- URL bar update otomatis saat navigasi
- js_result masuk event log (fix notifyEvent)
- Port 8888 (hindari blokir 8080)
- Bind 0.0.0.0 → akses via IP LAN

## Hasil Test v8 (30 Sep 2026)
| Test | Hasil |
|---|---|
| Health check | ✅ 200 |
| Navigate example.com | ✅ title "Example Domain" |
| Click link → iana.org | ✅ navigate sukses |
| Fill wikipedia search | ✅ "FILLED" |
| Extract h2 | ✅ 5 section terdeteksi |
| Foreground 60s background | ✅ server tetap hidup |
| Intercept images | ✅ 6 image diblokir |

## Install
Download `minibrowser.apk` → install → buka app → Allow notification permission

## Test
```bash
IP=$(termux-wifi-connectioninfo | python3 -c 'import json,sys; print(json.load(sys.stdin)["ip"])')
curl http://$IP:8888/health
```
