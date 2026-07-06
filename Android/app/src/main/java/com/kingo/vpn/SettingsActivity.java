package com.kingo.vpn;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "kingo_vpn_settings";
    private static final String KEY_SPLIT_TUNNELING = "split_tunneling_enabled";

    private SwitchMaterial switchSplitTunneling;
    private LinearLayout btnExcludeApps, btnIncludeApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        initViews();
        loadSettings();
        setupListeners();
    }

    private void initViews() {
        ImageView ivBack = findViewById(R.id.ivBack);
        ivBack.setOnClickListener(v -> finish());

        switchSplitTunneling = findViewById(R.id.switchSplitTunneling);
        btnExcludeApps = findViewById(R.id.btnExcludeApps);
        btnIncludeApps = findViewById(R.id.btnIncludeApps);

        // About section links
        LinearLayout btnGitHub = findViewById(R.id.btnGitHub);
        LinearLayout btnTelegram = findViewById(R.id.btnTelegram);

        btnGitHub.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/kingowow/Kingo-vpn"));
            startActivity(intent);
        });

        btnTelegram.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://t.me/kingo_team"));
            startActivity(intent);
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean splitEnabled = prefs.getBoolean(KEY_SPLIT_TUNNELING, false);

        switchSplitTunneling.setChecked(splitEnabled);
        updateSplitTunnelingVisibility(splitEnabled);
    }

    private void setupListeners() {
        switchSplitTunneling.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_SPLIT_TUNNELING, isChecked).apply();
            updateSplitTunnelingVisibility(isChecked);
        });

        btnExcludeApps.setOnClickListener(v ->
                Toast.makeText(this, "App exclusion coming soon", Toast.LENGTH_SHORT).show());
        btnIncludeApps.setOnClickListener(v ->
                Toast.makeText(this, "App inclusion coming soon", Toast.LENGTH_SHORT).show());
    }

    private void updateSplitTunnelingVisibility(boolean enabled) {
        int visibility = enabled ? View.VISIBLE : View.GONE;
        btnExcludeApps.setVisibility(visibility);
        btnIncludeApps.setVisibility(visibility);
    }
}
