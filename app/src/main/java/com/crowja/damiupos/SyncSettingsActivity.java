package com.crowja.damiupos;

import android.os.Bundle;
import android.os.Build;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

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
    private TextInputEditText etBaseUrl, etEnrollKey, etProvisioningCode;

    // QR scanner for provisioning — fills URL + code from the dashboard QR, then connects.
    private final ActivityResultLauncher<ScanOptions> qrLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) handleScan(result.getContents());
            });

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
        etProvisioningCode = findViewById(R.id.etProvisioningCode);

        SyncSettings cfg = engine.settings();
        // Pre-fill the server URL so staff usually only type the provisioning code.
        etBaseUrl.setText(cfg.getBaseUrl().isEmpty() ? SyncSettings.DEFAULT_BASE_URL : cfg.getBaseUrl());

        ((MaterialButton) findViewById(R.id.btnConnect)).setOnClickListener(v -> doConnect());
        ((MaterialButton) findViewById(R.id.btnSyncNow)).setOnClickListener(v -> doSyncNow());

        MaterialButton btnScan = findViewById(R.id.btnScanQr);
        if (btnScan != null) btnScan.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Arahkan ke QR provisioning di dashboard (Kelola → Provisioning)");
            options.setBeepEnabled(true);
            options.setOrientationLocked(true);                       // stay portrait
            options.setCaptureActivity(PortraitCaptureActivity.class);
            qrLauncher.launch(options);
        });

        com.google.android.material.checkbox.MaterialCheckBox cbLocation = findViewById(R.id.cbLocation);
        cbLocation.setChecked(cfg.isLocationTrackingEnabled());
        cbLocation.setOnCheckedChangeListener((b, checked) -> {
            cfg.setLocationTrackingEnabled(checked);
            // Disabling stops GPS immediately but KEEPS the service polling (sync stays
            // on in the background); enabling takes effect on the next clock-in.
            if (!checked) LocationService.pollOnly(getApplicationContext());
        });
        ((MaterialButton) findViewById(R.id.btnDisconnect)).setOnClickListener(v -> {
            cfg.clear();
            SyncScheduler.cancelAll(this);
            refreshStatus();
            Toast.makeText(this, "Perangkat diputuskan", Toast.LENGTH_SHORT).show();
        });

        refreshStatus();
    }

    /**
     * Parse a scanned provisioning QR ({"url":...,"code":...} from the dashboard), fill the
     * fields and connect. Falls back to treating the whole scanned text as the code.
     */
    private void handleScan(String contents) {
        String url = "", code = "";
        try {
            org.json.JSONObject j = new org.json.JSONObject(contents);
            url = j.optString("url", "");
            code = j.optString("code", "");
        } catch (org.json.JSONException ignored) {}
        if (code.isEmpty()) code = contents;          // plain-text QR = the code itself
        if (!url.isEmpty()) etBaseUrl.setText(url);
        if (!code.isEmpty()) etProvisioningCode.setText(code);
        Toast.makeText(this, "QR terbaca — menghubungkan…", Toast.LENGTH_SHORT).show();
        doConnect();
    }

    private void doConnect() {
        String baseUrl = text(etBaseUrl);
        // A provisioning code is the primary path; the long-lived enroll key is the fallback.
        String code = text(etProvisioningCode);
        String credential = !code.isEmpty() ? code : text(etEnrollKey);
        if (baseUrl.isEmpty() || credential.isEmpty()) {
            Toast.makeText(this, "Masukkan kode provisioning (atau kunci pendaftaran) dan URL server",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        tvStatus.setText("Menghubungkan…");
        String deviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        new Thread(() -> {
            SyncEngine.Result r = engine.enroll(baseUrl, credential, deviceName);
            runOnUiThread(() -> {
                if (r.ok) {
                    SyncScheduler.schedulePeriodic(getApplicationContext());
                    if (etProvisioningCode != null) etProvisioningCode.setText("");
                    Toast.makeText(this, "Terhubung ke cabang "
                            + engine.settings().getBranchName(), Toast.LENGTH_LONG).show();
                    promptImportSelection();
                } else {
                    Toast.makeText(this, "Gagal: " + r.error, Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        }).start();
    }

    /**
     * Right after provisioning, ask which existing on-device data to import to the server.
     * The selected categories are seeded via the reviewable import path; everything else stays
     * on the phone only. Default = all checked.
     */
    private void promptImportSelection() {
        final String[] labels = {"Pelanggan", "Karyawan", "Transaksi", "Pengeluaran", "Absensi", "Jenis Galon"};
        final boolean[] checked = {true, true, true, true, true, true};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Impor data dari perangkat ini")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setCancelable(false)
                .setNegativeButton("Lewati", (d, w) -> runProvisioningImport(new boolean[6]))   // import nothing
                .setPositiveButton("Impor", (d, w) -> runProvisioningImport(checked))
                .show();
    }

    /** Map the checklist to sync entities (incl. dependent data) and import on a background thread. */
    private void runProvisioningImport(boolean[] checked) {
        final java.util.Set<String> entities = new java.util.HashSet<>();
        if (checked[0]) java.util.Collections.addAll(entities, "customers", "reseller_rates", "reseller_withdrawals");
        if (checked[1]) java.util.Collections.addAll(entities, "staff", "salary_configs", "salary_items");
        if (checked[2]) entities.add("transactions");
        if (checked[3]) entities.add("expenses");
        if (checked[4]) entities.add("attendance");
        if (checked[5]) entities.add("products");

        tvStatus.setText("Mengimpor data ke server…");
        new Thread(() -> {
            SyncEngine.Result r = engine.provisioningImport(entities);
            runOnUiThread(() -> {
                if (r.ok) {
                    Toast.makeText(this, "Impor selesai — " + r.pushed + " data terkirim"
                            + (r.pulled > 0 ? (" (" + r.pulled + " konflik untuk ditinjau di dashboard)") : ""),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Gagal impor: " + r.error, Toast.LENGTH_LONG).show();
                }
                doSyncNow();   // pull dashboard config etc. (local backlog already handled)
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
            sb.append("Belum terhubung. Masukkan kode provisioning dari admin "
                    + "(Dashboard → Kelola → Provisioning), lalu tekan Hubungkan.");
        }
        tvStatus.setText(sb.toString());
    }

    private String text(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }
}
