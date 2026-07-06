package com.kingo.vpn;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NotificationChecker {

    private static final String PREFS_NAME = "kingo_notifications";
    private static final String KEY_LAST_HASH = "last_notification_hash";
    private static final String CHANNEL_ID = "kingo_notifications_channel";
    private static final String CHANNEL_NAME = "Kingo VPN Notifications";
    private static final int NOTIFICATION_REQUEST_CODE = 7777;
    private static final String NOTIFICATION_URL =
            "https://raw.githubusercontent.com/kingowow/Kingo-vpn/refs/heads/main/Notification/Notification.json";

    // Check every 4 hours
    private static final long CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = this::checkNotifications;

    private static NotificationChecker instance;

    public static synchronized NotificationChecker getInstance(Context context) {
        if (instance == null) {
            instance = new NotificationChecker(context.getApplicationContext());
        }
        return instance;
    }

    private NotificationChecker(Context context) {
        this.context = context;
    }

    public void startPeriodicCheck() {
        handler.removeCallbacks(checkRunnable);
        // Check immediately on start, then checkNotifications() handles periodic scheduling
        handler.post(checkRunnable);
    }

    public void stopPeriodicCheck() {
        handler.removeCallbacks(checkRunnable);
    }

    public void checkNotifications() {
        new Thread(() -> {
            try {
                URL url = new URL(NOTIFICATION_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    String response = sb.toString();
                    int currentHash = response.hashCode();

                    SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    int lastHash = prefs.getInt(KEY_LAST_HASH, -1);

                    if (currentHash != lastHash) {
                        // New or changed notifications
                        prefs.edit().putInt(KEY_LAST_HASH, currentHash).apply();
                        parseAndShowNotifications(response);
                    }
                } else {
                    conn.disconnect();
                }
            } catch (Exception e) {
                // Network error — ignore, will retry next cycle
            }

            // Schedule next check
            handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS);
        }).start();
    }

    private void parseAndShowNotifications(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray notifications = root.getAsJsonArray("notifications");

            if (notifications == null || notifications.size() == 0) return;

            createNotificationChannel();

            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            for (int i = 0; i < notifications.size(); i++) {
                JsonObject notif = notifications.get(i).getAsJsonObject();
                String title = notif.has("title") ? notif.get("title").getAsString() : "Kingo VPN";
                String message = notif.has("message") ? notif.get("message").getAsString() : "";
                String link = notif.has("link") ? notif.get("link").getAsString() : "";

                // Create PendingIntent: if link is not empty, open it; otherwise open app
                Intent intent;
                if (link != null && !link.isEmpty()) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
                } else {
                    intent = new Intent(context, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                }

                PendingIntent pendingIntent = PendingIntent.getActivity(
                        context,
                        NOTIFICATION_REQUEST_CODE + i,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT);

                nm.notify(NOTIFICATION_REQUEST_CODE + i, builder.build());
            }
        } catch (Exception ignore) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notifications from Kingo VPN");
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }
}
