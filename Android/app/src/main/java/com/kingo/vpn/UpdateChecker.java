package com.kingo.vpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    private static final String PREFS_NAME = "kingo_update_check";
    private static final String KEY_LAST_CHECK_TIME = "last_update_check_time";
    private static final String KEY_LAST_CHECKED_VERSION = "last_checked_version";
    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/kingowow/Kingo-vpn/refs/heads/main/Notification/Update.json";

    // Cache for 6 hours
    private static final long CHECK_CACHE_MS = 6 * 60 * 60 * 1000;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Activity activityRef = null;

    public UpdateChecker(Activity activity) {
        this.context = activity.getApplicationContext();
        this.activityRef = activity;
    }

    /**
     * Check for updates. Will show dialog if a newer version is available.
     * Respects a cache — won't check more than once per CHECK_CACHE_MS.
     */
    public void checkForUpdate() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0);
        long now = System.currentTimeMillis();

        // If we checked recently, skip
        if (now - lastCheck < CHECK_CACHE_MS) {
            return;
        }

        String currentVersion = getCurrentVersion();
        if (currentVersion == null) return;

        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_URL);
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

                    JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();

                    String remoteVersion = json.has("version") ? json.get("version").getAsString() : "";
                    String title = json.has("title") ? json.get("title").getAsString() : "Update Available";
                    String message = json.has("message") ? json.get("message").getAsString() : "";
                    String downloadUrl = json.has("download_url") ? json.get("download_url").getAsString() : "";

                    // Update cache time
                    prefs.edit()
                            .putLong(KEY_LAST_CHECK_TIME, now)
                            .putString(KEY_LAST_CHECKED_VERSION, remoteVersion)
                            .apply();

                    // Compare versions
                    if (isNewerVersion(remoteVersion, currentVersion)) {
                        mainHandler.post(() -> {
                            Activity act = activityRef;
                            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                                showUpdateDialog(act, title, message, downloadUrl);
                            }
                        });
                    }
                } else {
                    conn.disconnect();
                }
            } catch (Exception e) {
                // Network error — will retry next time
            }
        }).start();
    }

    private String getCurrentVersion() {
        try {
            PackageInfo pInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Simple version comparison: "1.1" > "1.0", "2.0" > "1.9", etc.
     */
    private boolean isNewerVersion(String remote, String current) {
        if (remote == null || remote.isEmpty()) return false;
        if (current == null || current.isEmpty()) return false;

        try {
            String[] remoteParts = remote.split("\\.");
            String[] currentParts = current.split("\\.");

            int maxLen = Math.max(remoteParts.length, currentParts.length);

            for (int i = 0; i < maxLen; i++) {
                int r = i < remoteParts.length ? Integer.parseInt(remoteParts[i].trim()) : 0;
                int c = i < currentParts.length ? Integer.parseInt(currentParts[i].trim()) : 0;

                if (r > c) return true;
                if (r < c) return false;
            }
        } catch (NumberFormatException e) {
            // If parsing fails, do string comparison
            return remote.compareTo(current) > 0;
        }

        return false;
    }

    private void showUpdateDialog(Activity activity, String title, String message, String downloadUrl) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(true);

            if (downloadUrl != null && !downloadUrl.isEmpty()) {
                builder.setPositiveButton("Update", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                    } catch (Exception ignore) {}
                });
            }

            builder.setNegativeButton("Later", null);
            builder.show();
        } catch (Exception ignore) {}
    }
}
