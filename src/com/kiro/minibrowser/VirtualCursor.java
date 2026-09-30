package com.kiro.minibrowser;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

/**
 * VirtualCursor — visual feedback untuk automasi.
 *
 * Komponen:
 *  1. Kursor SVG (panah) yang bergerak halus ke target
 *  2. Highlight box (border) pada elemen yang sedang diklik/diisi
 *  3. Status bubble (tampilkan aksi terakhir: "CLICK #btn", "FILL input", dll)
 *  4. URL bar update otomatis saat navigasi
 *
 * Semua berjalan di main thread (Handler mainLooper).
 */
public class VirtualCursor {
    private final android.app.Activity activity;
    private final FrameLayout overlay;
    private final View cursorView;
    private final TextView statusBubble;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private float curX = 400f, curY = 600f;
    private static final int HIGHLIGHT_MS = 1500;

    private static void logPub(String msg) {
        try {
            java.io.File f = new java.io.File(
                "/storage/emulated/0/Download/minibrowser_cursor_log.txt");
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write(new java.util.Date() + " | " + msg + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }

    public VirtualCursor(android.app.Activity activity) {
        this.activity = activity;
        this.overlay = new FrameLayout(activity);
        // Overlay TIDAK intercept touch (hanya visual)
        overlay.setClickable(false);
        overlay.setFocusable(false);

        // Kursor: custom View yang menggambar panah
        this.cursorView = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private boolean pressed = false;
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                // Glow lingkaran emas transparan di belakang panah
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x55E8C87A);
                c.drawCircle(24, 26, pressed ? 26 : 22, paint);
                // Panah EMAS terang + outline hitam tebal (jelas terlihat)
                paint.setColor(0xFFE8C87A);
                paint.setShadowLayer(8f, 0f, 0f, 0xF0000000);
                Path p = new Path();
                c.save();
                c.translate(14, 4);
                if (pressed) c.scale(0.75f, 0.75f, 12f, 14f);
                p.moveTo(0, 0);
                p.lineTo(0, 34);
                p.lineTo(9, 27);
                p.lineTo(14, 38);
                p.lineTo(20, 35);
                p.lineTo(15, 24);
                p.lineTo(25, 24);
                p.close();
                c.drawPath(p, paint);
                paint.clearShadowLayer();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f);
                paint.setColor(0xFF07070C);
                c.drawPath(p, paint);
                c.restore();
            }
            public void setPressedVisual(boolean p) { pressed = p; invalidate(); }
        };
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(64, 64);
        overlay.addView(cursorView, cp);

        // Status bubble (tampilkan aksi terakhir)
        TextView bubble = new TextView(activity);
        bubble.setTextSize(13);
        bubble.setTextColor(0xFFE8C87A);
        bubble.setBackgroundColor(0xEE12131E);
        bubble.setPadding(20, 12, 20, 12);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        bp.topMargin = 8;
        bp.leftMargin = 12;
        bubble.setLayoutParams(bp);
        this.statusBubble = bubble;
        overlay.addView(bubble);

        // Overlay harus di atas WebView
        overlay.bringToFront();
        logPub("VirtualCursor created, overlay size pending attach");
        // Mulai di posisi tengah + VISIBLE sejak awal
        handler.post(new Runnable() { @Override public void run() {
            cursorView.setTranslationX(curX);
            cursorView.setTranslationY(curY);
            logPub("cursor initial position set: " + curX + "," + curY);
        }});
    }

    public View getOverlay() { return overlay; }

    /** Dipanggil setelah overlay di-attach ke parent — log ukuran nyata. */
    public void onAttached() {
        handler.post(new Runnable() { @Override public void run() {
            logPub("overlay ATTACHED: w=" + overlay.getWidth() + " h=" + overlay.getHeight()
                + " cursor w=" + cursorView.getWidth() + " h=" + cursorView.getHeight()
                + " visible=" + (cursorView.getVisibility() == View.VISIBLE)
                + " alpha=" + cursorView.getAlpha());
            // Buat kursor bergerak demo kecil (bukti hidup)
            demoMove();
        }});
    }

    /** Demo: kursor bergerak bolak-balik 3x (bukti visual). */
    public void demoMove() {
        handler.post(new Runnable() { @Override public void run() {
            logPub("demoMove START");
            animateTo(600, 800, new Runnable() { @Override public void run() {
                handler.postDelayed(new Runnable() { @Override public void run() {
                    animateTo(300, 500, new Runnable() { @Override public void run() {
                        logPub("demoMove DONE");
                    }});
                }}, 400);
            }});
        }});
    }

    /** Pindah kursor ke koordinat (animasi halus). */
    public void moveTo(float x, float y, final Runnable onDone) {
        handler.post(new Runnable() { @Override public void run() {
            animateTo(x, y, onDone);
        }});
    }

    private void animateTo(final float tx0, final float ty0, final Runnable onDone) {
        // Clamp ke dalam layar (elemen hidden punya rect 0 → posisi negatif)
        final float tx = Math.max(0f, Math.min(tx0, overlay.getWidth() > 0 ? overlay.getWidth() - 64 : tx0));
        final float ty = Math.max(0f, Math.min(ty0, overlay.getHeight() > 0 ? overlay.getHeight() - 64 : ty0));
        logPub("animateTo: " + tx + "," + ty + " (raw " + tx0 + "," + ty0 + ") from " + curX + "," + curY);
        final float startX = curX, startY = curY;
        final long duration = 350;
        final long startTime = android.os.SystemClock.uptimeMillis();
        Runnable step = new Runnable() { @Override public void run() {
            long now = android.os.SystemClock.uptimeMillis();
            float t = Math.min(1f, (now - startTime) / (float) duration);
            float eased = 1 - (1 - t) * (1 - t);   // ease-out
            curX = startX + (tx - startX) * eased;
            curY = curY + (ty - startY) * eased;
            cursorView.setTranslationX(curX);
            cursorView.setTranslationY(curY);
            if (t < 1) {
                handler.postDelayed(this, 16);
            } else {
                curX = tx; curY = ty;
                if (onDone != null) onDone.run();
            }
        }};
        handler.post(step);
    }

    /** Highlight elemen di posisi (x, y, w, h) selama 1.5 detik. */
    public void highlight(float x, float y, float w, float h) {
        handler.post(new Runnable() { @Override public void run() {
            final View box = new View(activity);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) w, (int) h);
            box.setLayoutParams(lp);
            box.setTranslationX(x);
            box.setTranslationY(y);
            box.setBackgroundResource(android.R.drawable.alert_light_frame);
            // Border ungu
            android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
            gd.setColor(0x44E8C87A);
            gd.setStroke(4, 0xFFE8C87A);
            gd.setCornerRadius(8);
            box.setBackgroundDrawable(gd);
            overlay.addView(box);
            // Pindah kursor ke tengah elemen
            animateTo(x + w / 2 - 24, y + h / 2 - 24, null);
            // Hapus highlight setelah 1.5s
            handler.postDelayed(new Runnable() { @Override public void run() {
                overlay.removeView(box);
            }}, HIGHLIGHT_MS);
        }});
    }

    /** Tampilkan status bubble (aksi terakhir). */
    public void showStatus(String text) {
        handler.post(new Runnable() { @Override public void run() {
            statusBubble.setText("🤖 " + text);
            statusBubble.setVisibility(View.VISIBLE);
            handler.removeCallbacksAndMessages(null);   // cancel hide sebelumnya
            handler.postDelayed(new Runnable() { @Override public void run() {
                statusBubble.setVisibility(View.GONE);
            }}, 2500);
        }});
    }

    /** Klik visual: kursor pindah + highlight + efek klik (pressed state). */
    public void clickAt(float x, float y, float w, float h, String label) {
        if (w <= 0 || h <= 0) {
            showStatus("👆 " + label + " (elemen tersembunyi)");
            moveTo(overlay.getWidth() / 2f - 32, overlay.getHeight() / 2f - 32, null);
            return;
        }
        moveTo(x + w / 2 - 32, y + h / 2 - 32, new Runnable() { @Override public void run() {
            highlight(x, y, w, h);
            showStatus("👆 " + label);
            // Efek klik: pressed visual (panah mengecil + glow membesar)
            handler.post(new Runnable() { @Override public void run() {
                cursorView.animate().scaleX(0.75f).scaleY(0.75f).setDuration(120)
                    .withEndAction(new Runnable() { @Override public void run() {
                        cursorView.animate().scaleX(1f).scaleY(1f).setDuration(120);
                    }});
            }});
        }});
    }

    public void fillAt(float x, float y, float w, float h, String label, String value) {
        if (w <= 0 || h <= 0) {
            // Elemen hidden (rect 0) — posisi kursor ke tengah layar + peringatan
            showStatus("✏️ " + label + " (elemen tersembunyi — fill tetap dieksekusi)");
            moveTo(overlay.getWidth() / 2f - 32, overlay.getHeight() / 2f - 32, null);
        } else {
            moveTo(x + w / 2 - 32, y + h / 2 - 32, new Runnable() { @Override public void run() {
                highlight(x, y, w, h);
                showStatus("✏️ " + label + " = " + value);
            }});
        }
    }

    public void scrollIndicator(int dy) {
        handler.post(new Runnable() { @Override public void run() {
            showStatus((dy > 0 ? "⬇️" : "⬆️") + " scroll " + Math.abs(dy) + "px");
        }});
    }

    public void navigateIndicator(String url) {
        handler.post(new Runnable() { @Override public void run() {
            showStatus("🌐 " + url);
        }});
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        if (overlay != null && overlay.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }
}
