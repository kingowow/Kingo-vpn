package com.kingo.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Fetches server configurations from a remote URL.
 * The URL contains one server URI per line (vless://, vmess://, trojan://).
 * Supports two storage modes:
 * - Cache ("servers" group): used for auto-connect and scan testing. Not shown in the list.
 * - Saved: user-saved servers shown in the list.
 */
public class ServerFetcher {

    private static final String PREFS_NAME = "kingo_vpn_servers";
    private static final String KEY_SAVED_SERVERS = "saved_servers";
    private static final String KEY_CACHED_SERVERS = "cached_servers";
    private static final String KEY_LAST_FETCH_TIME = "last_fetch_time";
    private static final String SERVER_URL = "https://raw.githubusercontent.com/kingowow/Kingo-vpn/refs/heads/main/server/KingoVpn.txt";
    private static final long FETCH_INTERVAL_MS = 5 * 60 * 60 * 1000L; // 5 hours

    public interface FetchCallback {
        void onFetchComplete(int serverCount);
        void onFetchError(String error);
    }

    /**
     * Check if it's time to re-fetch servers (5 hours since last fetch).
     */
    public static boolean shouldFetch(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastFetch = prefs.getLong(KEY_LAST_FETCH_TIME, 0);
        return (System.currentTimeMillis() - lastFetch) > FETCH_INTERVAL_MS;
    }

    /**
     * Get cached servers (for auto-connect and scan testing).
     */
    public static ArrayList<ServerModel> getCachedServers(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CACHED_SERVERS, null);
        if (json != null && !json.isEmpty()) {
            try {
                Type listType = new TypeToken<ArrayList<ServerModel>>() {}.getType();
                return new Gson().fromJson(json, listType);
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    /**
     * Save servers to the saved list (shown in the UI).
     */
    public static void saveServersToList(Context context, ArrayList<ServerModel> servers) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existingJson = prefs.getString(KEY_SAVED_SERVERS, null);
        ArrayList<ServerModel> allServers = new ArrayList<>();

        if (existingJson != null && !existingJson.isEmpty()) {
            try {
                Type listType = new TypeToken<ArrayList<ServerModel>>() {}.getType();
                allServers = new Gson().fromJson(existingJson, listType);
            } catch (Exception e) {
                allServers = new ArrayList<>();
            }
        }

        // Add new servers (avoid duplicates by config)
        for (ServerModel newServer : servers) {
            boolean exists = false;
            for (ServerModel existing : allServers) {
                if (existing.getConfig() != null && existing.getConfig().equals(newServer.getConfig())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                allServers.add(newServer);
            }
        }

        String json = new Gson().toJson(allServers);
        prefs.edit().putString(KEY_SAVED_SERVERS, json).apply();
    }

    /**
     * Fetch servers from the remote URL on a background thread.
     * Stores fetched servers in cache (for auto-connect/scan testing).
     * Does NOT add them to the displayed server list.
     */
    public static void fetchServers(Context context, FetchCallback callback) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.connect();

                if (conn.getResponseCode() != 200) {
                    if (callback != null) {
                        callback.onFetchError("HTTP " + conn.getResponseCode());
                    }
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        sb.append(line).append("\n");
                    }
                }
                reader.close();
                conn.disconnect();

                String content = sb.toString().trim();
                String[] lines = content.split("\n");

                // Parse each line as a server URI
                ArrayList<ServerModel> fetchedServers = new ArrayList<>();
                for (String lineStr : lines) {
                    lineStr = lineStr.trim();
                    if (lineStr.startsWith("vless://") || lineStr.startsWith("vmess://") || lineStr.startsWith("trojan://")) {
                        ServerModel server = ServerModel.fromConfig(lineStr, "servers");
                        fetchedServers.add(server);
                    }
                }

                // Shuffle to randomize order
                Collections.shuffle(fetchedServers);

                // Save to cache (separate from displayed servers)
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String json = new Gson().toJson(fetchedServers);
                prefs.edit()
                        .putString(KEY_CACHED_SERVERS, json)
                        .putLong(KEY_LAST_FETCH_TIME, System.currentTimeMillis())
                        .apply();

                if (callback != null) {
                    callback.onFetchComplete(fetchedServers.size());
                }

            } catch (Exception e) {
                if (callback != null) {
                    callback.onFetchError(e.getMessage());
                }
            }
        }).start();
    }
}
