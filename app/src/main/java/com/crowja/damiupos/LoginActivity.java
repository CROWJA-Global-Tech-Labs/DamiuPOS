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

    private UserDao userDao;
    private SettingsDao settingsDao;
    private List<User> users;

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
            // Clock in + simpan sesi.
            new AttendanceDao(DatabaseHelper.getInstance(this))
                    .log(auth.getId(), Attendance.EVENT_IN);
            settingsDao.setCurrentUser(auth.getId(), auth.getName());
            Toast.makeText(this, "Selamat bekerja, " + auth.getName() + " 👋",
                    Toast.LENGTH_SHORT).show();
            goToMain();
        });
    }

    private void goToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
