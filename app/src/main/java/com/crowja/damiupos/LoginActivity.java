package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * Layar login multi user: pilih nama + PIN → clock in (absensi IN) →
 * masuk dashboard. Juga dipakai sebagai layar "idle" saat istirahat —
 * user wajib clock in lagi untuk lanjut bekerja.
 */
public class LoginActivity extends AppCompatActivity {

    /** Extra: dibuka karena tombol Istirahat (tampilkan pesan istirahat). */
    public static final String EXTRA_FROM_BREAK = "from_break";

    private static final int REQ_SELFIE_LOGIN = 701;

    private UserDao userDao;
    private SettingsDao settingsDao;
    private List<User> users;
    private User pendingUser; // user yang sudah lolos PIN, menunggu selfie
    private Spinner spUser;
    private ArrayAdapter<User> userAdapter;
    private TextView tvSyncStatus;

    /** Segarkan daftar staf saat sinkron membawa data baru (mis. karyawan baru
     *  ditambahkan admin di dashboard) — langsung bisa dipilih tanpa restart. */
    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reloadUsers(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        userDao = new UserDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        // Guard: fitur mati atau sudah login → langsung dashboard.
        if (!settingsDao.isMultiUserEnabled() || settingsDao.getCurrentUserId() > 0) {
            goToMain(false);
            return;
        }

        // Failsafe anti terkunci: kalau tidak ada admin, buat admin default
        // (Admin / 00000) supaya owner selalu bisa login & mengatur.
        userDao.ensureDefaultAdmin();

        users = userDao.getActive();
        if (users.isEmpty()) {
            // Sangat tidak mungkin (ensureDefaultAdmin menambah admin aktif),
            // tapi kalau toh kosong, matikan fitur LOKAL saja supaya app tidak
            // terkunci — JANGAN dorong ke dashboard (multiuser_enabled kini
            // dikelola web), agar satu HP rusak tak mematikan login staf se-cabang.
            settingsDao.setMultiUserEnabledLocal(false);
            Toast.makeText(this,
                    "Tidak ada pengguna aktif — fitur multi user dinonaktifkan",
                    Toast.LENGTH_LONG).show();
            goToMain(false);
            return;
        }

        setContentView(R.layout.activity_login);

        TextView tvDepot = findViewById(R.id.tvLoginDepot);
        String depot = settingsDao.getDepotName();
        if (depot != null && !depot.isEmpty()) tvDepot.setText(depot);

        // Layar istirahat: tampilkan tombol "Lanjut Kerja" 1 ketukan untuk user yang sedang istirahat
        // (clock in lagi TANPA PIN/selfie — shift masih terbuka). PIN tetap tersedia untuk ganti user.
        long breakUid = settingsDao.getBreakUserId();
        User breakUser = breakUid > 0 ? userDao.getById(breakUid) : null;
        if (breakUser != null) {
            MaterialButton btnResume = findViewById(R.id.btnResume);
            btnResume.setText("▶ Lanjut Kerja — " + breakUser.getName());
            btnResume.setVisibility(View.VISIBLE);
            final User bu = breakUser;
            btnResume.setOnClickListener(v -> resumeFromBreak(bu));
            TextView tvInfo = findViewById(R.id.tvLoginInfo);
            tvInfo.setText("⏸ " + breakUser.getName() + " sedang istirahat — tekan Lanjut Kerja, "
                    + "atau login dengan PIN untuk ganti pengguna.");
            tvInfo.setVisibility(View.VISIBLE);
        } else {
            if (breakUid > 0) settingsDao.clearBreakUser();   // user istirahat tak ada lagi → bersihkan
            if (getIntent().getBooleanExtra(EXTRA_FROM_BREAK, false)) {
                TextView tvInfo = findViewById(R.id.tvLoginInfo);
                tvInfo.setText("⏸ Sedang istirahat — clock in lagi untuk melanjutkan bekerja");
                tvInfo.setVisibility(View.VISIBLE);
            }
        }

        spUser = findViewById(R.id.spUser);
        userAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new java.util.ArrayList<>(users));
        userAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUser.setAdapter(userAdapter);

        TextInputEditText etPin = findViewById(R.id.etPin);
        findViewById(R.id.btnLogin).setOnClickListener(v -> {
            User selected = (User) spUser.getSelectedItem();
            String pin = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (selected == null) return;
            if (pin.isEmpty()) {
                etPin.setError("Masukkan PIN");
                return;
            }
            // Staff added on the web dashboard arrive without a PIN — the first PIN entered here
            // becomes theirs (set on first login, as shown on the dashboard).
            if (selected.getPin() == null || selected.getPin().isEmpty()) {
                userDao.setPin(selected.getId(), pin);
                selected.setPin(pin);
                Toast.makeText(this, "PIN dibuat untuk " + selected.getName() + ".", Toast.LENGTH_SHORT).show();
            }
            User auth = userDao.authenticate(selected.getId(), pin);
            if (auth == null) {
                etPin.setError("PIN salah");
                return;
            }
            settingsDao.clearBreakUser();   // login (siapa pun) membatalkan "Lanjut Kerja" yang tertunda
            if (!auth.tracksAttendance()) {
                // Admin & Viewer: login biasa tanpa absensi (tanpa clock in / selfie).
                settingsDao.setCurrentUser(auth.getId(), auth.getName());
                goToMain(false);
                return;
            }
            // Staf: ambil selfie wajah dulu, baru clock in.
            pendingUser = auth;
            Intent cam = new Intent(this, CameraCaptureActivity.class);
            cam.putExtra(CameraCaptureActivity.EXTRA_LABEL, "Clock In");
            startActivityForResult(cam, REQ_SELFIE_LOGIN);
        });

