package com.kingo.vpn;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.dev7.lib.v2ray.V2rayController;

/**
 * Full-screen dialog that scans servers from URL and shows only responding ones.
 * User can stop/resume scan, rescan, save to server list, or copy to clipboard.
 */
public class ScanServersDialog extends DialogFragment {

    private static final long FETCH_TIMEOUT_MS = 10000; // 10 seconds

    public interface OnServersSavedListener {
        void onServersSaved(ArrayList<ServerModel> servers);
    }

    private OnServersSavedListener listener;
    private ArrayList<ServerModel> foundServers = new ArrayList<>();
    private ArrayList<ServerModel> allCandidates = new ArrayList<>();
    private ScanServerAdapter adapter;
    private ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanStopped = new AtomicBoolean(false);
    private final AtomicBoolean scanPaused = new AtomicBoolean(false);
    private final AtomicBoolean scanComplete = new AtomicBoolean(false);
    private final AtomicBoolean fetchDone = new AtomicBoolean(false);
    private final AtomicBoolean scanStartedFromFetch = new AtomicBoolean(false);
    private int totalToTest = 0;
    private final AtomicInteger testedCount = new AtomicInteger(0);
    private final AtomicInteger foundCount = new AtomicInteger(0);
    private volatile int scanGeneration = 0;

    // Views
    private RecyclerView rvScanServers;
    private TextView tvScanStatus;
    private TextView tvFoundCount;
    private ProgressBar progressScan;
    private LinearLayout layoutScanHeader;
    private LinearLayout layoutScanControls;
    private TextView btnStopResume;
    private TextView btnRescan;
    private TextView btnSave;
    private TextView btnCopy;

