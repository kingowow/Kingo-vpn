package com.kingo.vpn;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;

import dev.dev7.lib.v2ray.V2rayController;
import dev.dev7.lib.v2ray.utils.V2rayConstants;

public class MainActivity extends AppCompatActivity {

    // Views
    private TextView tvStatus, tvTimer, tvConnectHint, tvSelectedServer, tvUpload, tvDownload;
    private LinearLayout btnPower, layoutTraffic;
    private ProgressBar progressRing, progressRingIndeterminate;
    private RecyclerView rvServers;
    private TextView tabFavorites, tabServers, tabCustom;
    private ImageView ivMenu, ivSettings;

    // Ping display (connected state)
    private LinearLayout layoutPing;
    private TextView tvPingValue;
    private ImageView ivRefreshPing;

    // IP display (connected state)
    private LinearLayout layoutIP;
    private TextView tvIP;

    // Get Active Servers button
    private TextView btnGetActiveServers;

    // Auto connect progress
    private LinearLayout layoutAutoProgress;
    private TextView tvAutoStatus, tvAutoProgress;
    private ProgressBar progressAutoScan;

    // Adapter
    private ServerAdapter serverAdapter;

    // Server data
    private ArrayList<ServerModel> allServers;
    private ArrayList<ServerModel> filteredServers;
    private int selectedServerIndex = -1;
    private int currentTab = 1;
    private boolean autoConnectSelected = false;

    // Timer
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long connectionStartTime;
    private boolean isTimerRunning = false;
    private V2rayConstants.CONNECTION_STATES currentState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
    private boolean receivedBroadcastDuration = false;
    private String localTimerValue = "00:00:00";

    // Connection timeout
    private final Handler connectionTimeoutHandler = new Handler(Looper.getMainLooper());
    private static final long CONNECTION_TIMEOUT_MS = 5000;
    private final Runnable connectionTimeoutRunnable = () -> {
        cancelConnecting();
        Toast.makeText(this, R.string.connection_timeout, Toast.LENGTH_SHORT).show();
    };

    // Auto connect state
    private boolean isAutoConnecting = false;

    // Test all ping state
    private volatile boolean isTestingAllPing = false;
    private final ArrayList<Thread> testPingThreads = new ArrayList<>();

    // IP fetching
    private final Handler ipHandler = new Handler(Looper.getMainLooper());
    private Thread ipFetchThread = null;
    private static final int IP_MAX_RETRIES = 3;
    private static final long IP_FETCH_TIMEOUT_MS = 15000;
    private boolean pingFetchedThisSession = false;
    private boolean ipFetchedThisSession = false;

    // Drag and drop
    private ItemTouchHelper itemTouchHelper;

    // Preferences
    private static final String PREFS_NAME = "kingo_vpn_servers";
    private static final String KEY_SAVED_SERVERS = "saved_servers";
    private static final String KEY_SELECTED_INDEX = "selected_index";
    private static final String KEY_CURRENT_TAB = "current_tab";

