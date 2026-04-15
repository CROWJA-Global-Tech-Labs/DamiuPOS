package com.damiu.pos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.SettingsDao;
import com.damiu.pos.demo.DemoDataHelper;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    private TextInputEditText etDefaultOngkir, etPointsPerAmount, etPointsReward;
    private TextInputEditText etDepotName, etDepotAddress, etDepotPhone;
    private TextInputEditText etFollowupDays, etStockAlert;
    private TextInputEditText etHargaBotolGalon;
    private SwitchMaterial switchPoints;
    private LinearLayout pointsConfigContainer;
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
                        int candidates = new com.damiu.pos.db.CustomerDao(
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

        Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
