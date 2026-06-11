package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.User;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Pengaturan Multi User & Absensi — dipindah dari Pengaturan ke area Karyawan.
 * Berisi toggle aktifkan login/absensi, email/SMTP laporan, dan rekap/lembur.
 * Dibuka dari menu Karyawan (UserListActivity) atau dari baris di Pengaturan
 * (entry bootstrap sebelum multi user aktif).
 */
public class AttendanceSettingsActivity extends AppCompatActivity {

    private SettingsDao settingsDao;
    private UserDao userDao;

    private SwitchMaterial switchMultiUser;
    private LinearLayout multiUserConfig;
    private TextInputEditText etAdminEmail, etSmtpUser, etSmtpPass, etSmtpHost, etSmtpPort;
    private TextInputEditText etDailyNormalHours, etCutoffDay;
    private SwitchMaterial switchWeeklyRecap;
    private LinearLayout weeklyRecapConfig;
    private Spinner spWeeklyDay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attendance_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        settingsDao = new SettingsDao(dbHelper);
        userDao = new UserDao(dbHelper);

        switchMultiUser = findViewById(R.id.switchMultiUser);
        multiUserConfig = findViewById(R.id.multiUserConfig);
        etAdminEmail = findViewById(R.id.etAdminEmail);
        etSmtpUser = findViewById(R.id.etSmtpUser);
        etSmtpPass = findViewById(R.id.etSmtpPass);
        etSmtpHost = findViewById(R.id.etSmtpHost);
        etSmtpPort = findViewById(R.id.etSmtpPort);
        etDailyNormalHours = findViewById(R.id.etDailyNormalHours);
        etCutoffDay = findViewById(R.id.etCutoffDay);

        boolean muEnabled = settingsDao.isMultiUserEnabled();
        switchMultiUser.setChecked(muEnabled);
        multiUserConfig.setVisibility(muEnabled ? View.VISIBLE : View.GONE);
        etAdminEmail.setText(settingsDao.getAdminEmail());
        etSmtpUser.setText(settingsDao.getSmtpUser());
        etSmtpPass.setText(settingsDao.getSmtpPass());
        etSmtpHost.setText(settingsDao.getSmtpHost());
        etSmtpPort.setText(String.valueOf(settingsDao.getSmtpPort()));
        double dnh = settingsDao.getDailyNormalHours();
        etDailyNormalHours.setText(dnh == Math.rint(dnh)
                ? String.valueOf((int) dnh) : String.valueOf(dnh));
        etCutoffDay.setText(String.valueOf(settingsDao.getPayrollCutoffDay()));

