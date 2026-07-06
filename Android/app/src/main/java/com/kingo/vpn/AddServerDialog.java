package com.kingo.vpn;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;

import com.google.android.material.button.MaterialButton;

public class AddServerDialog extends AppCompatDialogFragment {

    public interface OnServerAddedListener {
        void onServerAdded(ServerModel server);
    }

    private OnServerAddedListener listener;

    public static AddServerDialog newInstance(OnServerAddedListener listener) {
        AddServerDialog dialog = new AddServerDialog();
        dialog.setListener(listener);
        return dialog;
    }

    public void setListener(OnServerAddedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_add_server, null);

        EditText etServerName = view.findViewById(R.id.etServerName);
        EditText etConfig = view.findViewById(R.id.etConfig);
        MaterialButton btnPaste = view.findViewById(R.id.btnPaste);
        MaterialButton btnCancel = view.findViewById(R.id.btnCancel);
        MaterialButton btnAdd = view.findViewById(R.id.btnAdd);

        // Paste from clipboard
        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text != null) {
                        etConfig.setText(text.toString());
                    }
                }
            } else {
                Toast.makeText(getContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            }
        });

        // Cancel
        btnCancel.setOnClickListener(v -> dismiss());

        // Add server
        btnAdd.setOnClickListener(v -> {
            String config = etConfig.getText().toString().trim();

            if (TextUtils.isEmpty(config)) {
                Toast.makeText(getContext(), R.string.please_enter_config, Toast.LENGTH_SHORT).show();
                return;
            }

            // Validate config format
            if (!isValidV2rayConfig(config)) {
                Toast.makeText(getContext(), R.string.invalid_config, Toast.LENGTH_SHORT).show();
                return;
            }

            // Use the new factory method to parse config and extract name/protocol
            ServerModel server = ServerModel.fromConfig(config, "custom");

            // Override name if user provided one
            String userName = etServerName.getText().toString().trim();
            if (!TextUtils.isEmpty(userName)) {
                server.setName(userName);
            }

            if (listener != null) {
                listener.onServerAdded(server);
            }

            Toast.makeText(getContext(), R.string.server_added, Toast.LENGTH_SHORT).show();
            dismiss();
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setView(view);
        builder.setCancelable(true);
        return builder.create();
    }

    private boolean isValidV2rayConfig(String config) {
        if (config.startsWith("vless://") || config.startsWith("vmess://") || config.startsWith("trojan://")) {
            return true;
        }
        if (config.startsWith("{")) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(config);
                return json.has("outbounds") || json.has("inbounds");
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
}
