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

        // Pelacakan lokasi WAJIB aktif selama staff bekerja — tidak boleh dimatikan dari HP.
        // Toggle disembunyikan & dipaksa ON (staf harus terpantau sepanjang sesi kerja login).
        cfg.setLocationTrackingEnabled(true);
        com.google.android.material.checkbox.MaterialCheckBox cbLocation = findViewById(R.id.cbLocation);
        cbLocation.setChecked(true);
        cbLocation.setVisibility(android.view.View.GONE);
        ((MaterialButton) findViewById(R.id.btnDisconnect)).setOnClickListener(v -> confirmDisconnect());

        refreshStatus();
    }

    /**
     * "Putuskan Provisioning": sebelum akses diputus, SEMUA data perangkat (pelanggan lengkap
     * dengan foto + koordinat, transaksi, pengeluaran) dikirim ke web lalu diarsipkan di sana
     * (menu Arsip Data — bisa dipulihkan). Kalau server tak terjangkau, user bisa memilih
     * putus paksa TANPA pengarsipan (data hanya tersisa di HP ini).
     */
    private void confirmDisconnect() {
        SyncSettings cfg = engine.settings();
        if (!cfg.isEnrolled()) {   // belum pernah terhubung → cukup bersihkan konfigurasi lokal
            cfg.clear();
            SyncScheduler.cancelAll(this);
            com.crowja.damiupos.sync.ServiceRestartReceiver.cancel(this);
            refreshStatus();
            Toast.makeText(this, "Perangkat diputuskan", Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Putuskan Provisioning?")
                .setMessage("Semua data perangkat ini — pelanggan (lengkap dengan foto & koordinat), "
                        + "transaksi, dan pengeluaran — akan dikirim ke web lalu DIARSIPKAN di sana "
                        + "(bisa dipulihkan lewat menu Arsip Data). Setelah itu akses perangkat dicabut "
                        + "dan HP harus provisioning ulang untuk terhubung lagi.\n\nLanjutkan?")
                .setPositiveButton("Kirim & Putuskan", (d, w) -> retireAndDisconnect())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void retireAndDisconnect() {
        android.app.ProgressDialog progress = android.app.ProgressDialog.show(this,
                "Mengarsipkan ke web…",
                "Mengunggah foto, transaksi & pengeluaran lalu mengarsipkan. Jangan tutup aplikasi.",
                true, false);
        new Thread(() -> {
            SyncEngine.Result r = engine.retireAndArchive();
            runOnUiThread(() -> {
                try { progress.dismiss(); } catch (Throwable ignored) {}
                if (r.ok) {
                    finishDisconnect();
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Perangkat diputuskan")
                            .setMessage(r.pushed + " data perangkat diarsipkan di web (menu Arsip Data). "
                                    + "Log absensi & selfie tetap tersimpan sebagai rekam HR.")
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    // Gagal (jaringan/server) → JANGAN putus otomatis; tawarkan putus paksa.
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Gagal mengarsipkan")
                            .setMessage("Data belum terkirim ke web: " + r.error
                                    + "\n\nCoba lagi saat jaringan stabil, atau putuskan PAKSA tanpa "
                                    + "pengarsipan (data hanya tersisa di HP ini).")
                            .setPositiveButton("Coba Lagi", (d, w) -> retireAndDisconnect())
                            .setNegativeButton("Putus Paksa", (d, w) -> {
                                finishDisconnect();
                                Toast.makeText(this, "Perangkat diputuskan TANPA pengarsipan", Toast.LENGTH_LONG).show();
                            })
                            .setNeutralButton("Batal", null)
                            .show();
                }
            });
        }).start();
    }

    /** Bersihkan enrolment lokal + hentikan seluruh kerja latar (dipanggil setelah retire sukses / paksa). */
    private void finishDisconnect() {
        engine.settings().clear();
        SyncScheduler.cancelAll(this);
        try { com.crowja.damiupos.sync.ServiceRestartReceiver.cancel(this); } catch (Throwable ignored) {}
        try { LocationService.stop(getApplicationContext()); } catch (Throwable ignored) {}
        refreshStatus();
    }

    /**
     * Parse a scanned provisioning QR ({"url":...,"code":...} from the dashboard), fill the
     * fields and connect. Falls back to treating the whole scanned text as the code.
     */
    private void handleScan(String contents) {
        com.crowja.damiupos.sync.ProvisioningQr qr =
                com.crowja.damiupos.sync.ProvisioningQr.parse(contents);
        if (qr == null) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("QR tidak aman")
                    .setMessage("QR ini mengarahkan aplikasi ke alamat yang bukan HTTPS. "
                            + "Penyambungan dibatalkan.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (!qr.code.isEmpty()) etProvisioningCode.setText(qr.code);

        // Server BAWAAN (atau QR tanpa url) → lanjut seperti biasa.
        if (qr.trustedHost) {
            if (!qr.url.isEmpty()) etBaseUrl.setText(qr.url);
            Toast.makeText(this, "QR terbaca — menghubungkan…", Toast.LENGTH_SHORT).show();
            doConnect();
            return;
        }

        // Server ASING: WAJIB dikonfirmasi sambil menampilkan hostnya. QR bisa dicetak siapa pun
        // dan ditempel di depot; menyambung diam-diam berarti seluruh isi basis data HP ini
        // terdorong ke server orang itu pada sinkron pertama.
        new android.app.AlertDialog.Builder(this)
                .setTitle("⚠ Server tidak dikenal")
                .setMessage("QR ini menyuruh aplikasi terhubung ke:\n\n" + qr.host()
                        + "\n\nBukan server resmi (" + com.crowja.damiupos.sync.ProvisioningQr.defaultHost()
                        + "). Melanjutkan berarti SELURUH data di HP ini (pelanggan, transaksi, absensi) "
                        + "akan dikirim ke sana.\n\nLanjutkan hanya jika Anda sendiri yang menyiapkan server itu.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Saya paham, lanjutkan", (d, w) -> {
                    etBaseUrl.setText(qr.url);
                    doConnect();
                })
                .show();
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
