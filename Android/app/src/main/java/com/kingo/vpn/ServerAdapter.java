package com.kingo.vpn;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.dev7.lib.v2ray.V2rayController;

public class ServerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_AUTO_CONNECT = 0;
    private static final int VIEW_TYPE_SERVER = 1;

    private ArrayList<ServerModel> servers;
    private int selectedPosition = -1;
    private boolean reorderMode = false;
    public boolean showAutoConnect = false; // true when on Servers tab
    private final OnServerActionListener listener;
    private final ExecutorService executorService = Executors.newFixedThreadPool(8);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanStopped = new AtomicBoolean(false);
    private volatile int scanGeneration = 0;

    public interface OnServerActionListener {
        void onServerSelected(ServerModel server, int position);
        void onServerLongClick(ServerModel server, int position);
        void onDeleteServer(ServerModel server, int position);
        void onToggleFavorite(ServerModel server, int position);
        void onCopyConfig(ServerModel server);
        void onAutoConnectSelected(); // Called when "Best Server" item is tapped
    }

    public interface ManualScanCallback {
        void onServerFound(ServerModel server, long ping);
        void onProgress(int tested, int total, int found);
        void onComplete(int totalFound, boolean stopped);
    }

    public ServerAdapter(ArrayList<ServerModel> servers, OnServerActionListener listener, boolean showAutoConnect) {
        this.servers = servers;
        this.listener = listener;
        this.showAutoConnect = showAutoConnect;
    }

    public void setShowAutoConnect(boolean show) {
        this.showAutoConnect = show;
        notifyDataSetChanged();
    }

    public boolean isShowAutoConnect() {
        return showAutoConnect;
    }

    @Override
    public int getItemViewType(int position) {
        if (showAutoConnect && position == 0) {
            return VIEW_TYPE_AUTO_CONNECT;
        }
        return VIEW_TYPE_SERVER;
    }

    @Override
    public int getItemCount() {
        return servers.size() + (showAutoConnect ? 1 : 0);
    }

    private ServerModel getServerAtPosition(int position) {
        if (showAutoConnect) {
            return servers.get(position - 1);
        }
        return servers.get(position);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_AUTO_CONNECT) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_server, parent, false);
            return new AutoConnectViewHolder(view);
        }
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ServerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        if (holder instanceof AutoConnectViewHolder) {
            bindAutoConnect((AutoConnectViewHolder) holder);
        } else if (holder instanceof ServerViewHolder) {
            bindServer((ServerViewHolder) holder, position);
        }
    }

    private void bindAutoConnect(AutoConnectViewHolder holder) {
        holder.tvServerName.setText(R.string.best_server);
        holder.tvProtocol.setText(R.string.auto_connect_subtitle);
        holder.tvPing.setText("--");
        holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.text_hint));
        holder.ivDragHandle.setVisibility(View.GONE);

        boolean isSelected = (selectedPosition == -1); // Auto connect is "selected" when no server selected
        holder.itemView.setSelected(isSelected);

        holder.itemView.setOnClickListener(v -> {
            if (reorderMode) return;
            int prev = selectedPosition;
            selectedPosition = -1;
            if (prev >= 0 && prev < servers.size()) {
                notifyItemChanged(prev + 1); // +1 for auto connect header
            }
            notifyItemChanged(0);
            if (listener != null) {
                listener.onAutoConnectSelected();
            }
        });
    }

    private void bindServer(ServerViewHolder holder, int position) {
        ServerModel server = getServerAtPosition(position);

        holder.tvServerName.setText(server.getName());

        String protocolDisplay = server.getProtocol() + " server";
        holder.tvProtocol.setText(protocolDisplay);

        holder.ivDragHandle.setVisibility(reorderMode ? View.VISIBLE : View.GONE);

        // Ping display
        if (server.getPing() == -1) {
            holder.tvPing.setText("--");
            holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.text_hint));
        } else if (server.getPing() == -2) {
            holder.tvPing.setText("Timeout");
            holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.ping_red));
        } else {
            holder.tvPing.setText(server.getPing() + " ms");
            if (server.getPing() < 1500) {
                holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.ping_green));
            } else {
                holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.ping_yellow));
            }
        }

        boolean isSelected = (position == selectedPosition + (showAutoConnect ? 1 : 0));
        holder.itemView.setSelected(isSelected);

        holder.itemView.setOnClickListener(v -> {
            if (reorderMode) return;
            int serverIndex = position - (showAutoConnect ? 1 : 0);
            int previousSelected = selectedPosition;
            selectedPosition = serverIndex;
            // Update previous auto connect if it was selected
            if (previousSelected == -1 && showAutoConnect) {
                notifyItemChanged(0);
            }
            if (previousSelected >= 0) {
                notifyItemChanged(previousSelected + (showAutoConnect ? 1 : 0));
            }
            notifyItemChanged(position);
            if (listener != null) {
                listener.onServerSelected(server, serverIndex);
            }
        });

        holder.tvPing.setOnClickListener(v -> {
            if (reorderMode) return;
            holder.tvPing.setText("...");
            holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.connecting));
            server.setPing(-1);

            executorService.execute(() -> {
                try {
                    long delay = V2rayController.getV2rayServerDelay(server.getConfig());
                    mainHandler.post(() -> {
                        if (delay > 0) {
                            server.setPing(delay);
                            holder.tvPing.setText(delay + " ms");
                            holder.tvPing.setTextColor(holder.itemView.getContext().getColor(
                                    delay < 1500 ? R.color.ping_green : R.color.ping_yellow));
                        } else {
                            server.setPing(-2);
                            holder.tvPing.setText("Timeout");
                            holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.ping_red));
                        }
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        server.setPing(-2);
                        holder.tvPing.setText("Timeout");
                        holder.tvPing.setTextColor(holder.itemView.getContext().getColor(R.color.ping_red));
                    });
                }
            });
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (reorderMode) return false;
            showServerContextMenu(v, server, position - (showAutoConnect ? 1 : 0));
            return true;
        });
    }

    private void showServerContextMenu(View anchor, ServerModel server, int position) {
        PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
        popup.getMenuInflater().inflate(R.menu.popup_server_menu, popup.getMenu());

        if (server.isFavorite()) {
            popup.getMenu().findItem(R.id.menu_toggle_favorite).setTitle(R.string.remove_from_favorites);
        } else {
            popup.getMenu().findItem(R.id.menu_toggle_favorite).setTitle(R.string.add_to_favorites);
        }

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_delete_server) {
                if (listener != null) listener.onDeleteServer(server, position);
                return true;
            } else if (id == R.id.menu_toggle_favorite) {
                if (listener != null) listener.onToggleFavorite(server, position);
                return true;
            } else if (id == R.id.menu_copy_config) {
                if (listener != null) listener.onCopyConfig(server);
                return true;
            }
            return false;
        });

        popup.show();
    }

    public void setServers(ArrayList<ServerModel> newServers) {
        this.servers = newServers;
        notifyDataSetChanged();
    }

    public ArrayList<ServerModel> getServers() {
        return servers;
    }

    public void setReorderMode(boolean enabled) {
        this.reorderMode = enabled;
        notifyDataSetChanged();
    }

    public boolean isReorderMode() {
        return reorderMode;
    }

    public void setSelectedPosition(int position) {
        int prev = selectedPosition;
        selectedPosition = position;
        if (prev == -1 && showAutoConnect) notifyItemChanged(0);
        if (prev >= 0) notifyItemChanged(prev + (showAutoConnect ? 1 : 0));
        if (position >= 0) notifyItemChanged(position + (showAutoConnect ? 1 : 0));
    }

    public void swapItems(int fromPosition, int toPosition) {
        // Adjust for auto connect header
        if (showAutoConnect) {
            fromPosition--;
            toPosition--;
        }
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(servers, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(servers, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition + (showAutoConnect ? 1 : 0), toPosition + (showAutoConnect ? 1 : 0));

        if (selectedPosition == fromPosition) {
            selectedPosition = toPosition;
        } else if (fromPosition < selectedPosition && toPosition >= selectedPosition) {
            selectedPosition--;
        } else if (fromPosition > selectedPosition && toPosition <= selectedPosition) {
            selectedPosition++;
        }
    }

    /**
     * Manual scan from a list of candidates.
     * Tests each server one by one, calls onServerFound for each that responds.
     */
    public void manualScanFromList(ArrayList<ServerModel> candidates, ManualScanCallback callback) {
        scanStopped.set(false);
        final int generation = ++scanGeneration;
        final int total = candidates.size();
        final int[] tested = {0};
        final int[] found = {0};
        final AtomicBoolean isComplete = new AtomicBoolean(false);

        for (int i = 0; i < candidates.size(); i++) {
            if (scanStopped.get()) break;

            final int index = i;
            ServerModel server = candidates.get(index);

            executorService.execute(() -> {
                if (scanStopped.get()) return;

                try {
                    long delay = V2rayController.getV2rayServerDelay(server.getConfig());

                    if (scanStopped.get()) return;

                    server.setPing(delay > 0 ? delay : -2);

                    mainHandler.post(() -> {
                        if (isComplete.get() || generation != scanGeneration) return;

                        tested[0]++;
                        boolean responded = delay > 0;

                        if (responded) {
                            found[0]++;
                            if (callback != null) {
                                callback.onServerFound(server, delay);
                            }
                        }

                        if (callback != null) {
                            callback.onProgress(tested[0], total, found[0]);
                        }

                        if (tested[0] == total && !isComplete.get()) {
                            isComplete.set(true);
                            callback.onComplete(found[0], false);
                        }
                    });
                } catch (Exception e) {
                    if (scanStopped.get()) return;
                    server.setPing(-2);
                    mainHandler.post(() -> {
                        if (isComplete.get() || generation != scanGeneration) return;
                        tested[0]++;
                        if (callback != null) {
                            callback.onProgress(tested[0], total, found[0]);
                        }
                        if (tested[0] == total && !isComplete.get()) {
                            isComplete.set(true);
                            callback.onComplete(found[0], false);
                        }
                    });
                }
            });
        }
    }

    /**
     * Auto connect: randomly pick servers from the given list, batch ping them,
     * return the fastest server among the first 2 that respond.
     */
    public void autoConnectPing(ArrayList<ServerModel> candidates, AutoConnectCallback callback) {
        scanStopped.set(false);
        final AtomicBoolean bestFound = new AtomicBoolean(false);

        ArrayList<ServerModel> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled);

        final int[] respondedCount = {0};
        final ServerModel[] bestServer = {null};
        final long[] bestPing = {Long.MAX_VALUE};
        final int[] testedCount = {0};
        final int total = shuffled.size();

        for (int i = 0; i < shuffled.size(); i++) {
            if (scanStopped.get()) break;

            final int index = i;
            ServerModel server = shuffled.get(index);

            executorService.execute(() -> {
                if (scanStopped.get()) return;

                try {
                    long delay = V2rayController.getV2rayServerDelay(server.getConfig());

                    if (scanStopped.get()) return;

                    server.setPing(delay > 0 ? delay : -2);

                    mainHandler.post(() -> {
                        if (bestFound.get()) return;

                        testedCount[0]++;
                        if (delay > 0) {
                            respondedCount[0]++;
                            if (delay < bestPing[0]) {
                                bestPing[0] = delay;
                                bestServer[0] = server;
                            }
                        }

                        if (callback != null) {
                            callback.onProgress(testedCount[0], total, respondedCount[0]);
                        }

                        if (respondedCount[0] >= 2 && !bestFound.get()) {
                            bestFound.set(true);
                            scanStopped.set(true);
                            if (callback != null) {
                                callback.onBestFound(bestServer[0], bestPing[0]);
                            }
                        } else if (testedCount[0] == total && !bestFound.get()) {
                            bestFound.set(true);
                            if (bestServer[0] != null) {
                                if (callback != null) {
                                    callback.onBestFound(bestServer[0], bestPing[0]);
                                }
                            } else {
                                if (callback != null) {
                                    callback.onNoServerFound();
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    if (scanStopped.get()) return;
                    server.setPing(-2);
                    mainHandler.post(() -> {
                        if (bestFound.get()) return;
                        testedCount[0]++;
                        if (callback != null) {
                            callback.onProgress(testedCount[0], total, respondedCount[0]);
                        }
                        if (testedCount[0] == total && !bestFound.get()) {
                            bestFound.set(true);
                            if (bestServer[0] != null) {
                                callback.onBestFound(bestServer[0], bestPing[0]);
                            } else {
                                callback.onNoServerFound();
                            }
                        }
                    });
                }
            });
        }
    }

    public void stopScan() {
        scanStopped.set(true);
        scanGeneration++;
    }

    public interface AutoConnectCallback {
        void onProgress(int tested, int total, int responded);
        void onBestFound(ServerModel server, long ping);
        void onNoServerFound();
    }

    static class AutoConnectViewHolder extends RecyclerView.ViewHolder {
        ImageView ivDragHandle;
        TextView tvServerName, tvProtocol, tvPing;

        AutoConnectViewHolder(@NonNull View itemView) {
            super(itemView);
            ivDragHandle = itemView.findViewById(R.id.ivDragHandle);
            tvServerName = itemView.findViewById(R.id.tvServerName);
            tvProtocol = itemView.findViewById(R.id.tvProtocol);
            tvPing = itemView.findViewById(R.id.tvPing);
        }
    }

    static class ServerViewHolder extends RecyclerView.ViewHolder {
        ImageView ivDragHandle;
        TextView tvServerName, tvProtocol, tvPing;

        ServerViewHolder(@NonNull View itemView) {
            super(itemView);
            ivDragHandle = itemView.findViewById(R.id.ivDragHandle);
            tvServerName = itemView.findViewById(R.id.tvServerName);
            tvProtocol = itemView.findViewById(R.id.tvProtocol);
            tvPing = itemView.findViewById(R.id.tvPing);
        }
    }
}
