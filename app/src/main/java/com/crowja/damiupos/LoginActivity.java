package com.crowja.damiupos;

import android.content.Intent;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        userDao = new UserDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        // Guard: fitur mati atau sudah login → langsung dashboard.
        if (!settingsDao.isMultiUserEnabled() || settingsDao.getCurrentUserId() > 0) {
            goToMain();
            return;
        }

        // Failsafe anti terkunci: kalau tidak ada admin, buat admin default
        // (Admin / 00000) supaya owner selalu bisa login & mengatur.
        userDao.ensureDefaultAdmin();

        users = userDao.getActive();
        if (users.isEmpty()) {
            // Sangat tidak mungkin (ensureDefaultAdmin menambah admin aktif),
            // tapi kalau toh kosong, matikan fitur supaya app tidak terkunci.
            settingsDao.setMultiUserEnabled(false);
            Toast.makeText(this,
                    "Tidak ada pengguna aktif — fitur multi user dinonaktifkan",
                    Toast.LENGTH_LONG).show();
            goToMain();
            return;
        }

        setContentView(R.layout.activity_login);

        TextView tvDepot = findViewById(R.id.tvLoginDepot);
        String depot = settingsDao.getDepotName();
        if (depot != null && !depot.isEmpty()) tvDepot.setText(depot);

        if (getIntent().getBooleanExtra(EXTRA_FROM_BREAK, false)) {
            TextView tvInfo = findViewById(R.id.tvLoginInfo);
            tvInfo.setText("⏸ Sedang istirahat — clock in lagi untuk melanjutkan bekerja");
            tvInfo.setVisibility(View.VISIBLE);
        }

        Spinner spUser = findViewById(R.id.spUser);
        ArrayAdapter<User> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, users);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUser.setAdapter(adapter);

        TextInputEditText etPin = findViewById(R.id.etPin);
        findViewById(R.id.btnLogin).setOnClickListener(v -> {
            User selected = (User) spUser.getSelectedItem();
            String pin = etPin.getText() != null ? etPin.getText().toString().trim() : "";
            if (selected == null) return;
            if (pin.isEmpty()) {
                etPin.setError("Masukkan PIN");
                return;
            }
            User auth = userDao.authenticate(selected.getId(), pin);
            if (auth == null) {
                etPin.setError("PIN salah");
                return;
            }
            if (auth.isAdmin()) {
                // Admin: login biasa tanpa absensi (tanpa clock in / selfie).
                settingsDao.setCurrentUser(auth.getId(), auth.getName());
                goToMain();
                return;
            }
            // Staf: ambil selfie wajah dulu, baru clock in.
            pendingUser = auth;
            Intent cam = new Intent(this, CameraCaptureActivity.class);
            cam.putExtra(CameraCaptureActivity.EXTRA_LABEL, "Clock In");
            startActivityForResult(cam, REQ_SELFIE_LOGIN);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SELFIE_LOGIN && pendingUser != null) {
            // Foto boleh null (kamera gagal/ditolak) — absensi tetap jalan.
            String photo = data != null
                    ? data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH) : null;
            new AttendanceDao(DatabaseHelper.getInstance(this))
                    .log(pendingUser.getId(), Attendance.EVENT_IN, photo);
            settingsDao.setCurrentUser(pendingUser.getId(), pendingUser.getName());
            Toast.makeText(this, "Selamat bekerja, " + pendingUser.getName() + " 👋",
                    Toast.LENGTH_SHORT).show();
            pendingUser = null;
            goToMain();
        }
    }

    private void goToMain() {
        // Kirim/retry rekap absensi yang tertunda saat karyawan login. Kalau di
        // tanggal cut-off tidak ada yang login, ini yang menyusulkan di hari
        // berikutnya (hingga email berhasil). Internal-guard: no-op kalau fitur
        // mati / email belum diatur / sudah terkirim.
        AttendanceRecap.maybeSendDueRecap(getApplicationContext(),
                DatabaseHelper.getInstance(this), false);
        AttendanceRecap.maybeSendDueWeeklyRecap(getApplicationContext(),
                DatabaseHelper.getInstance(this), false);

        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