    public static ScanServersDialog newInstance(OnServersSavedListener listener) {
        ScanServersDialog dialog = new ScanServersDialog();
        dialog.listener = listener;
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.FullScreenDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_scan_servers, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvScanServers = view.findViewById(R.id.rvScanServers);
        tvScanStatus = view.findViewById(R.id.tvScanStatus);
        tvFoundCount = view.findViewById(R.id.tvFoundCount);
        progressScan = view.findViewById(R.id.progressScan);
        layoutScanHeader = view.findViewById(R.id.layoutScanHeader);
        layoutScanControls = view.findViewById(R.id.layoutScanControls);
        btnStopResume = view.findViewById(R.id.btnStopResume);
        btnRescan = view.findViewById(R.id.btnRescan);
        btnSave = view.findViewById(R.id.btnSave);
        btnCopy = view.findViewById(R.id.btnCopy);
        ImageView ivClose = view.findViewById(R.id.ivClose);

        adapter = new ScanServerAdapter(foundServers);
        rvScanServers.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvScanServers.setAdapter(adapter);

        ivClose.setOnClickListener(v -> {
            stopScan();
            dismissAllowingStateLoss();
        });

        btnStopResume.setOnClickListener(v -> {
            if (scanComplete.get()) return;
            if (scanPaused.get()) {
                resumeScan();
            } else {
                pauseScan();
            }
        });

        btnRescan.setOnClickListener(v -> startScan());

        btnSave.setOnClickListener(v -> {
            if (!foundServers.isEmpty() && listener != null) {
                for (ServerModel s : foundServers) {
                    s.setGroup("servers");
                }
                listener.onServersSaved(new ArrayList<>(foundServers));
                Toast.makeText(requireContext(), R.string.servers_saved, Toast.LENGTH_SHORT).show();
            }
            dismissAllowingStateLoss();
        });

        btnCopy.setOnClickListener(v -> {
            if (!foundServers.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < foundServers.size(); i++) {
                    sb.append(foundServers.get(i).getConfig());
                    if (i < foundServers.size() - 1) sb.append("\n");
                }
                ClipboardManager clipboard = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("servers", sb.toString());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(requireContext(), R.string.servers_copied, Toast.LENGTH_SHORT).show();
                }
            }
            // Do NOT close dialog on copy
        });

        // Start scanning
        startScan();
    }

    private void startScan() {
        foundServers.clear();
        allCandidates.clear();
        adapter.notifyDataSetChanged();
        scanStopped.set(false);
        scanPaused.set(false);
        scanComplete.set(false);
        fetchDone.set(false);
        testedCount.set(0);
        foundCount.set(0);
        scanGeneration++;

        // Show only Stop button during active scan
        showScanningButtons();

        // Try cached servers first (instant)
        ArrayList<ServerModel> cached = ServerFetcher.getCachedServers(requireContext());

        if (!cached.isEmpty()) {
            // Use cached immediately, also fetch fresh in background
            allCandidates.clear();
            allCandidates.addAll(cached);
            totalToTest = cached.size();
            updateScanStatus(0, totalToTest, 0);
            runPingScan(cached);

            // Fetch fresh servers in background to update cache for next time
            fetchWithTimeout(true);
        } else {
            // No cache, must fetch
            tvScanStatus.setText(R.string.scanning);
            fetchWithTimeout(false);
        }
    }

    private void fetchWithTimeout(boolean backgroundOnly) {
        // Use compareAndSet to prevent double-start race condition
        scanStartedFromFetch.set(false);

        ServerFetcher.fetchServers(requireContext(), new ServerFetcher.FetchCallback() {
            @Override
            public void onFetchComplete(int serverCount) {
                if (!isAdded()) return;
                mainHandler.post(() -> {
                    fetchDone.set(true);
                    ArrayList<ServerModel> fresh = ServerFetcher.getCachedServers(requireContext());

                    if (backgroundOnly) {
                        // Just refresh cache for next time
                        return;
                    }

                    if (fresh.isEmpty() || serverCount == 0) {
                        onScanFinished(true);
                        return;
                    }

                    // Gate: only one path can start the scan
                    if (scanStartedFromFetch.compareAndSet(false, true)) {
                        allCandidates.clear();
                        allCandidates.addAll(fresh);
                        totalToTest = fresh.size();
                        updateScanStatus(0, totalToTest, 0);
                        runPingScan(fresh);
                    }
                });
            }

            @Override
            public void onFetchError(String error) {
                if (!isAdded()) return;
                mainHandler.post(() -> {
                    fetchDone.set(true);

                    if (!backgroundOnly && !allCandidates.isEmpty()) {
                        // Already using cached servers from startScan(), continue
                        return;
                    }

                    if (!backgroundOnly) {
                        // No cache and fetch failed — try cached one more time
                        ArrayList<ServerModel> fallback = ServerFetcher.getCachedServers(requireContext());
                        if (!fallback.isEmpty()) {
                            if (scanStartedFromFetch.compareAndSet(false, true)) {
                                allCandidates.clear();
                                allCandidates.addAll(fallback);
                                totalToTest = fallback.size();
                                updateScanStatus(0, totalToTest, 0);
                                runPingScan(fallback);
                            }
                        } else {
                            Toast.makeText(requireContext(), R.string.fetch_error, Toast.LENGTH_SHORT).show();
                            onScanFinished(true);
                        }
                    }
                });
            }
        });

        // Timeout: if fetch doesn't complete in FETCH_TIMEOUT_MS, try cached
        mainHandler.postDelayed(() -> {
            if (!isAdded()) return;
            if (fetchDone.get()) return; // Fetch already completed

            if (!backgroundOnly && allCandidates.isEmpty()) {
                // Fetch timed out, try cached
                ArrayList<ServerModel> fallback = ServerFetcher.getCachedServers(requireContext());
                if (!fallback.isEmpty()) {
                    // Gate: only one path can start the scan
                    if (scanStartedFromFetch.compareAndSet(false, true)) {
                        allCandidates.clear();
                        allCandidates.addAll(fallback);
                        totalToTest = fallback.size();
                        updateScanStatus(0, totalToTest, 0);
                        runPingScan(fallback);
                    }
                } else {
                    onScanFinished(true);
                }
            }
        }, FETCH_TIMEOUT_MS);
    }

    private void runPingScan(ArrayList<ServerModel> candidates) {
        final int generation = scanGeneration;

        for (int i = 0; i < candidates.size(); i++) {
            if (scanStopped.get()) break;

            final int index = i;
            ServerModel server = candidates.get(index);

            executor.execute(() -> {
                if (scanStopped.get() || scanPaused.get()) return;

                try {
                    long delay = V2rayController.getV2rayServerDelay(server.getConfig());

                    if (scanStopped.get()) return;

                    server.setPing(delay > 0 ? delay : -2);

                    if (delay > 0) {
                        mainHandler.post(() -> {
                            if (!isAdded() || scanComplete.get() || generation != scanGeneration) return;
                            foundServers.add(server);
                            int insertedPos = foundServers.size() - 1;
                            adapter.notifyItemInserted(insertedPos);
                            rvScanServers.scrollToPosition(insertedPos);
                            foundCount.incrementAndGet();
                            updateCounts();
                        });
                    }
                } catch (Exception e) {
                    if (scanStopped.get()) return;
                    server.setPing(-2);
                }

                mainHandler.post(() -> {
                    if (!isAdded() || scanComplete.get() || generation != scanGeneration) return;
                    int count = testedCount.incrementAndGet();
                    progressScan.setProgress((int) ((count * 100.0) / totalToTest));
                    updateCounts();

                    if (count >= totalToTest && !scanComplete.get()) {
                        onScanFinished(false);
                    }
                });
            });
        }
    }

    private void updateCounts() {
        tvFoundCount.setText(getString(R.string.scanning_servers_found,
                testedCount.get(), totalToTest, foundCount.get()));
    }

    private void pauseScan() {
        scanPaused.set(true);
        showPostScanButtons();

        // Check if all tasks already finished before we paused
        if (testedCount.get() >= totalToTest) {
            onScanFinished(false);
        }
    }

    private void resumeScan() {
        scanPaused.set(false);
        showScanningButtons();

        // Re-submit remaining untested servers
        int tested = testedCount.get();
        if (allCandidates.size() > tested) {
            ArrayList<ServerModel> untested = new ArrayList<>(
                    allCandidates.subList(tested, allCandidates.size()));
            totalToTest = allCandidates.size();
            runPingScan(untested);
        } else {
            // All were already tested
            if (!scanComplete.get()) {
                onScanFinished(false);
            }
        }
    }

    private void showScanningButtons() {
        // During active scan: only show Stop button
        layoutScanControls.setVisibility(View.VISIBLE);
        btnStopResume.setText(R.string.stop_scan);
        btnStopResume.setVisibility(View.VISIBLE);
        btnRescan.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
        btnCopy.setVisibility(View.GONE);
    }

    private void showPostScanButtons() {
        // After scan stops/pauses/completes: show all buttons together
        layoutScanControls.setVisibility(View.VISIBLE);
        btnStopResume.setText(R.string.resume_scan);
        btnStopResume.setVisibility(View.VISIBLE);
        btnRescan.setVisibility(View.VISIBLE);

        if (!foundServers.isEmpty()) {
            btnSave.setVisibility(View.VISIBLE);
            btnCopy.setVisibility(View.VISIBLE);
        } else {
            btnSave.setVisibility(View.GONE);
            btnCopy.setVisibility(View.GONE);
        }
    }

    private void onScanFinished(boolean noServers) {
        if (scanComplete.get()) return;
        scanComplete.set(true);
        scanStopped.set(true);

        showPostScanButtons();

        if (noServers && foundServers.isEmpty()) {
            tvScanStatus.setText(R.string.no_active_servers);
            tvFoundCount.setText("");
        } else {
            tvScanStatus.setText(getString(R.string.scanning_complete, foundServers.size()));
            tvFoundCount.setText("");
        }
    }

    private void updateScanStatus(int tested, int total, int found) {
        tvScanStatus.setText(getString(R.string.scanning_servers, tested, total));
        tvFoundCount.setText(getString(R.string.scanning_servers_found, tested, total, found));
    }

    private void stopScan() {
        scanStopped.set(true);
        scanComplete.set(true);
        scanGeneration++;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopScan();
        executor.shutdownNow();
    }

    // ==================== ADAPTER ====================

    static class ScanServerAdapter extends RecyclerView.Adapter<ScanServerAdapter.ViewHolder> {
        private final ArrayList<ServerModel> servers;

        ScanServerAdapter(ArrayList<ServerModel> servers) {
            this.servers = servers;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_server, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ServerModel server = servers.get(position);
            holder.tvServerName.setText(server.getName());
            holder.tvProtocol.setText(server.getProtocol() + " server");

            long ping = server.getPing();
            if (ping > 0) {
                holder.tvPing.setText(ping + " ms");
                holder.tvPing.setTextColor(holder.itemView.getContext().getColor(
                        ping < 1500 ? R.color.ping_green : R.color.ping_yellow));
            } else {
                holder.tvPing.setText("--");
                holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.text_hint));
            }
        }

        @Override
        public int getItemCount() {
            return servers.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvServerName, tvProtocol, tvPing;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvServerName = itemView.findViewById(R.id.tvServerName);
                tvProtocol = itemView.findViewById(R.id.tvProtocol);
                tvPing = itemView.findViewById(R.id.tvPing);
            }
        }
    }
}
