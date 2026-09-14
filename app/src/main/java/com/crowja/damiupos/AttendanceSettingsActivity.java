package com.crowja.damiupos;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.util.ReadOnlyForms;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Multi User &amp; Absensi — READ-ONLY. Jam kerja ideal, cut-off, hari kerja, dan
 * bonus penjualan dikelola admin di dashboard web dan tersinkron ke perangkat ini.
 * Kelola Pengguna (karyawan) juga dipindah ke dashboard web.
 */
public class AttendanceSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attendance_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        SettingsDao settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));

        SwitchMaterial switchMultiUser = findViewById(R.id.switchMultiUser);
        LinearLayout multiUserConfig = findViewById(R.id.multiUserConfig);
        TextInputEditText etDailyNormalHours = findViewById(R.id.etDailyNormalHours);
        TextInputEditText etCutoffDay = findViewById(R.id.etCutoffDay);
        TextInputEditText etWorkDaysPerWeek = findViewById(R.id.etWorkDaysPerWeek);
        SwitchMaterial switchSalesBonus = findViewById(R.id.switchSalesBonus);
        LinearLayout salesBonusConfig = findViewById(R.id.salesBonusConfig);
        TextInputEditText etSalesBonusPerGalon = findViewById(R.id.etSalesBonusPerGalon);

        // Tampilkan nilai terkini (hasil sinkron dari dashboard web).
        boolean muEnabled = settingsDao.isMultiUserEnabled();
        switchMultiUser.setChecked(muEnabled);
        multiUserConfig.setVisibility(muEnabled ? View.VISIBLE : View.GONE);
        double dnh = settingsDao.getDailyNormalHours();
        etDailyNormalHours.setText(dnh == Math.rint(dnh)
                ? String.valueOf((int) dnh) : String.valueOf(dnh));
        etCutoffDay.setText(String.valueOf(settingsDao.getPayrollCutoffDay()));
        etWorkDaysPerWeek.setText(String.valueOf(settingsDao.getWorkDaysPerWeek()));

        boolean bonusOn = settingsDao.isSalesBonusEnabled();
        switchSalesBonus.setChecked(bonusOn);
        salesBonusConfig.setVisibility(bonusOn ? View.VISIBLE : View.GONE);
        double bonusRate = settingsDao.getSalesBonusPerGalon();
        if (bonusRate > 0) etSalesBonusPerGalon.setText(String.valueOf((long) bonusRate));

        // Read-only: dikelola admin di dashboard web.
        ReadOnlyForms.disable(switchMultiUser, etDailyNormalHours, etCutoffDay,
                etWorkDaysPerWeek, switchSalesBonus, etSalesBonusPerGalon);
    }
}