    // Timer runnable — only updates when timer is actually running
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTimerRunning) {
                long elapsed = System.currentTimeMillis() - connectionStartTime;
                int totalSeconds = (int) (elapsed / 1000);
                int hours = totalSeconds / 3600;
                int minutes = (totalSeconds % 3600) / 60;
                int seconds = totalSeconds % 60;
                localTimerValue = String.format("%02d:%02d:%02d", hours, minutes, seconds);
                if (!receivedBroadcastDuration) {
                    tvTimer.setText(localTimerValue);
                }
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                V2rayConstants.CONNECTION_STATES newState =
                        (V2rayConstants.CONNECTION_STATES) intent.getSerializableExtra(
                                "CONNECTION_STATE_EXTRA");
                if (newState != null) {
                    currentState = newState;
                    updateUI();

                    if (newState == V2rayConstants.CONNECTION_STATES.CONNECTED) {
                        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
                        // updateUI() handles startTimer, fetchIPInfo, refreshPing
                    } else if (newState == V2rayConstants.CONNECTION_STATES.DISCONNECTED) {
                        // updateUI() handles stopTimer
                    }
                }

                String duration = intent.getStringExtra("SERVICE_DURATION_EXTRA");
                if (duration != null && isTimerRunning) {
                    receivedBroadcastDuration = true;
                    tvTimer.setText(duration);
                }

                String uploadSpeed = intent.getStringExtra("UPLOAD_SPEED_EXTRA");
                String downloadSpeed = intent.getStringExtra("DOWNLOAD_SPEED_EXTRA");
                if (uploadSpeed != null) tvUpload.setText("↑ " + uploadSpeed);
                if (downloadSpeed != null) tvDownload.setText("↓ " + downloadSpeed);

            } catch (Exception ignore) {}
        }
    };    // Notification & Update
    private NotificationChecker notificationChecker;
    private UpdateChecker updateChecker;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        initV2ray();
        loadServers();
        setupTabs();
        setupMenu();
        setupSettings();
        setupListeners();
        setupDragAndDrop();
        registerStateReceiver();
        updateUI();

        // Notification check (periodic)
        notificationChecker = NotificationChecker.getInstance(this);
        notificationChecker.startPeriodicCheck();

        // Update check (on app launch)
        updateChecker = new UpdateChecker(this);
        updateChecker.checkForUpdate();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvTimer = findViewById(R.id.tvTimer);
        tvConnectHint = findViewById(R.id.tvConnectHint);
        tvSelectedServer = findViewById(R.id.tvSelectedServer);
        tvUpload = findViewById(R.id.tvUpload);
        tvDownload = findViewById(R.id.tvDownload);
        btnPower = findViewById(R.id.btnPower);
        layoutTraffic = findViewById(R.id.layoutTraffic);
        progressRing = findViewById(R.id.progressRing);
        progressRingIndeterminate = findViewById(R.id.progressRingIndeterminate);
        rvServers = findViewById(R.id.rvServers);
        tabFavorites = findViewById(R.id.tabFavorites);
        tabServers = findViewById(R.id.tabServers);
        tabCustom = findViewById(R.id.tabCustom);
        ivMenu = findViewById(R.id.ivMenu);
        ivSettings = findViewById(R.id.ivSettings);

        // Ping display
        layoutPing = findViewById(R.id.layoutPing);
        tvPingValue = findViewById(R.id.tvPingValue);
        ivRefreshPing = findViewById(R.id.ivRefreshPing);

        // IP display
        layoutIP = findViewById(R.id.layoutIP);
        tvIP = findViewById(R.id.tvIP);

        // Get Active Servers button
        btnGetActiveServers = findViewById(R.id.btnGetActiveServers);

        // Auto connect progress
        layoutAutoProgress = findViewById(R.id.layoutAutoProgress);
        tvAutoStatus = findViewById(R.id.tvAutoStatus);
        tvAutoProgress = findViewById(R.id.tvAutoProgress);
        progressAutoScan = findViewById(R.id.progressAutoScan);
    }

    private void initV2ray() {
        V2rayController.init(this, android.R.drawable.sym_def_app_icon, "Kingo VPN");
    }

    private void setupSettings() {
        ivSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    // ==================== SERVER LOADING ====================

    private void loadServers() {
        allServers = new ArrayList<>();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedServers = prefs.getString(KEY_SAVED_SERVERS, null);

        if (savedServers != null && !savedServers.isEmpty()) {
            try {
                Type listType = new TypeToken<ArrayList<ServerModel>>() {}.getType();
                allServers = new Gson().fromJson(savedServers, listType);
            } catch (Exception e) {
                allServers = new ArrayList<>();
            }
        }

        for (ServerModel s : allServers) {
            if (s.getGroup() == null || s.getGroup().isEmpty()) {
                s.setGroup("servers");
            }
        }

        selectedServerIndex = prefs.getInt(KEY_SELECTED_INDEX, -1);
        currentTab = prefs.getInt(KEY_CURRENT_TAB, 1);
        autoConnectSelected = (selectedServerIndex < 0 && currentTab == 1);

        if (ServerFetcher.shouldFetch(this)) {
            ServerFetcher.fetchServers(this, null);
        }

        filterServersByTab();
    }

    private void filterServersByTab() {
        filteredServers = new ArrayList<>();
        for (ServerModel server : allServers) {
            switch (currentTab) {
                case 0:
                    if (server.isFavorite()) filteredServers.add(server);
                    break;
                case 1:
                    if ("servers".equals(server.getGroup())) filteredServers.add(server);
                    break;
                case 2:
                    if ("custom".equals(server.getGroup())) filteredServers.add(server);
                    break;
            }
        }
        setupServerAdapter();
        updateServersTabUI();
    }

    // ==================== ADAPTER ====================

    private void setupServerAdapter() {
        boolean showAutoConnect = (currentTab == 1);

        serverAdapter = new ServerAdapter(filteredServers,
                new ServerAdapter.OnServerActionListener() {
                    @Override
                    public void onServerSelected(ServerModel server, int position) {
                        int globalIndex = allServers.indexOf(server);
                        if (globalIndex >= 0) {
                            selectedServerIndex = globalIndex;
                        }
                        autoConnectSelected = false;
                        tvSelectedServer.setText(server.getName() + " • " + server.getProtocol());
                        saveServers();
                    }

                    @Override
                    public void onServerLongClick(ServerModel server, int position) {}

                    @Override
                    public void onDeleteServer(ServerModel server, int position) {
                        showDeleteServerDialog(server, position);
                    }

                    @Override
                    public void onToggleFavorite(ServerModel server, int position) {
                        server.setFavorite(!server.isFavorite());
                        saveServers();
                        if (server.isFavorite()) {
                            Toast.makeText(MainActivity.this, R.string.added_to_favorites, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.removed_from_favorites, Toast.LENGTH_SHORT).show();
                            if (currentTab == 0) {
                                filterServersByTab();
                            }
                        }
                        serverAdapter.notifyItemChanged(position);
                    }

                    @Override
                    public void onCopyConfig(ServerModel server) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            ClipData clip = ClipData.newPlainText("v2ray_config", server.getConfig());
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(MainActivity.this, R.string.config_copied, Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onAutoConnectSelected() {
                        autoConnectSelected = true;
                        selectedServerIndex = -1;
                        tvSelectedServer.setText(R.string.best_server);
                        saveServers();
                    }
                }, showAutoConnect);

        rvServers.setLayoutManager(new LinearLayoutManager(this));
        rvServers.setAdapter(serverAdapter);

        if (autoConnectSelected && currentTab == 1) {
            tvSelectedServer.setText(R.string.best_server);
        } else if (selectedServerIndex >= 0 && selectedServerIndex < allServers.size()) {
            ServerModel selected = allServers.get(selectedServerIndex);
            tvSelectedServer.setText(selected.getName() + " • " + selected.getProtocol());
        }
    }

    // ==================== TABS ====================

    private void setupTabs() {
        updateTabUI();

        tabFavorites.setOnClickListener(v -> {
            currentTab = 0;
            autoConnectSelected = false;
            updateTabUI();
            filterServersByTab();
            saveServers();
        });

        tabServers.setOnClickListener(v -> {
            currentTab = 1;
            if (selectedServerIndex < 0) autoConnectSelected = true;
            updateTabUI();
            filterServersByTab();
            saveServers();
        });

        tabCustom.setOnClickListener(v -> {
            currentTab = 2;
            autoConnectSelected = false;
            updateTabUI();
            filterServersByTab();
            saveServers();
        });
    }

    private void updateTabUI() {
        TextView[] tabs = {tabFavorites, tabServers, tabCustom};

        for (TextView tab : tabs) {
            tab.setTextColor(getColor(R.color.tab_unselected));
            tab.setBackground(null);
        }

        TextView selectedTab;
        switch (currentTab) {
            case 0: selectedTab = tabFavorites; break;
            case 2: selectedTab = tabCustom; break;
            default: selectedTab = tabServers; break;
        }
        selectedTab.setTextColor(getColor(R.color.tab_selected));
        selectedTab.setBackgroundResource(R.drawable.tab_selected_bg);
    }

    private void updateServersTabUI() {
        btnGetActiveServers.setVisibility(currentTab == 1 ? View.VISIBLE : View.GONE);
        btnGetActiveServers.setOnClickListener(v -> showScanDialog());
    }

    // ==================== SCAN DIALOG ====================

    private void showScanDialog() {
        ScanServersDialog dialog = ScanServersDialog.newInstance(servers -> {
            ServerFetcher.saveServersToList(this, servers);
            reloadAllServers();
            Toast.makeText(this, getString(R.string.servers_saved), Toast.LENGTH_SHORT).show();
        });
        dialog.show(getSupportFragmentManager(), "scan_servers");
    }

    // ==================== AUTO CONNECT ====================

    private void startAutoConnectFromUrl() {
        isAutoConnecting = true;
        layoutAutoProgress.setVisibility(View.VISIBLE);
        progressAutoScan.setProgress(0);
        tvAutoStatus.setText(R.string.auto_connect_testing);
        tvAutoProgress.setText("");

        ArrayList<ServerModel> candidates = ServerFetcher.getCachedServers(this);

        if (candidates.isEmpty()) {
            ServerFetcher.fetchServers(this, new ServerFetcher.FetchCallback() {
                @Override
                public void onFetchComplete(int serverCount) {
                    runOnUiThread(() -> {
                        if (serverCount == 0) {
                            onAutoConnectFailed();
                            return;
                        }
                        doAutoConnect(ServerFetcher.getCachedServers(MainActivity.this));
                    });
                }

                @Override
                public void onFetchError(String error) {
                    runOnUiThread(() -> onAutoConnectFailed());
                }
            });
        } else {
            if (ServerFetcher.shouldFetch(this)) {
                ServerFetcher.fetchServers(this, null);
            }
            doAutoConnect(candidates);
        }
    }

    private void doAutoConnect(ArrayList<ServerModel> candidates) {
        if (candidates.isEmpty()) {
            onAutoConnectFailed();
            return;
        }

        serverAdapter.autoConnectPing(candidates, new ServerAdapter.AutoConnectCallback() {
            @Override
            public void onProgress(int tested, int total, int responded) {
                int percent = (int) ((tested * 100.0) / total);
                progressAutoScan.setProgress(percent);
                tvAutoProgress.setText(
                        getString(R.string.auto_connect_progress, tested, total));
            }

            @Override
            public void onBestFound(ServerModel server, long ping) {
                isAutoConnecting = false;
                layoutAutoProgress.setVisibility(View.GONE);

                Toast.makeText(MainActivity.this,
                        getString(R.string.auto_connect_found, server.getName(), ping),
                        Toast.LENGTH_LONG).show();

                int globalIndex = allServers.indexOf(server);
                if (globalIndex >= 0) {
                    selectedServerIndex = globalIndex;
                }
                autoConnectSelected = false;
                tvSelectedServer.setText(server.getName() + " • " + server.getProtocol());
                saveServers();

                // Connect directly — do NOT call connect() to avoid re-triggering auto-connect
                V2rayController.startV2ray(
                        MainActivity.this,
                        server.getName(),
                        server.getConfig(),
                        null
                );
                connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);
            }

            @Override
            public void onNoServerFound() {
                onAutoConnectFailed();
            }
        });
    }

    private void onAutoConnectFailed() {
        isAutoConnecting = false;
        layoutAutoProgress.setVisibility(View.GONE);
        currentState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
        updateUI();
        Toast.makeText(this, R.string.auto_connect_failed, Toast.LENGTH_LONG).show();
    }

    // ==================== PING & IP DISPLAY ====================

    private void refreshPing() {
        if (selectedServerIndex < 0 || selectedServerIndex >= allServers.size()) return;
        ServerModel server = allServers.get(selectedServerIndex);

        tvPingValue.setText("...");
        tvPingValue.setTextColor(getColor(R.color.connecting));

        new Thread(() -> {
            try {
                long delay = V2rayController.getV2rayServerDelay(server.getConfig());
                runOnUiThread(() -> {
                    if (delay > 0) {
                        tvPingValue.setText(getString(R.string.ping_value, delay));
                        tvPingValue.setTextColor(getColor(
                                delay < 1500 ? R.color.ping_green : R.color.ping_yellow));
                    } else {
                        tvPingValue.setText("Timeout");
                        tvPingValue.setTextColor(getColor(R.color.ping_red));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvPingValue.setText("Timeout");
                    tvPingValue.setTextColor(getColor(R.color.ping_red));
                });
            }
        }).start();
    }



    private void fetchIPInfo() {
        tvIP.setText(getString(R.string.ip_loading));

        // Cancel previous fetch
        if (ipFetchThread != null && ipFetchThread.isAlive()) {
            ipFetchThread.interrupt();
        }

        ipFetchThread = new Thread(() -> {
            for (int attempt = 1; attempt <= IP_MAX_RETRIES; attempt++) {
                try {
                    URL url = new URL("https://api.ip.sb/geoip");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout((int) IP_FETCH_TIMEOUT_MS);
                    conn.setReadTimeout((int) IP_FETCH_TIMEOUT_MS);
                    conn.connect();

                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        conn.disconnect();

                        JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();
                        String ip = json.has("ip") ? json.get("ip").getAsString() : "N/A";
                        String countryCode = json.has("country_code") ? json.get("country_code").getAsString() : "";

                        ipHandler.post(() -> displayIP(countryCode, ip));
                        return; // Success — stop retrying
                    } else {
                        conn.disconnect();
                    }
                } catch (Exception e) {
                    // Retry
                }

                // Wait before retrying (except on last attempt)
                if (attempt < IP_MAX_RETRIES) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { return; }
                }
            }
            // All retries failed
            ipHandler.post(() -> tvIP.setText(""));
        });
        ipFetchThread.start();
    }

    private String getCountryFlag(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "";
        // Convert country code to flag emoji
        int firstChar = Character.codePointAt(countryCode.toUpperCase(), 0) - 0x41 + 0x1F1E6;
        int secondChar = Character.codePointAt(countryCode.toUpperCase(), 1) - 0x41 + 0x1F1E6;
        return new String(Character.toChars(firstChar)) + new String(Character.toChars(secondChar));
    }

    private void displayIP(String countryCode, String ip) {
        String flag = getCountryFlag(countryCode);
        if (!flag.isEmpty()) {
            tvIP.setText(flag + "  " + ip);
        } else {
            tvIP.setText(ip);
        }
    }

    // ==================== THREE-DOT MENU ====================

    private void setupMenu() {
        ivMenu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenuInflater().inflate(R.menu.popup_main_menu, popup.getMenu());

            if (serverAdapter != null && serverAdapter.isReorderMode()) {
                popup.getMenu().findItem(R.id.menu_reorder).setTitle(R.string.done_reorder);
            }

            popup.getMenu().findItem(R.id.menu_test_ping_all)
                    .setTitle(isTestingAllPing ? R.string.stop_test : R.string.test_all_ping);

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_add_server) {
                    showAddServerDialog();
                    return true;
                } else if (id == R.id.menu_fetch_servers) {
                    fetchAndReload();
                    return true;
                } else if (id == R.id.menu_test_ping_all) {
                    testAllPing();
                    return true;
                } else if (id == R.id.menu_delete_all) {
                    showDeleteAllDialog();
                    return true;
                } else if (id == R.id.menu_sort_ping) {
                    sortByPing();
                    return true;
                } else if (id == R.id.menu_reorder) {
                    toggleReorderMode();
                    return true;
                }
                return false;
            });

            popup.show();
        });
    }

    private void fetchAndReload() {
        ServerFetcher.fetchServers(this, new ServerFetcher.FetchCallback() {
            @Override
            public void onFetchComplete(int serverCount) {
                runOnUiThread(() -> {
                    if (serverCount > 0) {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.fetch_complete, serverCount),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onFetchError(String error) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                R.string.fetch_error, Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void reloadAllServers() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedServers = prefs.getString(KEY_SAVED_SERVERS, null);
        if (savedServers != null && !savedServers.isEmpty()) {
            try {
                Type listType = new TypeToken<ArrayList<ServerModel>>() {}.getType();
                allServers = new Gson().fromJson(savedServers, listType);
                for (ServerModel s : allServers) {
                    if (s.getGroup() == null || s.getGroup().isEmpty()) {
                        s.setGroup("servers");
                    }
                }
            } catch (Exception e) {
                // Keep existing allServers
            }
        }
        filterServersByTab();
    }

    private void testAllPing() {
        if (isTestingAllPing) {
            stopTestAllPing();
            return;
        }

        if (serverAdapter == null || filteredServers.isEmpty()) {
            Toast.makeText(this, R.string.no_servers_in_group, Toast.LENGTH_SHORT).show();
            return;
        }

        isTestingAllPing = true;
        testPingThreads.clear();

        for (ServerModel server : filteredServers) {
            server.setPing(-1);
        }
        serverAdapter.notifyDataSetChanged();

        Toast.makeText(this, R.string.ping_testing, Toast.LENGTH_SHORT).show();

        for (int i = 0; i < filteredServers.size(); i++) {
            ServerModel server = filteredServers.get(i);
            final int index = i;

            Thread thread = new Thread(() -> {
                try {
                    long delay = V2rayController.getV2rayServerDelay(server.getConfig());
                    server.setPing(delay > 0 ? delay : -2);
                } catch (Exception e) {
                    server.setPing(-2);
                }
                runOnUiThread(() -> {
                    if (index < filteredServers.size() && isTestingAllPing) {
                        int adapterPos = serverAdapter.showAutoConnect ? index + 1 : index;
                        serverAdapter.notifyItemChanged(adapterPos);
                    }
                });
            });
            testPingThreads.add(thread);
            thread.start();
        }

        new Thread(() -> {
            ArrayList<Thread> snapshot;
            synchronized (testPingThreads) {
                snapshot = new ArrayList<>(testPingThreads);
            }
            for (Thread t : snapshot) {
                try {
                    t.join();
                } catch (InterruptedException ignored) {}
            }
            runOnUiThread(() -> {
                if (isTestingAllPing) {
                    isTestingAllPing = false;
                    Toast.makeText(this, R.string.ping_all_complete, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void stopTestAllPing() {
        isTestingAllPing = false;
        synchronized (testPingThreads) {
            for (Thread t : testPingThreads) {
                t.interrupt();
            }
            testPingThreads.clear();
        }
        Toast.makeText(this, R.string.ping_stopped, Toast.LENGTH_SHORT).show();
    }

    private void showDeleteAllDialog() {
        if (filteredServers.isEmpty()) {
            Toast.makeText(this, R.string.no_servers_in_group, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_all)
                .setMessage(R.string.delete_all_confirm)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    allServers.removeAll(filteredServers);
                    filteredServers.clear();
                    serverAdapter.notifyDataSetChanged();
                    selectedServerIndex = -1;
                    tvSelectedServer.setText("");
                    saveServers();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void sortByPing() {
        if (filteredServers.isEmpty()) {
            Toast.makeText(this, R.string.no_servers_in_group, Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.sort(filteredServers, (a, b) -> {
            if (a.getPing() == -1 && b.getPing() == -1) return 0;
            if (a.getPing() == -1) return 1;
            if (b.getPing() == -1) return -1;
            if (a.getPing() == -2 && b.getPing() == -2) return 0;
            if (a.getPing() == -2) return 1;
            if (b.getPing() == -2) return -1;
            return Long.compare(a.getPing(), b.getPing());
        });

        syncAllServersFromFiltered();
        serverAdapter.notifyDataSetChanged();
        saveServers();
        Toast.makeText(this, R.string.servers_sorted, Toast.LENGTH_SHORT).show();
    }

    private void syncAllServersFromFiltered() {
        ArrayList<ServerModel> others = new ArrayList<>();
        for (ServerModel s : allServers) {
            if (!filteredServers.contains(s)) {
                others.add(s);
            }
        }
        allServers.clear();
        allServers.addAll(others);
        allServers.addAll(filteredServers);
    }

    private void toggleReorderMode() {
        if (serverAdapter == null) return;

        boolean newMode = !serverAdapter.isReorderMode();
        serverAdapter.setReorderMode(newMode);

        if (newMode) {
            Toast.makeText(this, R.string.reorder_mode_enabled, Toast.LENGTH_SHORT).show();
        } else {
            syncAllServersFromFiltered();
            saveServers();
        }
    }

    // ==================== DRAG AND DROP ====================

    private void setupDragAndDrop() {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                if (serverAdapter == null || !serverAdapter.isReorderMode()) return false;
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                if (fromPos == 0 || toPos == 0) return false;
                serverAdapter.swapItems(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

            @Override
            public boolean isLongPressDragEnabled() {
                return serverAdapter != null && serverAdapter.isReorderMode();
            }
        };

        itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(rvServers);
    }

    // ==================== ADD / DELETE SERVER ====================

    private void showAddServerDialog() {
        AddServerDialog dialog = AddServerDialog.newInstance(new AddServerDialog.OnServerAddedListener() {
            @Override
            public void onServerAdded(ServerModel server) {
                server.setGroup("custom");
                allServers.add(server);
                saveServers();

                if (currentTab == 2) {
                    filterServersByTab();
                }

                selectedServerIndex = allServers.size() - 1;
                autoConnectSelected = false;
                tvSelectedServer.setText(server.getName() + " • " + server.getProtocol());

                currentTab = 2;
                updateTabUI();
                filterServersByTab();
            }
        });
        dialog.show(getSupportFragmentManager(), "add_server");
    }

    private void showDeleteServerDialog(ServerModel server, int position) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_server)
                .setMessage(server.getName())
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    int globalIndex = allServers.indexOf(server);
                    allServers.remove(server);
                    filteredServers.remove(position);

                    if (selectedServerIndex == globalIndex) {
                        selectedServerIndex = -1;
                        tvSelectedServer.setText("");
                    } else if (selectedServerIndex > globalIndex) {
                        selectedServerIndex--;
                    }

                    int adapterPos = serverAdapter.showAutoConnect ? position + 1 : position;
                    serverAdapter.notifyItemRemoved(adapterPos);
                    serverAdapter.notifyItemRangeChanged(adapterPos, filteredServers.size());
                    saveServers();

                    Toast.makeText(this, R.string.server_deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ==================== CONNECTION ====================

    private void setupListeners() {
        btnPower.setOnClickListener(v -> {
            switch (currentState) {
                case DISCONNECTED:
                    connect();
                    break;
                case CONNECTED:
                    disconnect();
                    break;
                case CONNECTING:
                    cancelConnecting();
                    break;
            }
        });

        ivRefreshPing.setOnClickListener(v -> refreshPing());
    }

    private void connect() {
        // Reset one-shot flags for each new connection
        pingFetchedThisSession = false;
        ipFetchedThisSession = false;

        if (autoConnectSelected && currentTab == 1 && !isAutoConnecting) {
            currentState = V2rayConstants.CONNECTION_STATES.CONNECTING;
            updateUI();
            startAutoConnectFromUrl();
            return;
        }

        ServerModel selectedServer = null;
        if (selectedServerIndex >= 0 && selectedServerIndex < allServers.size()) {
            selectedServer = allServers.get(selectedServerIndex);
        }

        if (selectedServer == null || selectedServer.getConfig() == null || selectedServer.getConfig().isEmpty()) {
            Toast.makeText(this, R.string.please_select_server, Toast.LENGTH_SHORT).show();
            return;
        }

        V2rayController.startV2ray(
                this,
                selectedServer.getName(),
                selectedServer.getConfig(),
                null
        );

        connectionTimeoutHandler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS);
    }

    private void disconnect() {
        V2rayController.stopV2ray(this);
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        stopTimer();
    }

    private void cancelConnecting() {
        V2rayController.stopV2ray(this);
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        stopTimer();
    }

    // ==================== STATE RECEIVER ====================

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiver() {
        try {
            IntentFilter filter = new IntentFilter("V2RAY_SERVICE_STATICS_INTENT");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, RECEIVER_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
        } catch (Exception ignore) {}
    }

    // ==================== UI UPDATE ====================

    private void updateUI() {
        switch (currentState) {
            case DISCONNECTED:
                tvStatus.setText(R.string.status_disconnected);
                tvStatus.setTextColor(getColor(R.color.text_secondary));
                tvConnectHint.setText(R.string.tap_to_connect);
                tvConnectHint.setVisibility(View.VISIBLE);
                btnPower.setBackgroundResource(R.drawable.power_button_bg);
                progressRingIndeterminate.setVisibility(View.GONE);
                progressRing.setVisibility(View.GONE);
                tvTimer.setVisibility(View.GONE);
                layoutTraffic.setVisibility(View.GONE);
                layoutPing.setVisibility(View.GONE);
                layoutIP.setVisibility(View.GONE);
                stopTimer();
                // Reset one-shot flags for next connection session
                pingFetchedThisSession = false;
                ipFetchedThisSession = false;
                break;

            case CONNECTING:
                tvStatus.setText(R.string.status_connecting);
                tvStatus.setTextColor(getColor(R.color.connecting));
                tvConnectHint.setText(R.string.tap_to_cancel);
                tvConnectHint.setVisibility(View.VISIBLE);
                btnPower.setBackgroundResource(R.drawable.power_button_bg_connecting);
                progressRingIndeterminate.setVisibility(View.VISIBLE);
                progressRing.setVisibility(View.GONE);
                // Timer shows 00:00:00 but does NOT start counting
                tvTimer.setVisibility(View.VISIBLE);
                tvTimer.setText("00:00:00");
                layoutTraffic.setVisibility(View.VISIBLE);
                tvUpload.setText("↑ 0.0 B/s");
                tvDownload.setText("↓ 0.0 B/s");
                layoutPing.setVisibility(View.GONE);
                layoutIP.setVisibility(View.GONE);
                break;

            case CONNECTED:
                tvStatus.setText(R.string.status_connected);
                tvStatus.setTextColor(getColor(R.color.connected));
                tvConnectHint.setText(R.string.tap_to_disconnect);
                tvConnectHint.setVisibility(View.VISIBLE);
                btnPower.setBackgroundResource(R.drawable.power_button_bg_connected);
                progressRingIndeterminate.setVisibility(View.GONE);
                progressRing.setVisibility(View.VISIBLE);
                tvTimer.setVisibility(View.VISIBLE);
                layoutTraffic.setVisibility(View.VISIBLE);
                layoutPing.setVisibility(View.VISIBLE);
                layoutIP.setVisibility(View.VISIBLE);
                startTimer();
                // Only fetch ping/IP once per connection session
                if (!pingFetchedThisSession) {
                    pingFetchedThisSession = true;
                    refreshPing();
                }
                if (!ipFetchedThisSession) {
                    ipFetchedThisSession = true;
                    fetchIPInfo();
                }
                break;
        }
    }

    // ==================== TIMER ====================

    private void startTimer() {
        if (!isTimerRunning) {
            connectionStartTime = System.currentTimeMillis();
            isTimerRunning = true;
            receivedBroadcastDuration = false;
            timerHandler.post(timerRunnable);
        }
    }

    private void stopTimer() {
        isTimerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
        tvTimer.setText("00:00:00");
    }

    // ==================== PERSISTENCE ====================

    private void saveServers() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String json = new Gson().toJson(allServers);
        editor.putString(KEY_SAVED_SERVERS, json);
        editor.putInt(KEY_SELECTED_INDEX, selectedServerIndex);
        editor.putInt(KEY_CURRENT_TAB, currentTab);
        editor.apply();
    }

    // ==================== LIFECYCLE ====================

    @Override
    protected void onResume() {
        super.onResume();
        V2rayController.registerReceivers(this);
        currentState = V2rayController.getConnectionState();
        updateUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignore) {}
        stopTimer();
        connectionTimeoutHandler.removeCallbacks(connectionTimeoutRunnable);
        if (ipFetchThread != null && ipFetchThread.isAlive()) {
            ipFetchThread.interrupt();
        }
        if (notificationChecker != null) {
            notificationChecker.stopPeriodicCheck();
        }
    }
}