        // Rekap pekanan (PDF).
        switchWeeklyRecap = findViewById(R.id.switchWeeklyRecap);
        weeklyRecapConfig = findViewById(R.id.weeklyRecapConfig);
        spWeeklyDay = findViewById(R.id.spWeeklyDay);
        String[] dayLabels = {"Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu"};
        ArrayAdapter<String> dayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, dayLabels);
        dayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spWeeklyDay.setAdapter(dayAdapter);
        boolean weeklyOn = settingsDao.isWeeklyRecapEnabled();
        switchWeeklyRecap.setChecked(weeklyOn);
        weeklyRecapConfig.setVisibility(weeklyOn ? View.VISIBLE : View.GONE);
        spWeeklyDay.setSelection(settingsDao.getWeeklyRecapDay() - 1); // dow 1-7 → pos 0-6
        switchWeeklyRecap.setOnCheckedChangeListener((b, checked) ->
                weeklyRecapConfig.setVisibility(checked ? View.VISIBLE : View.GONE));

        switchMultiUser.setOnCheckedChangeListener((b, checked) ->
                multiUserConfig.setVisibility(checked ? View.VISIBLE : View.GONE));
        findViewById(R.id.btnKelolaUser).setOnClickListener(v ->
                startActivity(new Intent(this, UserListActivity.class)));
        findViewById(R.id.btnTestSmtp).setOnClickListener(v -> testSmtp());
        findViewById(R.id.btnSimpanAbsensi).setOnClickListener(v -> save());
    }

    private void save() {
        boolean multiUser = switchMultiUser.isChecked();
        boolean adminJustCreated = false;
        if (multiUser) {
            // Anti-terkunci: pastikan selalu ada admin (default Admin / 00000).
            adminJustCreated = userDao.ensureDefaultAdmin();
        }
        settingsDao.setMultiUserEnabled(multiUser);
        settingsDao.setAdminEmail(text(etAdminEmail));
        settingsDao.setSmtpUser(text(etSmtpUser));
        settingsDao.setSmtpPass(etSmtpPass.getText() != null ? etSmtpPass.getText().toString() : "");
        settingsDao.setSmtpHost(text(etSmtpHost));

        String portStr = text(etSmtpPort);
        if (!portStr.isEmpty()) {
            try { settingsDao.setSmtpPort(Integer.parseInt(portStr)); }
            catch (NumberFormatException e) { etSmtpPort.setError("Port tidak valid"); return; }
        }
        String dnhStr = text(etDailyNormalHours);
        if (!dnhStr.isEmpty()) {
            try { settingsDao.setDailyNormalHours(Double.parseDouble(dnhStr)); }
            catch (NumberFormatException e) { etDailyNormalHours.setError("Jam tidak valid"); return; }
        }
        String cutoffStr = text(etCutoffDay);
        if (!cutoffStr.isEmpty()) {
            try {
                int cd = Integer.parseInt(cutoffStr);
                if (cd < 1 || cd > 31) { etCutoffDay.setError("1–31"); return; }
                settingsDao.setPayrollCutoffDay(cd);
            } catch (NumberFormatException e) {
                etCutoffDay.setError("Tanggal tidak valid"); return;
            }
        }

        // Saat pertama kali aktif: baseline periode rekap ke periode terbaru yang
        // sudah selesai, supaya rekap auto-kirim mulai dari cut-off BERIKUTNYA
        // (tidak langsung mengirim rekap periode sebelum fitur diaktifkan).
        if (multiUser && settingsDao.getLastRecapPeriod().isEmpty()) {
            settingsDao.setLastRecapPeriod(
                    AttendanceRecap.mostRecentCompletedPeriod(
                            settingsDao.getPayrollCutoffDay())[1]);
        }

        // Rekap pekanan.
        boolean weekly = switchWeeklyRecap.isChecked();
        settingsDao.setWeeklyRecapEnabled(weekly);
        int weeklyDow = spWeeklyDay.getSelectedItemPosition() + 1; // pos 0-6 → dow 1-7
        settingsDao.setWeeklyRecapDay(weeklyDow);
        if (weekly && settingsDao.getLastWeeklyRecap().isEmpty()) {
            settingsDao.setLastWeeklyRecap(
                    AttendanceRecap.mostRecentCompletedWeek(weeklyDow)[1]);
        }

        if (adminJustCreated) {
            new AlertDialog.Builder(this)
                    .setTitle("Admin default dibuat")
                    .setMessage("Login admin dibuat otomatis:\n\nNama: "
                            + User.DEFAULT_ADMIN_NAME + "\nPIN: " + User.DEFAULT_ADMIN_PIN
                            + "\n\nSegera ganti PIN lewat Kelola Pengguna demi keamanan.")
                    .setPositiveButton("Mengerti", (d, w) -> finish())
                    .show();
        } else {
            Toast.makeText(this, "Pengaturan absensi tersimpan", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * Tes konfigurasi SMTP pakai nilai yang sedang diisi (belum perlu disimpan):
     * connect + auth + kirim email tes ke Email Admin (atau ke diri sendiri kalau
     * kosong). Tampilkan progress, lalu hasil sukses/gagal beserta pesan error.
     */
    private void testSmtp() {
        String host = text(etSmtpHost);
        String user = text(etSmtpUser);
        String pass = etSmtpPass.getText() != null ? etSmtpPass.getText().toString() : "";
        String to = text(etAdminEmail);
        String portStr = text(etSmtpPort);

        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Lengkapi SMTP host, email pengirim, dan password dulu",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int port;
        try {
            port = portStr.isEmpty() ? 587 : Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            etSmtpPort.setError("Port tidak valid");
            return;
        }
        final String dest = to.isEmpty() ? user : to;

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        TextView tv = new TextView(this);
        tv.setText("Menguji koneksi SMTP…");
        tv.setPadding((int) (16 * getResources().getDisplayMetrics().density), 0, 0, 0);
        row.addView(pb);
        row.addView(tv);
        AlertDialog progress = new AlertDialog.Builder(this)
                .setView(row).setCancelable(false).create();
        progress.show();

        ShiftEmailSender.testAsync(host, port, user, pass, dest, (ok, msg) -> {
            if (isFinishing() || isDestroyed()) return;
            progress.dismiss();
            if (ok) {
                new AlertDialog.Builder(this)
                        .setTitle("SMTP OK ✓")
                        .setMessage("Koneksi & autentikasi berhasil.\n\nEmail tes terkirim ke "
                                + dest + ". Cek inbox untuk memastikan.")
                        .setPositiveButton("Tutup", null)
                        .show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("SMTP Gagal")
                        .setMessage((msg != null ? msg : "Error tidak diketahui")
                                + "\n\nPeriksa host/port, email & password. Gmail wajib pakai "
                                + "App Password (bukan password biasa) dengan 2FA aktif.")
                        .setPositiveButton("Tutup", null)
                        .show();
            }
        });
    }

    private static String text(EditText e) {
        return e.getText() != null ? e.getText().toString().trim() : "";
    }
}
