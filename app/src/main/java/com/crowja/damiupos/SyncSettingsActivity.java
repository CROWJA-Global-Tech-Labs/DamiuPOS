package com.crowja.damiupos;

import android.os.Bundle;
import android.os.Build;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.sync.SyncEngine;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Hubungkan perangkat ke server DAMIU POS (online sync), lalu sinkronkan.
 * Enrollment & sync berjalan di thread latar; UI hanya menampilkan status.
 */
public class SyncSettingsActivity extends AppCompatActivity {

    private SyncEngine engine;
    private TextView tvStatus;
    private TextInputEditText etBaseUrl, etEnrollKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        engine = new SyncEngine(this);
        tvStatus = findViewById(R.id.tvStatus);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etEnrollKey = findViewById(R.id.etEnrollKey);

        SyncSettings cfg = engine.settings();
        if (!cfg.getBaseUrl().isEmpty()) etBaseUrl.setText(cfg.getBaseUrl());

        ((MaterialButton) findViewById(R.id.btnConnect)).setOnClickListener(v -> doConnect());
        ((MaterialButton) findViewById(R.id.btnSyncNow)).setOnClickListener(v -> doSyncNow());

        com.google.android.material.checkbox.MaterialCheckBox cbLocation = findViewById(R.id.cbLocation);
        cbLocation.setChecked(cfg.isLocationTrackingEnabled());
        cbLocation.setOnCheckedChangeListener((b, checked) -> {
            cfg.setLocationTrackingEnabled(checked);
            // Disabling stops immediately; enabling takes effect on next clock-in
            // (MainActivity requests the location permission + starts the service).
            if (!checked) LocationService.stop(getApplicationContext());
        });
        ((MaterialButton) findViewById(R.id.btnDisconnect)).setOnClickListener(v -> {
            cfg.clear();
            SyncScheduler.cancelAll(this);
            refreshStatus();
            Toast.makeText(this, "Perangkat diputuskan", Toast.LENGTH_SHORT).show();
        });

        refreshStatus();
    }

    private void doConnect() {
        String baseUrl = text(etBaseUrl);
        String key = text(etEnrollKey);
        if (baseUrl.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Isi URL server dan kunci pendaftaran", Toast.LENGTH_SHORT).show();
            return;
        }
        tvStatus.setText("Menghubungkan…");
        String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        new Thread(() -> {
            SyncEngine.Result r = engine.enroll(baseUrl, key, deviceName);
            runOnUiThread(() -> {
                if (r.ok) {
                    SyncScheduler.schedulePeriodic(getApplicationContext());
                    Toast.makeText(this, "Terhubung ke cabang "
                            + engine.settings().getBranchName(), Toast.LENGTH_LONG).show();
                    doSyncNow();
                } else {
                    Toast.makeText(this, "Gagal: " + r.error, Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        }).start();
    }

    private void doSyncNow() {
        if (!engine.settings().isEnrolled()) {
            Toast.makeText(this, "Hubungkan dulu ke server", Toast.LENGTH_SHORT).show();
            return;
        }
        tvStatus.setText("Menyinkronkan…");
        new Thread(() -> {
            SyncEngine.Result r = engine.sync();
            runOnUiThread(() -> {
                Toast.makeText(this, r.ok
                        ? ("Sinkron: " + r.pushed + " dikirim, " + r.pulled + " diterima")
                        : ("Gagal sinkron: " + r.error), Toast.LENGTH_LONG).show();
                refreshStatus();
            });
        }).start();
    }

    private void refreshStatus() {
        SyncSettings cfg = engine.settings();
        StringBuilder sb = new StringBuilder();
        if (cfg.isEnrolled()) {
            sb.append("Terhubung ke cabang: ").append(cfg.getBranchName())
                    .append(" (").append(cfg.getBranchCode()).append(")\n");
            sb.append("Server: ").append(cfg.getBaseUrl()).append("\n");
            String last = cfg.getLastSyncAt();
            sb.append("Sinkron terakhir: ").append(last.isEmpty() ? "belum pernah" : last);
        } else {
            sb.append("Belum terhubung. Masukkan URL server dan kunci pendaftaran cabang, "
                    + "lalu tekan Hubungkan.");
        }
        tvStatus.setText(sb.toString());
    }

    private String text(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }
}
