package com.kiro.minibrowser;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * AutomationService — keeps the process alive during long automation sessions
 * so Android does not kill the WebView mid-run. Foreground service with
 * persistent notification (required on API 26+).
 *
 * Started by MainActivity when "keep alive" is toggled, or via
 *   adb shell am start-foreground-service -n com.kiro.minibrowser/.AutomationService
 */
public class AutomationService extends android.app.Service {
    private static final String CHANNEL_ID = "minibrowser_automation";
    private static final int NOTIF_ID = 1;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
            "MiniBrowser Automation", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps browser alive for automation");
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MiniBrowser")
            .setContentText("Automation session active — http://127.0.0.1:8080")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build();
        startForeground(NOTIF_ID, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;   // restart if killed
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() { super.onDestroy(); stopForeground(STOP_FOREGROUND_REMOVE); }
}
