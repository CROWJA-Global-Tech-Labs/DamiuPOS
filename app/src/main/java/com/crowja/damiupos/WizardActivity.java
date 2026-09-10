package com.crowja.damiupos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseBackupHelper;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncEngine;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.InputStream;

public class WizardActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_CONTACTS = 301;
    private static final int REQUEST_IMPORT_DB = 302;
    private static final int REQUEST_PICK_CONTACTS = 303;
    private static final int TOTAL_STEPS = 6;

    private ViewFlipper flipper;
    private TextView tvStepTitle, tvStepIndicator, tvImportStatus, tvProvisionStatus;
    private ProgressBar progress;
    private MaterialButton btnBack, btnNext, btnImportContacts;

    // QR provisioning during onboarding — scans the dashboard's {url,code} QR and
    // enrolls this fresh phone to its branch (reuses the online sync engine).
    private final ActivityResultLauncher<ScanOptions> qrLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) handleProvisionScan(result.getContents());
            });

    private TextInputEditText etDepotName, etDepotAddress, etDepotPhone;
    private TextInputEditText etDefaultOngkir, etHargaBotolGalon, etPointsPerAmount, etPointsReward;
    private TextInputEditText etFollowupDays, etStockAlert;
    private SwitchMaterial switchPoints;
    private View pointsConfigContainer;

    private SettingsDao settingsDao;

    private final String[] titles = new String[]{
            "Selamat Datang",
            "Info Depot",
            "Harga & Ongkir",
            "Sistem Poin",
            "Peringatan",
            "Impor Pelanggan"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wizard);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        settingsDao = new SettingsDao(dbHelper);

        flipper = findViewById(R.id.flipper);
        tvStepTitle = findViewById(R.id.tvStepTitle);
        tvStepIndicator = findViewById(R.id.tvStepIndicator);
        tvImportStatus = findViewById(R.id.tvImportStatus);
        progress = findViewById(R.id.progress);
        btnBack = findViewById(R.id.btnBack);
        btnNext = findViewById(R.id.btnNext);
        btnImportContacts = findViewById(R.id.btnImportContacts);

        etDepotName = findViewById(R.id.etDepotName);
        etDepotAddress = findViewById(R.id.etDepotAddress);
        etDepotPhone = findViewById(R.id.etDepotPhone);
        etDefaultOngkir = findViewById(R.id.etDefaultOngkir);
        etHargaBotolGalon = findViewById(R.id.etHargaBotolGalonWiz);
        etPointsPerAmount = findViewById(R.id.etPointsPerAmount);
        etPointsReward = findViewById(R.id.etPointsReward);
        switchPoints = findViewById(R.id.switchPoints);
        pointsConfigContainer = findViewById(R.id.pointsConfigContainer);
        etFollowupDays = findViewById(R.id.etFollowupDays);
        etStockAlert = findViewById(R.id.etStockAlert);

        // Pre-fill from existing settings (if any)
        etDepotName.setText(settingsDao.getDepotName());
        etDepotAddress.setText(settingsDao.getDepotAddress());
        etDepotPhone.setText(settingsDao.getDepotPhone());
        double ongkir = settingsDao.getDefaultOngkir();
        if (ongkir > 0) etDefaultOngkir.setText(String.valueOf((long) ongkir));
        etHargaBotolGalon.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));
        etPointsPerAmount.setText(String.valueOf((long) settingsDao.getPointsPerAmount()));
        etPointsReward.setText(String.valueOf(settingsDao.getPointsRewardThreshold()));
        etFollowupDays.setText(String.valueOf(settingsDao.getFollowupDays()));
        etStockAlert.setText(String.valueOf(settingsDao.getStockAlert()));
        boolean pointsEnabled = settingsDao.isPointsEnabled();
        switchPoints.setChecked(pointsEnabled);
        pointsConfigContainer.setVisibility(pointsEnabled ? View.VISIBLE : View.GONE);
        switchPoints.setOnCheckedChangeListener((b, checked) ->
                pointsConfigContainer.setVisibility(checked ? View.VISIBLE : View.GONE));

        btnBack.setOnClickListener(v -> goBack());
        btnNext.setOnClickListener(v -> goNext());
        btnImportContacts.setOnClickListener(v -> syncFromContacts());

        View btnImportDb = findViewById(R.id.btnImportDbWizard);
        if (btnImportDb != null) {
            btnImportDb.setOnClickListener(v -> startImportDb());
        }

        tvProvisionStatus = findViewById(R.id.tvProvisionStatus);
        View btnProvision = findViewById(R.id.btnProvisionWizard);
        if (btnProvision != null) {
            btnProvision.setOnClickListener(v -> {
                ScanOptions options = new ScanOptions();
                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
                options.setPrompt("Arahkan ke QR provisioning di dashboard (Perangkat → Provisioning)");
                options.setBeepEnabled(true);
                options.setOrientationLocked(false);
                qrLauncher.launch(options);
            });
        }

        updateStepUI();
    }

    @Override
    public void onBackPressed() {
        if (flipper.getDisplayedChild() > 0) {
            goBack();
        } else {
            // Don't allow exit from first step
            moveTaskToBack(true);
        }
    }

    private void goNext() {
        int current = flipper.getDisplayedChild();

        // Validate / save per step
        switch (current) {
            case 1: // Depot
                settingsDao.setDepotName(text(etDepotName));
                settingsDao.setDepotAddress(text(etDepotAddress));
                settingsDao.setDepotPhone(text(etDepotPhone));
                break;
            case 2: // Harga & Ongkir
                double ong = 0;
                try { ong = Double.parseDouble(text(etDefaultOngkir)); } catch (Exception ignored) {}
                settingsDao.setDefaultOngkir(ong);
                double hargaBotol = 35000;
                try { hargaBotol = Double.parseDouble(text(etHargaBotolGalon)); } catch (Exception ignored) {}
                if (hargaBotol <= 0) hargaBotol = 35000;
                settingsDao.setHargaBotolGalon(hargaBotol);
                break;
            case 3: // Points
                boolean enabled = switchPoints.isChecked();
                settingsDao.setPointsEnabled(enabled);
                if (enabled) {
                    double ppa = 10000;
                    try { ppa = Double.parseDouble(text(etPointsPerAmount)); } catch (Exception ignored) {}
                    if (ppa <= 0) ppa = 10000;
                    settingsDao.setPointsPerAmount(ppa);
                    int reward = 100;
                    try { reward = Integer.parseInt(text(etPointsReward)); } catch (Exception ignored) {}
                    if (reward <= 0) reward = 100;
                    settingsDao.setPointsRewardThreshold(reward);
                }
                break;
            case 4: // Peringatan (Follow Up + Stock Alert)
                int fuDays = 5;
                try { fuDays = Integer.parseInt(text(etFollowupDays)); } catch (Exception ignored) {}
                if (fuDays <= 0) fuDays = 5;
                settingsDao.setFollowupDays(fuDays);
                int stockAlert = 30;
                try { stockAlert = Integer.parseInt(text(etStockAlert)); } catch (Exception ignored) {}
                if (stockAlert < 0) stockAlert = 30;
                settingsDao.setStockAlert(stockAlert);
                break;
            case 5: // Final: finish wizard
                finishWizard();
                return;
        }

        if (current < TOTAL_STEPS - 1) {
            flipper.showNext();
            updateStepUI();
        }
    }

    private void goBack() {
        if (flipper.getDisplayedChild() > 0) {
            flipper.showPrevious();
            updateStepUI();
        }
    }

    private void updateStepUI() {
        int idx = flipper.getDisplayedChild();
        tvStepTitle.setText(titles[idx]);
        tvStepIndicator.setText("Langkah " + (idx + 1) + " dari " + TOTAL_STEPS);
        progress.setProgress((int) (((idx + 1) * 100f) / TOTAL_STEPS));
        btnBack.setVisibility(idx == 0 ? View.INVISIBLE : View.VISIBLE);
        if (idx == 0) {
            btnNext.setText("Mulai");
        } else if (idx == TOTAL_STEPS - 1) {
            btnNext.setText("Selesai");
        } else {
            btnNext.setText("Lanjut");
        }
    }

    private void finishWizard() {
        settingsDao.setWizardCompleted(true);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private String text(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private void syncFromContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSION_CONTACTS);
            return;
        }
        openContactPicker();
    }

    /**
     * Buka layar pemilihan kontak — user pilih sendiri kontak mana yang
     * mau dijadikan pelanggan, bukan auto-import semuanya.
     */
    private void openContactPicker() {
        startActivityForResult(
                new Intent(this, ContactPickerActivity.class),
                REQUEST_PICK_CONTACTS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openContactPicker();
            } else {
                tvImportStatus.setText("Izin kontak ditolak. Anda dapat melakukan impor nanti di menu Pelanggan.");
            }
        }
    }

    // ==========================================================================
    // Import database from backup (offered on the welcome step so users
    // migrating from another device can skip the rest of the wizard).
    // ==========================================================================

    private void startImportDb() {
        new AlertDialog.Builder(this)
                .setTitle("Pulihkan dari Backup")
                .setMessage("Pilih file .db hasil export dari HP lama. "
                        + "Setelah dipulihkan, wizard akan ditutup dan aplikasi "
                        + "akan terbuka dengan data lengkap dari backup.")
                .setPositiveButton("Pilih File", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        startActivityForResult(i, REQUEST_IMPORT_DB);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, "Tidak ada aplikasi untuk memilih file.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_DB && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) doImportDb(uri);
        } else if (requestCode == REQUEST_PICK_CONTACTS) {
            if (resultCode == RESULT_OK && data != null) {
                int imported = data.getIntExtra("imported", 0);
                String msg = imported + " pelanggan diimpor";
                tvImportStatus.setText(msg);
                btnImportContacts.setText("Impor Lagi");
            }
            // Kalau user cancel, tidak ubah status — biar bisa retry
        }
    }

    private void doImportDb(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(this, "Gagal membuka file backup", Toast.LENGTH_LONG).show();
                return;
            }
            DatabaseBackupHelper.importFrom(this, in);
            // Backup telah berisi semua pengaturan termasuk wizard_completed=true
            // (atau setidaknya semua data inti). Set wizard completed dan loncat ke MainActivity.
            settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
            settingsDao.setWizardCompleted(true);
            new AlertDialog.Builder(this)
                    .setTitle("Berhasil")
                    .setMessage("Data berhasil dipulihkan. Aplikasi akan dibuka.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> {
                        startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK));
                        finish();
                    })
                    .show();
        } catch (DatabaseBackupHelper.InvalidBackupException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Backup tidak valid")
                    .setMessage(e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Gagal Pulihkan")
                    .setMessage("Terjadi kesalahan:\n\n" + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    // ==========================================================================
    // Online provisioning via QR (offered on the welcome step so a new depot phone
    // can connect to its branch during onboarding — same flow as Settings → Sync).
    // ==========================================================================

    /**
     * Parse a scanned provisioning QR ({"url":...,"code":...} from the dashboard),
     * enroll this device on a background thread, then offer to open the app with the
     * branch's data synced. Falls back to treating the whole scan as the code and the
     * default server URL.
     */
    private void handleProvisionScan(String contents) {
        com.crowja.damiupos.sync.ProvisioningQr qr =
                com.crowja.damiupos.sync.ProvisioningQr.parse(contents);
        if (qr == null) {
            if (tvProvisionStatus != null)
                tvProvisionStatus.setText("QR ditolak: alamatnya bukan HTTPS.");
            return;
        }
        // Server ASING harus dikonfirmasi DULU sambil ditampilkan hostnya — QR bisa dicetak siapa
        // pun. Tanpa gerbang ini, memindai satu gambar sudah cukup membuat HP mendaftar ke server
        // penyerang lalu mengunggah seluruh data cabang ke sana pada sinkron pertama.
        if (!qr.trustedHost) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("⚠ Server tidak dikenal")
                    .setMessage("QR ini menyuruh aplikasi terhubung ke:\n\n" + qr.host()
                            + "\n\nBukan server resmi (" + com.crowja.damiupos.sync.ProvisioningQr.defaultHost()
                            + "). Melanjutkan berarti data HP ini akan dikirim ke sana.")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Saya paham, lanjutkan", (d, w) -> startProvisioning(qr.url, qr.code))
                    .show();
            return;
        }
        startProvisioning(qr.url.isEmpty() ? SyncSettings.DEFAULT_BASE_URL : qr.url, qr.code);
    }

    private void startProvisioning(String url, String code) {
        if (tvProvisionStatus != null) tvProvisionStatus.setText("Menghubungkan ke dashboard…");
        final String baseUrl = url, credential = code.trim();
        final String deviceName = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim();
        final SyncEngine engine = new SyncEngine(this);
        new Thread(() -> {
            SyncEngine.Result r = engine.enroll(baseUrl, credential, deviceName);
            // Pull-only: jangan otomatis push data lokal HP ini ke server saat baru diprovisioning
            // (lihat doc SyncEngine.pullOnly) — cukup tarik data cabang supaya wizard bisa lanjut.
            if (r.ok) engine.pullOnly();
            runOnUiThread(() -> {
                if (r.ok) {
                    SyncScheduler.schedulePeriodic(getApplicationContext());
                    if (tvProvisionStatus != null)
                        tvProvisionStatus.setText("Terhubung ke cabang "
                                + engine.settings().getBranchName());
                    showProvisionSuccess(engine.settings().getBranchName());
                } else {
                    if (tvProvisionStatus != null)
                        tvProvisionStatus.setText("Gagal: " + r.error);
                    new AlertDialog.Builder(this)
                            .setTitle("Gagal Hubungkan")
                            .setMessage("Tidak bisa mendaftar ke dashboard:\n\n" + r.error
                                    + "\n\nPastikan kode masih berlaku (15 menit) dan ada koneksi internet.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    private void showProvisionSuccess(String branchName) {
        new AlertDialog.Builder(this)
                .setTitle("Perangkat Terhubung")
                .setMessage("Berhasil terhubung ke cabang \"" + branchName + "\". "
                        + "Data cabang akan tersinkron otomatis. Buka aplikasi sekarang, "
                        + "atau lanjutkan setup untuk mengatur info depot di HP ini.")
                .setCancelable(false)
                .setPositiveButton("Buka Aplikasi", (d, w) -> {
                    settingsDao.setWizardCompleted(true);
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_NEW_TASK));
                    finish();
                })
                .setNegativeButton("Lanjutkan Setup", null)
                .show();
    }
}
