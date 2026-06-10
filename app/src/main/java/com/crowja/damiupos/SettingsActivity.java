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
import com.crowja.damiupos.demo.DemoDataHelper;
import com.crowja.damiupos.model.OrderInbox;
import com.crowja.damiupos.wa.OrderParseService;
import com.crowja.damiupos.wa.WaListenerService;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import android.widget.EditText;
import android.widget.LinearLayout.LayoutParams;

import java.io.InputStream;
import java.io.OutputStream;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_EXPORT_DB = 4101;
    private static final int REQUEST_IMPORT_DB = 4102;
    private static final int REQUEST_PICK_RINGTONE = 4103;

    private TextInputEditText etDefaultOngkir, etPointsPerAmount, etPointsReward;
    private TextInputEditText etDepotName, etDepotAddress, etDepotPhone;
    private TextInputEditText etFollowupDays, etStockAlert, etFollowUpTemplate;
    private TextInputEditText etHargaBotolGalon, etResellerKomisi;
    private TextInputEditText etClaudeKey, etReplyTemplate, etAutoArchiveHours;
    private SwitchMaterial switchPoints, switchWaAutoDetect;
    private LinearLayout pointsConfigContainer, waAutoDetectConfig;
    private RadioGroup rgWaParseMode;
    private TextView tvNotifAccessStatus, tvRingtoneName, tvClaudeKeyStatus,
            tvClaudeLastStatus;
    private SettingsDao settingsDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        etDefaultOngkir = findViewById(R.id.etDefaultOngkir);
        etPointsPerAmount = findViewById(R.id.etPointsPerAmount);
        etPointsReward = findViewById(R.id.etPointsReward);
        switchPoints = findViewById(R.id.switchPoints);
        pointsConfigContainer = findViewById(R.id.pointsConfigContainer);
        etDepotName = findViewById(R.id.etDepotName);
        etDepotAddress = findViewById(R.id.etDepotAddress);
        etDepotPhone = findViewById(R.id.etDepotPhone);
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

        findViewById(R.id.btnTestParser).setOnClickListener(v -> showTestParserDialog());

        findViewById(R.id.btnViewArchive).setOnClickListener(v ->
                startActivity(new Intent(this, OrderInboxActivity.class)
                        .putExtra(OrderInboxActivity.EXTRA_SHOW_ARCHIVE, true)));

        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());

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

        findViewById(R.id.btnGenerateDemo).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Generate Data Demo")
                    .setMessage("Akan ditambahkan 6 pelanggan demo dengan transaksi lama agar "
                            + "fitur Follow Up Pelanggan bisa didemonstrasikan.\n\nLanjutkan?")
                    .setPositiveButton("Generate", (d, w) -> {
                        DemoDataHelper helper = new DemoDataHelper(DatabaseHelper.getInstance(this));
                        int[] result = helper.generateDetailed();
                        int followupDays = settingsDao.getFollowupDays();
                        int candidates = new com.crowja.damiupos.db.CustomerDao(
                                DatabaseHelper.getInstance(this)).countFollowUpCandidates(followupDays);
                        String msg = "Ditambahkan: " + result[0] + " pelanggan, "
                                + result[1] + " transaksi.\n"
                                + "Kandidat Follow Up (>" + followupDays + " hari): "
                                + candidates;
                        new AlertDialog.Builder(this)
                                .setTitle("Selesai")
                                .setMessage(msg)
                                .setPositiveButton("OK", null)
                                .show();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });

        findViewById(R.id.btnClearDemo).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Hapus Data Demo")
                    .setMessage("Semua pelanggan berlabel [DEMO] beserta transaksinya akan dihapus.")
                    .setPositiveButton("Hapus", (d, w) -> {
                        int n = new DemoDataHelper(DatabaseHelper.getInstance(this)).clearDemo();
                        Toast.makeText(this, n + " pelanggan demo dihapus",
                                Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        });

        findViewById(R.id.btnExportDb).setOnClickListener(v -> startExportDb());
        findViewById(R.id.btnImportDb).setOnClickListener(v -> startImportDb());
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
                .setTitle("Import Database")
                .setMessage("Semua data saat ini (pelanggan, produk, transaksi, "
                        + "pengaturan) akan DIGANTI dengan isi file backup.\n\n"
                        + "Lanjutkan?")
                .setPositiveButton("Pilih File", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    // Beberapa file picker tidak menampilkan .db di filter strict;
                    // pakai */* agar file backup tetap kelihatan.
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

    private void save() {
        String ongkirStr = etDefaultOngkir.getText() != null
                ? etDefaultOngkir.getText().toString().trim() : "";
        double ongkir = 0;
        if (!ongkirStr.isEmpty()) {
            try {
                ongkir = Double.parseDouble(ongkirStr);
            } catch (NumberFormatException e) {
                etDefaultOngkir.setError("Format tidak valid");
                return;
            }
        }
        settingsDao.setDefaultOngkir(ongkir);

        settingsDao.setDepotName(etDepotName.getText() != null ? etDepotName.getText().toString().trim() : "");
        settingsDao.setDepotAddress(etDepotAddress.getText() != null ? etDepotAddress.getText().toString().trim() : "");
        settingsDao.setDepotPhone(etDepotPhone.getText() != null ? etDepotPhone.getText().toString().trim() : "");

        // Points settings
        boolean pointsEnabled = switchPoints.isChecked();
        settingsDao.setPointsEnabled(pointsEnabled);
        if (pointsEnabled) {
            String ppaStr = etPointsPerAmount.getText() != null
                    ? etPointsPerAmount.getText().toString().trim() : "";
            double ppa = 10000;
            if (!ppaStr.isEmpty()) {
                try {
                    ppa = Double.parseDouble(ppaStr);
                    if (ppa <= 0) ppa = 10000;
                } catch (NumberFormatException e) {
                    etPointsPerAmount.setError("Format tidak valid");
                    return;
                }
            }
            settingsDao.setPointsPerAmount(ppa);

            String rewardStr = etPointsReward.getText() != null
                    ? etPointsReward.getText().toString().trim() : "";
            int reward = 100;
            if (!rewardStr.isEmpty()) {
                try {
                    reward = Integer.parseInt(rewardStr);
                    if (reward <= 0) reward = 100;
                } catch (NumberFormatException e) {
                    etPointsReward.setError("Format tidak valid");
                    return;
                }
            }
            settingsDao.setPointsRewardThreshold(reward);
        }

        // Followup days
        String fuStr = etFollowupDays.getText() != null ? etFollowupDays.getText().toString().trim() : "";
        int followupDays = 5;
        if (!fuStr.isEmpty()) {
            try {
                followupDays = Integer.parseInt(fuStr);
                if (followupDays <= 0) followupDays = 5;
            } catch (NumberFormatException e) {
                etFollowupDays.setError("Format tidak valid");
                return;
            }
        }
        settingsDao.setFollowupDays(followupDays);

        // Follow-up message template (kosong → fallback ke default di getter)
        if (etFollowUpTemplate != null && etFollowUpTemplate.getText() != null) {
            settingsDao.setFollowUpTemplate(etFollowUpTemplate.getText().toString());
        }

        // Komisi reseller per galon
        if (etResellerKomisi != null && etResellerKomisi.getText() != null) {
            String rkStr = etResellerKomisi.getText().toString().trim();
            if (!rkStr.isEmpty()) {
                try {
                    settingsDao.setResellerKomisi(Double.parseDouble(rkStr));
                } catch (NumberFormatException ignored) {}
            }
        }

        // Stock alert threshold
        String saStr = etStockAlert.getText() != null ? etStockAlert.getText().toString().trim() : "";
        int stockAlert = 30;
        if (!saStr.isEmpty()) {
            try {
                stockAlert = Integer.parseInt(saStr);
                if (stockAlert < 0) stockAlert = 30;
            } catch (NumberFormatException e) {
                etStockAlert.setError("Format tidak valid");
                return;
            }
        }
        settingsDao.setStockAlert(stockAlert);

        // Harga botol galon
        String hbgStr = etHargaBotolGalon.getText() != null ? etHargaBotolGalon.getText().toString().trim() : "";
        double hargaBotol = 35000;
        if (!hbgStr.isEmpty()) {
            try {
                hargaBotol = Double.parseDouble(hbgStr);
                if (hargaBotol <= 0) hargaBotol = 35000;
            } catch (NumberFormatException e) {
                etHargaBotolGalon.setError("Format tidak valid");
                return;
            }
        }
        settingsDao.setHargaBotolGalon(hargaBotol);

        // Reply template
        if (etReplyTemplate != null && etReplyTemplate.getText() != null) {
            settingsDao.setWaReplyTemplate(etReplyTemplate.getText().toString());
        }

        // Auto-archive hours
        if (etAutoArchiveHours != null && etAutoArchiveHours.getText() != null) {
            String s = etAutoArchiveHours.getText().toString().trim();
            int h = SettingsDao.DEFAULT_WA_AUTO_ARCHIVE_HOURS;
            if (!s.isEmpty()) {
                try { h = Integer.parseInt(s); }
                catch (NumberFormatException e) {
                    etAutoArchiveHours.setError("Format tidak valid");
                    return;
                }
            }
            settingsDao.setWaAutoArchiveHours(h);
        }

        Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
