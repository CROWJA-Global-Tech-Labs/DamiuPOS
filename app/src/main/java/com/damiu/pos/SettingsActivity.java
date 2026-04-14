package com.damiu.pos;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.SettingsDao;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    private TextInputEditText etDefaultOngkir;
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

        // Load current value
        double currentOngkir = settingsDao.getDefaultOngkir();
        if (currentOngkir > 0) {
            etDefaultOngkir.setText(String.valueOf((long) currentOngkir));
        }

        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());
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
        Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
