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
    private float curX = 200f, curY = 300f;
    private static final int HIGHLIGHT_MS = 1500;

    public VirtualCursor(android.app.Activity activity) {
        this.activity = activity;
        this.overlay = new FrameLayout(activity);
        // Overlay TIDAK intercept touch (hanya visual)
        overlay.setClickable(false);
        overlay.setFocusable(false);

        // Kursor: custom View yang menggambar panah
        this.cursorView = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                // Panah kursor (pointer) putih dengan outline hitam
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL);
                paint.setShadowLayer(6f, 0f, 0f, Color.BLACK);
                Path p = new Path();
                c.save();
                c.translate(20, 4);
                p.moveTo(0, 0);
                p.lineTo(0, 26);
                p.lineTo(7, 20);
                p.lineTo(11, 28);
                p.lineTo(15, 26);
                p.lineTo(11, 18);
                p.lineTo(19, 18);
                p.close();
                c.drawPath(p, paint);
                paint.clearShadowLayer();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f);
                paint.setColor(Color.BLACK);
                c.drawPath(p, paint);
            }
        };
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(48, 48);
        overlay.addView(cursorView, cp);

        // Status bubble (tampilkan aksi terakhir)
        TextView bubble = new TextView(activity);
        bubble.setTextSize(11);
        bubble.setTextColor(Color.WHITE);
        bubble.setBackgroundColor(0xCC1B1D2C);
        bubble.setPadding(16, 8, 16, 8);
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
    }

    public View getOverlay() { return overlay; }

    /** Pindah kursor ke koordinat (animasi halus). */
    public void moveTo(float x, float y, final Runnable onDone) {
        handler.post(new Runnable() { @Override public void run() {
            animateTo(x, y, onDone);
        }});
    }

    private void animateTo(final float tx, final float ty, final Runnable onDone) {
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
            gd.setColor(0x338B7CF6);
            gd.setStroke(3, 0xFF8B7CF6);
            gd.setCornerRadius(6);
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

    /** Klik visual: kursor pindah + highlight + efek klik. */
    public void clickAt(float x, float y, float w, float h, String label) {
        moveTo(x + w / 2 - 24, y + h / 2 - 24, new Runnable() { @Override public void run() {
            highlight(x, y, w, h);
            showStatus("👆 " + label);
            // Efek klik: scale kursor
            handler.post(new Runnable() { @Override public void run() {
                cursorView.animate().scaleX(0.7f).scaleY(0.7f).setDuration(100)
                    .withEndAction(new Runnable() { @Override public void run() {
                        cursorView.animate().scaleX(1f).scaleY(1f).setDuration(100);
                    }});
            }});
        }});
    }

    public void fillAt(float x, float y, float w, float h, String label, String value) {
        moveTo(x + w / 2 - 24, y + h / 2 - 24, new Runnable() { @Override public void run() {
            highlight(x, y, w, h);
            showStatus("✏️ " + label + " = " + value);
        }});
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