        // Provisioning langsung dari halaman login: hubungkan / re-provision
        // perangkat ke server online tanpa harus login dulu. Pendaftaran tetap
        // butuh kode/QR provisioning dari admin (Dashboard → Kelola → Provisioning).
        tvSyncStatus = findViewById(R.id.tvSyncStatus);
        findViewById(R.id.btnProvision).setOnClickListener(v ->
                startActivity(new Intent(this, SyncSettingsActivity.class)));
        updateSyncStatus();

        // Pulihkan pendingUser kalau activity sempat di-recreate OS saat selfie
        // di depan — supaya hasil foto tetap tercatat sebagai clock in.
        if (savedInstanceState != null) {
            long savedUid = savedInstanceState.getLong("pending_login_uid", 0);
            if (savedUid > 0) pendingUser = userDao.getById(savedUid);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sinkronisasi berkelanjutan: nyalakan service polling (jalan terus selama
        // app hidup, termasuk background) + tarik data terbaru, lalu segarkan daftar
        // staf supaya karyawan baru dari dashboard langsung bisa dipilih.
        com.crowja.damiupos.LocationService.ensureOnline(getApplicationContext());
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
        reloadUsers();
        updateSyncStatus();   // segarkan status koneksi (mis. setelah kembali dari provisioning)
        IntentFilter f = new IntentFilter(com.crowja.damiupos.sync.SyncEngine.ACTION_SYNCED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(syncReceiver); } catch (Exception ignored) {}
    }

    /** Muat ulang daftar staf aktif ke spinner, mempertahankan pilihan saat ini. */
    private void reloadUsers() {
        if (userDao == null || userAdapter == null || spUser == null) return;
        Object sel = spUser.getSelectedItem();
        long selId = sel instanceof User ? ((User) sel).getId() : -1;
        List<User> fresh = userDao.getActive();
        users = fresh;
        userAdapter.clear();
        userAdapter.addAll(fresh);
        userAdapter.notifyDataSetChanged();
        if (selId > 0) {
            for (int i = 0; i < fresh.size(); i++) {
                if (fresh.get(i).getId() == selId) { spUser.setSelection(i); break; }
            }
        }
    }

    /** Tampilkan status koneksi server di footer login + sesuaikan label tombol. */
    private void updateSyncStatus() {
        if (tvSyncStatus == null || settingsDao == null) return;
        SyncSettings cfg = new SyncSettings(settingsDao);
        if (cfg.isEnrolled()) {
            String branch = cfg.getBranchName();
            tvSyncStatus.setText(branch != null && !branch.isEmpty()
                    ? "✓ Terhubung ke server — " + branch
                    : "✓ Terhubung ke server");
        } else {
            tvSyncStatus.setText("Perangkat belum terhubung ke server");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Selamatkan user yang menunggu selfie supaya clock in tidak hilang
        // kalau LoginActivity di-kill OS saat kamera di depan.
        if (pendingUser != null) outState.putLong("pending_login_uid", pendingUser.getId());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SELFIE_LOGIN && pendingUser != null) {
            // Batal eksplisit di peringatan lokasi → jangan clock in, balik ke login.
            if (data != null && data.getBooleanExtra(
                    CameraCaptureActivity.EXTRA_USER_CANCELLED, false)) {
                pendingUser = null;
                return;
            }
            // Foto boleh null (kamera gagal/ditolak) — absensi tetap jalan.
            String photo = data != null
                    ? data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH) : null;
            long attId = new AttendanceDao(DatabaseHelper.getInstance(this))
                    .log(pendingUser.getId(), Attendance.EVENT_IN, photo);
            LocationService.stampAttendanceLocation(this, attId);   // GPS clock-in untuk dashboard
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());   // absensi real-time
            settingsDao.setCurrentUser(pendingUser.getId(), pendingUser.getName());
            // Shift terbuka → service polling tetap aktif (background) sepanjang
            // shift, termasuk saat istirahat, sampai Pulang.
            settingsDao.setShiftActive(true);
            // Jadwalkan pengingat "jam kerja terpenuhi" untuk shift ini
            // (re-arm otomatis menghitung jam kerja sebelum istirahat).
            WorkHoursReminder.schedule(getApplicationContext(), pendingUser.getId());
            Toast.makeText(this, "Selamat bekerja, " + pendingUser.getName() + " 👋",
                    Toast.LENGTH_SHORT).show();
            pendingUser = null;
            goToMain(true);   // staf clock in → MainActivity tampilkan info pending
        }
    }

    /**
     * "Lanjut Kerja" dari istirahat: clock in lagi TANPA PIN/selfie (shift masih terbuka). GPS tetap
     * distempel agar kepatuhan geofence di dashboard terjaga; hanya foto wajah yang dilewati. Login PIN
     * biasa tetap tersedia untuk ganti pengguna.
     */
    private void resumeFromBreak(User user) {
        long attId = new AttendanceDao(DatabaseHelper.getInstance(this))
                .log(user.getId(), Attendance.EVENT_IN, null);   // tanpa selfie pada lanjut cepat
        LocationService.stampAttendanceLocation(this, attId);    // GPS clock-in untuk dashboard/geofence
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());   // absensi real-time
        settingsDao.setCurrentUser(user.getId(), user.getName());
        settingsDao.setShiftActive(true);
        settingsDao.clearBreakUser();
        WorkHoursReminder.schedule(getApplicationContext(), user.getId());
        Toast.makeText(this, "Lanjut bekerja, " + user.getName() + " 👋", Toast.LENGTH_SHORT).show();
        goToMain(true);
    }

    private void goToMain(boolean justClockedInStaff) {
        // Dorong data (absensi, transaksi, dll.) ke server saat login supaya
        // admin selalu melihat data terkini di dashboard web. Tidak ada email.
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
