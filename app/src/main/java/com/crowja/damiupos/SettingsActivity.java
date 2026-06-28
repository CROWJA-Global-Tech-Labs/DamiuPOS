package com.crowja.damiupos;

import android.content.ComponentName;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseBackupHelper;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.demo.DemoDataHelper;
import com.crowja.damiupos.model.OrderInbox;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.util.ReadOnlyForms;
import com.crowja.damiupos.wa.OrderParseService;
import com.crowja.damiupos.wa.WaListenerService;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.LinearLayout.LayoutParams;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_EXPORT_DB = 4101;
    private static final int REQUEST_IMPORT_DB = 4102;
    private static final int REQUEST_PICK_RINGTONE = 4103;

    // Restore mode chosen before the file picker opens.
    private static final int RESTORE_FULL = 0;            // replace the whole database
    private static final int RESTORE_MERGE_TRX = 1;       // add transactions for existing customers
    private static final int RESTORE_PICK_CUSTOMERS = 2;  // pick customers to restore + their transactions
    private int restoreMode = RESTORE_FULL;

    private TextInputEditText etDefaultOngkir, etPointsPerAmount, etPointsReward;
    private TextInputEditText etDepotName, etDepotAddress, etDepotPhone;
    private TextInputEditText etFollowupDays, etStockAlert, etFollowUpTemplate;
    private TextInputEditText etHargaBotolGalon, etResellerKomisi;
    private TextInputEditText etClaudeKey, etReplyTemplate, etAutoArchiveHours;
    private SwitchMaterial switchPoints, switchWaAutoDetect, switchWaAutoSend;
    private LinearLayout pointsConfigContainer, waAutoDetectConfig, waAutoSendConfig;
    private RadioGroup rgWaParseMode;
    private TextView tvNotifAccessStatus, tvRingtoneName, tvClaudeKeyStatus,
            tvClaudeLastStatus;
    private SettingsDao settingsDao;
    private UserDao userDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        userDao = new UserDao(DatabaseHelper.getInstance(this));

        // Saat fitur multi user aktif, Pengaturan hanya untuk Admin.
        // Staf yang membuka harus memasukkan PIN admin (atau batal → keluar).
        enforceAdminGate();
        etDefaultOngkir = findViewById(R.id.etDefaultOngkir);
        etPointsPerAmount = findViewById(R.id.etPointsPerAmount);
        etPointsReward = findViewById(R.id.etPointsReward);
        switchPoints = findViewById(R.id.switchPoints);
        pointsConfigContainer = findViewById(R.id.pointsConfigContainer);
        etDepotName = findViewById(R.id.etDepotName);
        etDepotAddress = findViewById(R.id.etDepotAddress);
        etDepotPhone = findViewById(R.id.etDepotPhone);
        // Logo depot (read-only di HP): disinkron dari web Konfigurasi, ditampilkan dari cache lokal +
        // diunduh ulang bila berubah.
        com.crowja.damiupos.util.DepotLogo.loadInto(findViewById(R.id.ivDepotLogo));
        etFollowupDays = findViewById(R.id.etFollowupDays);
        etStockAlert = findViewById(R.id.etStockAlert);
        etHargaBotolGalon = findViewById(R.id.etHargaBotolGalon);
        etResellerKomisi = findViewById(R.id.etResellerKomisi);
        etFollowUpTemplate = findViewById(R.id.etFollowUpTemplate);

        etResellerKomisi.setText(String.valueOf((long) settingsDao.getResellerKomisi()));
        etFollowUpTemplate.setText(settingsDao.getFollowUpTemplate());
        etFollowupDays.setText(String.valueOf(settingsDao.getFollowupDays()));
        etStockAlert.setText(String.valueOf(settingsDao.getStockAlert()));
        etHargaBotolGalon.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));
        etDepotName.setText(settingsDao.getDepotName());
        etDepotAddress.setText(settingsDao.getDepotAddress());
        etDepotPhone.setText(settingsDao.getDepotPhone());

        // Load current values
        double currentOngkir = settingsDao.getDefaultOngkir();
        if (currentOngkir > 0) {
            etDefaultOngkir.setText(String.valueOf((long) currentOngkir));
        }
        etPointsPerAmount.setText(String.valueOf((long) settingsDao.getPointsPerAmount()));
        etPointsReward.setText(String.valueOf(settingsDao.getPointsRewardThreshold()));
        boolean pointsEnabled = settingsDao.isPointsEnabled();
        switchPoints.setChecked(pointsEnabled);
        pointsConfigContainer.setVisibility(pointsEnabled ? View.VISIBLE : View.GONE);

        switchPoints.setOnCheckedChangeListener((buttonView, isChecked) ->
                pointsConfigContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE));

        // ----- WhatsApp Auto-Detect -----
        switchWaAutoDetect = findViewById(R.id.switchWaAutoDetect);
        waAutoDetectConfig = findViewById(R.id.waAutoDetectConfig);
        rgWaParseMode = findViewById(R.id.rgWaParseMode);
        etClaudeKey = findViewById(R.id.etClaudeKey);
        tvNotifAccessStatus = findViewById(R.id.tvNotifAccessStatus);

        boolean waEnabled = settingsDao.isWaAutoDetectEnabled();
        switchWaAutoDetect.setChecked(waEnabled);
        waAutoDetectConfig.setVisibility(waEnabled ? View.VISIBLE : View.GONE);
        switchWaAutoDetect.setOnCheckedChangeListener((b, checked) -> {
            waAutoDetectConfig.setVisibility(checked ? View.VISIBLE : View.GONE);
            settingsDao.setWaAutoDetectEnabled(checked);
            if (checked) updateNotifAccessStatus();
        });

        // Multi user & absensi → pindah ke layar tersendiri (menu Karyawan).
        findViewById(R.id.cardMultiUser).setOnClickListener(v ->
                startActivity(new Intent(this, AttendanceSettingsActivity.class)));

        // Mode parsing
        String mode = settingsDao.getWaParseMode();
        if (SettingsDao.PARSE_MODE_DEFAULT.equals(mode)) {
            rgWaParseMode.check(R.id.rbParseDefault);
        } else if (SettingsDao.PARSE_MODE_AI.equals(mode)) {
            rgWaParseMode.check(R.id.rbParseAi);
        } else {
            rgWaParseMode.check(R.id.rbParseHybrid);
        }
        rgWaParseMode.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.rbParseDefault) settingsDao.setWaParseMode(SettingsDao.PARSE_MODE_DEFAULT);
            else if (id == R.id.rbParseAi) settingsDao.setWaParseMode(SettingsDao.PARSE_MODE_AI);
            else settingsDao.setWaParseMode(SettingsDao.PARSE_MODE_HYBRID);
        });

        // Claude API key — auto-save tiap karakter berubah, plus indikator
        // visual jelas supaya user tahu key sudah benar2 ter-save.
        tvClaudeKeyStatus = findViewById(R.id.tvClaudeKeyStatus);
        tvClaudeLastStatus = findViewById(R.id.tvClaudeLastStatus);
        etClaudeKey.setText(settingsDao.getClaudeApiKey());
        updateClaudeKeyStatus();
        updateClaudeLastStatus();
        etClaudeKey.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String key = s.toString().trim();
                settingsDao.setClaudeApiKey(key);
                updateClaudeKeyStatus();
            }
        });

        findViewById(R.id.btnGrantNotifAccess).setOnClickListener(v -> {
            try {
                startActivity(new Intent(
                        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Tidak bisa buka pengaturan notifikasi: "
                        + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        if (waEnabled) updateNotifAccessStatus();

        // --- Auto-Kirim Struk WhatsApp (AccessibilityService) ---
        switchWaAutoSend = findViewById(R.id.switchWaAutoSend);
        waAutoSendConfig = findViewById(R.id.waAutoSendConfig);
        boolean autoSendOn = com.crowja.damiupos.wa.WaAutoSendService.isEnabled(this);
        switchWaAutoSend.setChecked(autoSendOn);
        waAutoSendConfig.setVisibility(autoSendOn ? View.VISIBLE : View.GONE);
        switchWaAutoSend.setOnCheckedChangeListener((b, checked) -> {
            com.crowja.damiupos.wa.WaAutoSendService.setEnabled(this, checked);
            waAutoSendConfig.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) {
                updateAccessibilityStatus();
                if (!com.crowja.damiupos.wa.WaAutoSendService.isAccessibilityGranted(this)) {
                    Toast.makeText(this,
                            "Aktifkan layanan Aksesibilitas \"DAMIU POS — Auto-Kirim Struk WA\" agar auto-kirim bekerja.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        findViewById(R.id.btnGrantAccessibility).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Tidak bisa buka pengaturan Aksesibilitas: "
                        + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        if (autoSendOn) updateAccessibilityStatus();

        // Ringtone picker
        tvRingtoneName = findViewById(R.id.tvRingtoneName);
        updateRingtoneLabel();
        findViewById(R.id.btnPickRingtone).setOnClickListener(v -> startRingtonePicker());

        // Reply template
        etReplyTemplate = findViewById(R.id.etReplyTemplate);
        etReplyTemplate.setText(settingsDao.getWaReplyTemplate());

        // Auto-archive hours
        etAutoArchiveHours = findViewById(R.id.etAutoArchiveHours);
        etAutoArchiveHours.setText(String.valueOf(settingsDao.getWaAutoArchiveHours()));

        findViewById(R.id.btnViewArchive).setOnClickListener(v ->
                startActivity(new Intent(this, OrderInboxActivity.class)
                        .putExtra(OrderInboxActivity.EXTRA_SHOW_ARCHIVE, true)));

        View cardUpgrade = findViewById(R.id.cardUpgradePro);
        if (cardUpgrade != null) {
            cardUpgrade.setOnClickListener(v ->
                    startActivity(new Intent(this, UpgradeActivity.class)));
            // Update card text jika sudah Pro
            android.widget.TextView tvTitle = findViewById(R.id.tvUpgradeTitle);
            android.widget.TextView tvSub = findViewById(R.id.tvUpgradeSubtitle);
            if (settingsDao.isProActive()) {
                if (tvTitle != null) tvTitle.setText("DAMIU POS Pro Aktif");
                if (tvSub != null) tvSub.setText("Kelola langganan");
            }
        }

        findViewById(R.id.btnExportDb).setOnClickListener(v -> startExportDb());
        findViewById(R.id.btnImportDb).setOnClickListener(v -> startImportDb());

        // Pengaturan bisnis dikelola admin di dashboard web (tersinkron ke perangkat).
        // Di sini hanya-baca; yang tetap bisa diubah: izin notifikasi, nada dering,
        // dan Backup/Restore database.
        ReadOnlyForms.disable(
                etDefaultOngkir, etPointsPerAmount, etPointsReward, switchPoints,
                etDepotName, etDepotAddress, etDepotPhone,
                etFollowupDays, etStockAlert, etHargaBotolGalon, etResellerKomisi,
                etFollowUpTemplate, etReplyTemplate, etAutoArchiveHours,
                switchWaAutoDetect);
        // Disable AI parser key + parse-mode radios (children of the WA config block).
        ReadOnlyForms.disableTree(waAutoDetectConfig);
    }

    // ==========================================================================
    // Database backup & restore — uses Storage Access Framework so the file
    // ends up wherever the user picks (Downloads, Drive, USB drive, etc.).
    // ==========================================================================

    private void startExportDb() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(DatabaseBackupHelper.BACKUP_MIME);
        i.putExtra(Intent.EXTRA_TITLE, DatabaseBackupHelper.defaultBackupFileName());
        try {
            startActivityForResult(i, REQUEST_EXPORT_DB);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk menyimpan file.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startImportDb() {
        new AlertDialog.Builder(this)
                .setTitle("Pulihkan Backup")
                .setItems(new CharSequence[]{
                        "Restore Semua (ganti seluruh data)",
                        "Restore Transaksi (pelanggan eksisting)",
                        "Restore: Pilih Konsumen"
                }, (d, which) -> {
                    restoreMode = which;   // 0=full, 1=merge trx, 2=pick customers
                    String title, msg;
                    if (restoreMode == RESTORE_FULL) {
                        title = "Restore Semua";
                        msg = "Semua data saat ini (pelanggan, produk, transaksi, pengaturan) akan "
                              + "DIGANTI dengan isi file backup.\n\nLanjutkan?";
                    } else if (restoreMode == RESTORE_MERGE_TRX) {
                        title = "Restore Transaksi";
                        msg = "Hanya transaksi milik pelanggan yang SUDAH ADA di perangkat ini yang "
                              + "akan ditambahkan dari backup. Pelanggan, produk, dan pengaturan tidak "
                              + "diubah. Transaksi duplikat dilewati.\n\nLanjutkan?";
                    } else {
                        title = "Pilih Konsumen";
                        msg = "Pilih file backup, lalu pilih konsumen yang ingin dipulihkan. "
                              + "Konsumen yang belum ada akan ditambahkan beserta transaksinya. "
                              + "Data lain tidak diubah.\n\nLanjutkan?";
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton("Pilih File", (dd, ww) -> launchImportPicker())
                            .setNegativeButton("Batal", null)
                            .show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void launchImportPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // Beberapa file picker tidak menampilkan .db di filter strict; pakai */*.
        i.setType("*/*");
        try {
            startActivityForResult(i, REQUEST_IMPORT_DB);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk memilih file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_PICK_RINGTONE) {
            Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            settingsDao.setWaRingtoneUri(picked != null ? picked.toString() : "");
            updateRingtoneLabel();
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == REQUEST_EXPORT_DB) {
            doExportDb(uri);
        } else if (requestCode == REQUEST_IMPORT_DB) {
            doImportDb(uri);
        }
    }

    private void startRingtonePicker() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pilih Nada Dering Pesanan WA");
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
        String existing = settingsDao.getWaRingtoneUri();
        if (existing != null && !existing.isEmpty()) {
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existing));
        }
        try {
            startActivityForResult(i, REQUEST_PICK_RINGTONE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Tidak ada aplikasi pilih nada dering",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void updateRingtoneLabel() {
        if (tvRingtoneName == null) return;
        String uriStr = settingsDao.getWaRingtoneUri();
        Uri uri = (uriStr != null && !uriStr.isEmpty())
                ? Uri.parse(uriStr)
                : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        String name = "Default sistem";
        if (uriStr != null && !uriStr.isEmpty() && uri != null) {
            try {
                Ringtone r = RingtoneManager.getRingtone(this, uri);
                if (r != null) {
                    String t = r.getTitle(this);
                    if (t != null && !t.isEmpty()) name = t;
                }
            } catch (Exception ignored) {}
        }
        tvRingtoneName.setText("Nada saat ini: " + name);
    }

    private void doExportDb(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                Toast.makeText(this, "Gagal membuka file untuk ditulis",
                        Toast.LENGTH_LONG).show();
                return;
            }
            DatabaseBackupHelper.exportTo(this, out);
            Toast.makeText(this, "Database berhasil dicadangkan",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal export: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void doImportDb(Uri uri) {
        if (restoreMode == RESTORE_MERGE_TRX) {
            doMergeTransactions(uri);
            return;
        }
        if (restoreMode == RESTORE_PICK_CUSTOMERS) {
            doPickCustomers(uri);
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(this, "Gagal membuka file backup",
                        Toast.LENGTH_LONG).show();
                return;
            }
            DatabaseBackupHelper.importFrom(this, in);
            // Reset cached DAO inside this Activity so the success dialog reflects new data.
            settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
            new AlertDialog.Builder(this)
                    .setTitle("Berhasil")
                    .setMessage("Database berhasil dipulihkan. Aplikasi akan dimulai ulang.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (d, w) -> restartApp())
                    .show();
        } catch (DatabaseBackupHelper.InvalidBackupException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Backup tidak valid")
                    .setMessage(e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Gagal Import")
                    .setMessage("Terjadi kesalahan saat memulihkan database:\n\n"
                            + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    /** Merge-restore: add transactions from the backup for customers that already exist. */
    private void doMergeTransactions(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(this, "Gagal membuka file backup", Toast.LENGTH_LONG).show();
                return;
            }
            DatabaseBackupHelper.ImportResult r =
                    DatabaseBackupHelper.importTransactionsForExistingCustomers(this, in);
            new AlertDialog.Builder(this)
                    .setTitle("Restore Transaksi Selesai")
                    .setMessage(r.imported + " transaksi ditambahkan.\n"
                            + r.skippedDuplicate + " duplikat dilewati.\n"
                            + r.skippedNoCustomer + " dilewati (pelanggan tidak ada di perangkat ini).")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (DatabaseBackupHelper.InvalidBackupException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Backup tidak valid")
                    .setMessage(e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Gagal Restore Transaksi")
                    .setMessage("Terjadi kesalahan:\n\n" + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    /** Pick-customers restore: stage the backup, let the user select customers, then import. */
    private void doPickCustomers(Uri uri) {
        final File staged;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Toast.makeText(this, "Gagal membuka file backup", Toast.LENGTH_LONG).show();
                return;
            }
            staged = DatabaseBackupHelper.stageBackup(this, in);
        } catch (DatabaseBackupHelper.InvalidBackupException e) {
            new AlertDialog.Builder(this).setTitle("Backup tidak valid")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
            return;
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Gagal membaca backup")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
            return;
        }

        final List<DatabaseBackupHelper.BackupCustomer> all;
        try {
            all = DatabaseBackupHelper.listCustomers(staged);
        } catch (Exception e) {
            staged.delete();
            new AlertDialog.Builder(this).setTitle("Gagal membaca pelanggan")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
            return;
        }
        if (all.isEmpty()) {
            staged.delete();
            Toast.makeText(this, "Tidak ada pelanggan di backup", Toast.LENGTH_LONG).show();
            return;
        }
        android.util.Log.i("DAMIU_RESTORE", "pick dialog: listed " + all.size() + " customers from backup");

        View dv = LayoutInflater.from(this).inflate(R.layout.dialog_pick_customers, null);
        EditText etSearch = dv.findViewById(R.id.etSearchCustomer);
        ListView lv = dv.findViewById(R.id.lvCustomers);
        TextView tvCount = dv.findViewById(R.id.tvPickCount);
        View btnAll = dv.findViewById(R.id.btnSelectAll);
        View btnNone = dv.findViewById(R.id.btnSelectNone);
        android.widget.CheckBox cbTrx = dv.findViewById(R.id.cbFilterTrx);
        android.widget.CheckBox cbGeo = dv.findViewById(R.id.cbFilterGeo);
        android.widget.CheckBox cbPhoto = dv.findViewById(R.id.cbFilterPhoto);

        final Set<Long> selected = new HashSet<>();
        final List<DatabaseBackupHelper.BackupCustomer> visible = new ArrayList<>(all);

        ArrayAdapter<DatabaseBackupHelper.BackupCustomer> adapter =
                new ArrayAdapter<DatabaseBackupHelper.BackupCustomer>(
                        this, android.R.layout.simple_list_item_multiple_choice, visible) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        DatabaseBackupHelper.BackupCustomer c = visible.get(position);
                        String label = (c.name != null && !c.name.isEmpty()) ? c.name : "(tanpa nama)";
                        if (c.phone != null && !c.phone.isEmpty()) label += " · " + c.phone;
                        ((CheckedTextView) v).setText(label);
                        return v;
                    }
                };
        lv.setAdapter(adapter);

        Runnable refreshChecks = () -> {
            for (int i = 0; i < visible.size(); i++) {
                lv.setItemChecked(i, selected.contains(visible.get(i).id));
            }
            tvCount.setText("Terpilih: " + selected.size());
        };
        refreshChecks.run();

        lv.setOnItemClickListener((parent, view, position, id) -> {
            long cid = visible.get(position).id;
            if (lv.isItemChecked(position)) selected.add(cid); else selected.remove(cid);
            tvCount.setText("Terpilih: " + selected.size());
        });

        // Gabungan filter: kata kunci + checklist eksistensi data (transaksi/koordinat/foto).
        Runnable applyFilter = () -> {
            String k = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
            boolean fTrx = cbTrx.isChecked(), fGeo = cbGeo.isChecked(), fPhoto = cbPhoto.isChecked();
            visible.clear();
            for (DatabaseBackupHelper.BackupCustomer c : all) {
                if (fTrx && !c.hasTransactions) continue;
                if (fGeo && !c.hasGeo) continue;
                if (fPhoto && !c.hasPhoto) continue;
                boolean hit = k.isEmpty()
                        || (c.name != null && c.name.toLowerCase(Locale.ROOT).contains(k))
                        || (c.phone != null && c.phone.contains(k));
                if (hit) visible.add(c);
            }
            adapter.notifyDataSetChanged();
            refreshChecks.run();
        };

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter.run(); }
        });
        android.widget.CompoundButton.OnCheckedChangeListener fl = (b, c) -> applyFilter.run();
        cbTrx.setOnCheckedChangeListener(fl);
        cbGeo.setOnCheckedChangeListener(fl);
        cbPhoto.setOnCheckedChangeListener(fl);

        btnAll.setOnClickListener(v -> {
            for (DatabaseBackupHelper.BackupCustomer c : visible) selected.add(c.id);
            refreshChecks.run();
        });
        btnNone.setOnClickListener(v -> { selected.clear(); refreshChecks.run(); });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih Konsumen untuk Dipulihkan")
                .setView(dv)
                .setPositiveButton("Restore", null)   // overridden below to validate
                .setNegativeButton("Batal", (d, w) -> staged.delete())
                .setOnCancelListener(d -> staged.delete())
                .create();
        dialog.setOnShowListener(dd ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (selected.isEmpty()) {
                        Toast.makeText(this, "Pilih minimal 1 konsumen", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    runRestoreSelectedCustomers(staged, new HashSet<>(selected));
                }));
        dialog.show();
    }

    private void runRestoreSelectedCustomers(File staged, Set<Long> ids) {
        try {
            android.util.Log.i("DAMIU_RESTORE", "restore selected: " + ids.size() + " customers chosen");
            DatabaseBackupHelper.ImportResult r =
                    DatabaseBackupHelper.importSelectedCustomers(this, staged, ids);
            android.util.Log.i("DAMIU_RESTORE", "result: customersAdded=" + r.customersAdded
                    + " trx=" + r.imported + " related=" + r.relatedAdded
                    + " relinked=" + r.relinked + " dupTrx=" + r.skippedDuplicate);
            new AlertDialog.Builder(this)
                    .setTitle("Restore Konsumen Selesai")
                    .setMessage(r.customersAdded + " konsumen dipulihkan (yang belum ada).\n"
                            + r.imported + " transaksi baru ditambahkan.\n"
                            + r.relinked + " transaksi lama dihubungkan ke konsumen ini.\n"
                            + r.relatedAdded + " data terkait (reseller/pesanan WA) ditambahkan.")
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception e) {
            android.util.Log.e("DAMIU_RESTORE", "restore selected FAILED", e);
            new AlertDialog.Builder(this)
                    .setTitle("Gagal Restore Konsumen")
                    .setMessage("Terjadi kesalahan:\n\n" + e)
                    .setPositiveButton("OK", null)
                    .show();
        } finally {
            staged.delete();
        }
    }

    private void restartApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh status setelah user kembali dari pengaturan Notification Access
        if (settingsDao.isWaAutoDetectEnabled()) {
            updateNotifAccessStatus();
        }
        // Refresh status setelah user kembali dari pengaturan Aksesibilitas.
        if (com.crowja.damiupos.wa.WaAutoSendService.isEnabled(this)) {
            updateAccessibilityStatus();
        }
        updateClaudeLastStatus();
    }

    /** Tampilkan status Claude terakhir — sukses / error / belum pernah call. */
    private void updateClaudeLastStatus() {
        if (tvClaudeLastStatus == null) return;
        String err = settingsDao.getLastClaudeError();
        long at = settingsDao.getLastClaudeAt();
        if (at == 0) {
            tvClaudeLastStatus.setText(""); // belum pernah dipanggil
            tvClaudeLastStatus.setVisibility(View.GONE);
            return;
        }
        tvClaudeLastStatus.setVisibility(View.VISIBLE);
        long agoMs = System.currentTimeMillis() - at;
        String agoStr = formatAgo(agoMs);
        if (err == null || err.isEmpty()) {
            tvClaudeLastStatus.setText("Status AI: ✓ sukses (" + agoStr + ")");
            tvClaudeLastStatus.setTextColor(getResources().getColor(R.color.green));
        } else {
            tvClaudeLastStatus.setText("Status AI: ⚠ " + err + " (" + agoStr + ")");
            tvClaudeLastStatus.setTextColor(getResources().getColor(R.color.red));
        }
    }

    private static String formatAgo(long ms) {
        if (ms < 0) return "baru saja";
        long sec = ms / 1000;
        if (sec < 60) return sec + " detik lalu";
        long min = sec / 60;
        if (min < 60) return min + " menit lalu";
        long hr = min / 60;
        if (hr < 24) return hr + " jam lalu";
        long d = hr / 24;
        return d + " hari lalu";
    }

    /** Tampilkan indikator status token supaya user tahu sudah ter-save. */
    private void updateClaudeKeyStatus() {
        if (tvClaudeKeyStatus == null) return;
        String key = settingsDao.getClaudeApiKey();
        if (key == null || key.isEmpty()) {
            tvClaudeKeyStatus.setText("✗ Bearer token belum di-set — AI tidak akan dipanggil");
            tvClaudeKeyStatus.setTextColor(getResources().getColor(R.color.red));
        } else {
            tvClaudeKeyStatus.setText("✓ Bearer token tersimpan (" + key.length()
                    + " karakter)");
            tvClaudeKeyStatus.setTextColor(getResources().getColor(R.color.green));
        }
    }

    /** Cek apakah WaListenerService sudah granted di Notification Access setting. */
    private void updateNotifAccessStatus() {
        boolean granted = isNotificationListenerEnabled();
        if (granted) {
            tvNotifAccessStatus.setText("✅ Notification Access aktif");
            tvNotifAccessStatus.setTextColor(getResources().getColor(R.color.green));
        } else {
            tvNotifAccessStatus.setText("⚠ Notification Access belum diaktifkan — tap tombol di atas");
            tvNotifAccessStatus.setTextColor(getResources().getColor(R.color.red));
        }
    }

    private void updateAccessibilityStatus() {
        TextView tv = findViewById(R.id.tvAccessibilityStatus);
        if (tv == null) return;
        if (com.crowja.damiupos.wa.WaAutoSendService.isAccessibilityGranted(this)) {
            tv.setText("✅ Layanan Aksesibilitas aktif — struk akan auto-terkirim");
            tv.setTextColor(getResources().getColor(R.color.green));
        } else {
            tv.setText("⚠ Layanan Aksesibilitas belum diaktifkan — tap tombol di atas");
            tv.setTextColor(getResources().getColor(R.color.red));
        }
    }

    private boolean isNotificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName self = new ComponentName(this, WaListenerService.class);
        for (String name : enabled.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(name);
            if (cn != null && cn.equals(self)) return true;
        }
        return false;
    }

    /** Test parser dialog — input pesan, lihat hasil parse instan. */
    private void showTestParserDialog() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("Ketik pesan WA simulasi (mis. \"pesan mineral 5\"):");
        hint.setTextSize(13);
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        wrap.addView(hint);

        EditText etTest = new EditText(this);
        etTest.setHint("Pesan WA...");
        etTest.setMinLines(2);
        etTest.setText("pesan mineral 5");
        wrap.addView(etTest, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Test Parser WhatsApp")
                .setView(wrap)
                .setPositiveButton("Test", (d, w) -> {
                    String msg = etTest.getText() != null
                            ? etTest.getText().toString() : "";
                    if (msg.trim().isEmpty()) {
                        Toast.makeText(this, "Pesan kosong", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "Parsing...", Toast.LENGTH_SHORT).show();
                    OrderParseService.handleIncoming(getApplicationContext(),
                            "Test User", "", msg,
                            new OrderParseService.Listener() {
                                @Override public void onOrderStored(OrderInbox stored) {
                                    showTestResult(stored, null);
                                }
                                @Override public void onError(String err) {
                                    showTestResult(null, err);
                                }
                            });
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showTestResult(OrderInbox stored, String error) {
        StringBuilder sb = new StringBuilder();
        if (error != null) {
            sb.append("❌ Error: ").append(error);
        } else if (stored == null) {
            sb.append("Parser menyimpulkan ini bukan pesanan (skip simpan).\n\n"
                    + "Tip: kalau mode = Default, regex saja yang dipakai. "
                    + "Coba ganti ke Hybrid atau Pakai AI di mode parsing.");
        } else {
            com.crowja.damiupos.wa.ParsedOrder p =
                    com.crowja.damiupos.wa.ParsedOrder.fromJson(stored.getParsedJson());
            sb.append("✅ Disimpan ke Inbox\n\n");
            sb.append("Parser: ").append(stored.getParserUsed()).append("\n");
            sb.append("Confidence: ").append(String.format(java.util.Locale.US,
                    "%.0f%%", p.confidence * 100)).append("\n");
            sb.append("Tipe: ").append(p.type != null ? p.type : "-").append("\n");
            sb.append("Urgent: ").append(p.urgent ? "ya" : "tidak").append("\n");
            sb.append("Items:\n");
            if (p.items.isEmpty()) {
                sb.append("  (tidak ada)\n");
            } else {
                for (com.crowja.damiupos.wa.ParsedOrder.Item it : p.items) {
                    sb.append("  • ").append(it.qty).append("× ")
                            .append(it.product).append("\n");
                }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Hasil Parse")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .setNeutralButton("Buka Inbox", (d, w) ->
                        startActivity(new Intent(this, OrderInboxActivity.class)))
                .show();
    }

    /**
     * Gate akses Pengaturan saat fitur multi user aktif: hanya Admin yang boleh.
     * - Fitur mati → bebas (belum ada konsep role).
     * - User login adalah admin → langsung boleh.
     * - Selain itu → minta PIN admin (modal). Salah/batal → tutup Pengaturan.
     */
    private void enforceAdminGate() {
        if (!settingsDao.isMultiUserEnabled()) return;
        long uid = settingsDao.getCurrentUserId();
        User current = uid > 0 ? userDao.getById(uid) : null;
        if (current != null && current.isAdmin()) return;

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN Admin");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Akses Admin")
                .setMessage("Pengaturan hanya untuk Admin. Masukkan PIN admin untuk lanjut.")
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Masuk", null)
                .setNegativeButton("Batal", (d, w) -> finish())
                .create();
        dialog.setOnShowListener(dlg ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String pin = input.getText().toString().trim();
                    if (userDao.isAdminPin(pin)) {
                        dialog.dismiss();
                    } else {
                        input.setError("PIN admin salah");
                    }
                }));
        dialog.show();
    }

}
