package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 * Berisi toggle aktifkan login/absensi, jam kerja/cut-off, dan bonus penjualan.
 * Data absensi & shift dilihat admin di dashboard web (bukan email lagi).
 */
public class AttendanceSettingsActivity extends AppCompatActivity {

    private SettingsDao settingsDao;
    private UserDao userDao;

    private SwitchMaterial switchMultiUser;
    private LinearLayout multiUserConfig;
    private TextInputEditText etDailyNormalHours, etCutoffDay, etWorkDaysPerWeek;
    private SwitchMaterial switchSalesBonus;
    private LinearLayout salesBonusConfig;
    private TextInputEditText etSalesBonusPerGalon;

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
        etDailyNormalHours = findViewById(R.id.etDailyNormalHours);
        etCutoffDay = findViewById(R.id.etCutoffDay);
        etWorkDaysPerWeek = findViewById(R.id.etWorkDaysPerWeek);

        boolean muEnabled = settingsDao.isMultiUserEnabled();
        switchMultiUser.setChecked(muEnabled);
        multiUserConfig.setVisibility(muEnabled ? View.VISIBLE : View.GONE);
        double dnh = settingsDao.getDailyNormalHours();
        etDailyNormalHours.setText(dnh == Math.rint(dnh)
                ? String.valueOf((int) dnh) : String.valueOf(dnh));
        etCutoffDay.setText(String.valueOf(settingsDao.getPayrollCutoffDay()));
        etWorkDaysPerWeek.setText(String.valueOf(settingsDao.getWorkDaysPerWeek()));

        // Bonus penjualan per galon.
        switchSalesBonus = findViewById(R.id.switchSalesBonus);
        salesBonusConfig = findViewById(R.id.salesBonusConfig);
        etSalesBonusPerGalon = findViewById(R.id.etSalesBonusPerGalon);
        boolean bonusOn = settingsDao.isSalesBonusEnabled();
        switchSalesBonus.setChecked(bonusOn);
        salesBonusConfig.setVisibility(bonusOn ? View.VISIBLE : View.GONE);
        double bonusRate = settingsDao.getSalesBonusPerGalon();
        if (bonusRate > 0) etSalesBonusPerGalon.setText(String.valueOf((long) bonusRate));
        switchSalesBonus.setOnCheckedChangeListener((b, checked) ->
                salesBonusConfig.setVisibility(checked ? View.VISIBLE : View.GONE));

        switchMultiUser.setOnCheckedChangeListener((b, checked) ->
                multiUserConfig.setVisibility(checked ? View.VISIBLE : View.GONE));
        findViewById(R.id.btnKelolaUser).setOnClickListener(v ->
                startActivity(new Intent(this, UserListActivity.class)));
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
        String workDaysStr = text(etWorkDaysPerWeek);
        if (!workDaysStr.isEmpty()) {
            try {
                int d = Integer.parseInt(workDaysStr);
                if (d < 1 || d > 7) { etWorkDaysPerWeek.setError("1–7"); return; }
                settingsDao.setWorkDaysPerWeek(d);
            } catch (NumberFormatException e) {
                etWorkDaysPerWeek.setError("Jumlah hari tidak valid"); return;
            }
        }

        // Bonus penjualan.
        settingsDao.setSalesBonusEnabled(switchSalesBonus.isChecked());
        String bonusStr = text(etSalesBonusPerGalon);
        if (!bonusStr.isEmpty()) {
            try { settingsDao.setSalesBonusPerGalon(Double.parseDouble(bonusStr)); }
            catch (NumberFormatException e) { etSalesBonusPerGalon.setError("Nominal tidak valid"); return; }
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

    private static String text(EditText e) {
        return e.getText() != null ? e.getText().toString().trim() : "";
    }
}
