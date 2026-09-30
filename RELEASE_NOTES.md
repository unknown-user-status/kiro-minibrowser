# MiniBrowser APK — Release Notes (FINAL)

## v12 (final) — bafaa082
- **UA switch**: `/ua` endpoint (desktop/mobile/custom) — kunci untuk situs yang
  redirect mobile (YouTube trending, dll)
- Virtual cursor emas 64px + glow + highlight + status bubble
- URL bar update otomatis
- Foreground service (app tidak di-freeze MIUI saat background)
- js_result masuk event log
- Port 8888, bind 0.0.0.0
- 17 endpoint API otomasi

## Riwayat versi
| v | MD5 | Fitur utama |
|---|---|---|
| v1 | 8884fc3a | awal (bind 127.0.0.1) |
| v3 | 1b3ad382 | bind 0.0.0.0 |
| v4 | fe776858 | fix /events |
| v5 | a20cb3cd | virtual cursor |
| v6 | e78f356a | foreground service |
| v7 | ae1e5606 | port 8888 |
| v8 | 0ac4ebb2 | crash fix Android 14 (FGS permission) |
| v9 | b96f839e | cursor visible (FrameLayout fix) |
| v10 | 10310abe | cursor debug + auto-demo |
| v11 | 976b2c47 | cursor clamp |
| **v12** | **bafaa082** | **UA switch — FINAL** |

## 12 pelajaran teknis (Termux + Android 14 + MIUI)
1. Android 14 network namespace — loopback antar app terpisah → bind 0.0.0.0
2. API 34 wajib permission per foregroundServiceType (dataSync → FOREGROUND_SERVICE_DATA_SYNC)
3. POST_NOTIFICATIONS = runtime permission (API 33+)
4. Overlay kursor HARUS di FrameLayout (di LinearLayout = tinggi 0 → tak terlihat)
5. MIUI blokir background activity start — app hanya bisa dibuka manual dari launcher
6. MIUI freeze network app background — server hanya hidup saat app foreground
7. IP HP dinamis (DHCP) — selalu cek termux-wifi-connectioninfo
8. logcat blocked → debug via log ke file publik (Download/)
9. getBoundingClientRect = cara dapat posisi elemen untuk kursor visual
10. Elemen display:none → rect 0×0 → clamp posisi kursor
11. YouTube trending: mobile web butuh login; Piped API = solusi (api.piped.private.coffee)
12. evaluateJavascript tidak wait promise → simpan ke window var + polling

## Install
Download `minibrowser.apk` → install → buka app → Allow notification

## Test
```bash
bash ~/kiro_tools/demo_cursor.sh       # demo 6 langkah
bash ~/kiro_tools/test_minibrowser.sh  # test 9 endpoint
```
