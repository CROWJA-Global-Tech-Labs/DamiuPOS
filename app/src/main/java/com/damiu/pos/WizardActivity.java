package com.damiu.pos;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.SettingsDao;
import com.damiu.pos.model.Customer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

public class WizardActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSION_CONTACTS = 301;
    private static final int TOTAL_STEPS = 6;

    private ViewFlipper flipper;
    private TextView tvStepTitle, tvStepIndicator, tvImportStatus;
    private ProgressBar progress;
    private MaterialButton btnBack, btnNext, btnImportContacts;

    private TextInputEditText etDepotName, etDepotAddress, etDepotPhone;
    private TextInputEditText etDefaultOngkir, etHargaBotolGalon, etPointsPerAmount, etPointsReward;
    private TextInputEditText etFollowupDays, etStockAlert;
    private SwitchMaterial switchPoints;
    private View pointsConfigContainer;

    private SettingsDao settingsDao;
    private CustomerDao customerDao;

    private final String[] titles = new String[]{
            "Selamat Datang",
            "Info Depot",
            "Harga & Ongkir",
            "Sistem Poin",
            "Peringatan",
            "Impor Pelanggan"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wizard);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        settingsDao = new SettingsDao(dbHelper);
        customerDao = new CustomerDao(dbHelper);

        flipper = findViewById(R.id.flipper);
        tvStepTitle = findViewById(R.id.tvStepTitle);
        tvStepIndicator = findViewById(R.id.tvStepIndicator);
        tvImportStatus = findViewById(R.id.tvImportStatus);
        progress = findViewById(R.id.progress);
        btnBack = findViewById(R.id.btnBack);
        btnNext = findViewById(R.id.btnNext);
        btnImportContacts = findViewById(R.id.btnImportContacts);

        etDepotName = findViewById(R.id.etDepotName);
        etDepotAddress = findViewById(R.id.etDepotAddress);
        etDepotPhone = findViewById(R.id.etDepotPhone);
        etDefaultOngkir = findViewById(R.id.etDefaultOngkir);
        etHargaBotolGalon = findViewById(R.id.etHargaBotolGalonWiz);
        etPointsPerAmount = findViewById(R.id.etPointsPerAmount);
        etPointsReward = findViewById(R.id.etPointsReward);
        switchPoints = findViewById(R.id.switchPoints);
        pointsConfigContainer = findViewById(R.id.pointsConfigContainer);
        etFollowupDays = findViewById(R.id.etFollowupDays);
        etStockAlert = findViewById(R.id.etStockAlert);

        // Pre-fill from existing settings (if any)
        etDepotName.setText(settingsDao.getDepotName());
        etDepotAddress.setText(settingsDao.getDepotAddress());
        etDepotPhone.setText(settingsDao.getDepotPhone());
        double ongkir = settingsDao.getDefaultOngkir();
        if (ongkir > 0) etDefaultOngkir.setText(String.valueOf((long) ongkir));
        etHargaBotolGalon.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));
        etPointsPerAmount.setText(String.valueOf((long) settingsDao.getPointsPerAmount()));
        etPointsReward.setText(String.valueOf(settingsDao.getPointsRewardThreshold()));
        etFollowupDays.setText(String.valueOf(settingsDao.getFollowupDays()));
        etStockAlert.setText(String.valueOf(settingsDao.getStockAlert()));
        boolean pointsEnabled = settingsDao.isPointsEnabled();
        switchPoints.setChecked(pointsEnabled);
        pointsConfigContainer.setVisibility(pointsEnabled ? View.VISIBLE : View.GONE);
        switchPoints.setOnCheckedChangeListener((b, checked) ->
                pointsConfigContainer.setVisibility(checked ? View.VISIBLE : View.GONE));

        btnBack.setOnClickListener(v -> goBack());
        btnNext.setOnClickListener(v -> goNext());
        btnImportContacts.setOnClickListener(v -> syncFromContacts());

        updateStepUI();
    }

    @Override
    public void onBackPressed() {
        if (flipper.getDisplayedChild() > 0) {
            goBack();
        } else {
            // Don't allow exit from first step
            moveTaskToBack(true);
        }
    }

    private void goNext() {
        int current = flipper.getDisplayedChild();

        // Validate / save per step
        switch (current) {
            case 1: // Depot
                settingsDao.setDepotName(text(etDepotName));
                settingsDao.setDepotAddress(text(etDepotAddress));
                settingsDao.setDepotPhone(text(etDepotPhone));
                break;
            case 2: // Harga & Ongkir
                double ong = 0;
                try { ong = Double.parseDouble(text(etDefaultOngkir)); } catch (Exception ignored) {}
                settingsDao.setDefaultOngkir(ong);
                double hargaBotol = 35000;
                try { hargaBotol = Double.parseDouble(text(etHargaBotolGalon)); } catch (Exception ignored) {}
                if (hargaBotol <= 0) hargaBotol = 35000;
                settingsDao.setHargaBotolGalon(hargaBotol);
                break;
            case 3: // Points
                boolean enabled = switchPoints.isChecked();
                settingsDao.setPointsEnabled(enabled);
                if (enabled) {
                    double ppa = 10000;
                    try { ppa = Double.parseDouble(text(etPointsPerAmount)); } catch (Exception ignored) {}
                    if (ppa <= 0) ppa = 10000;
                    settingsDao.setPointsPerAmount(ppa);
                    int reward = 100;
                    try { reward = Integer.parseInt(text(etPointsReward)); } catch (Exception ignored) {}
                    if (reward <= 0) reward = 100;
                    settingsDao.setPointsRewardThreshold(reward);
                }
                break;
            case 4: // Peringatan (Follow Up + Stock Alert)
                int fuDays = 5;
                try { fuDays = Integer.parseInt(text(etFollowupDays)); } catch (Exception ignored) {}
                if (fuDays <= 0) fuDays = 5;
                settingsDao.setFollowupDays(fuDays);
                int stockAlert = 30;
                try { stockAlert = Integer.parseInt(text(etStockAlert)); } catch (Exception ignored) {}
                if (stockAlert < 0) stockAlert = 30;
                settingsDao.setStockAlert(stockAlert);
                break;
            case 5: // Final: finish wizard
                finishWizard();
                return;
        }

        if (current < TOTAL_STEPS - 1) {
            flipper.showNext();
            updateStepUI();
        }
    }

    private void goBack() {
        if (flipper.getDisplayedChild() > 0) {
            flipper.showPrevious();
            updateStepUI();
        }
    }

    private void updateStepUI() {
        int idx = flipper.getDisplayedChild();
        tvStepTitle.setText(titles[idx]);
        tvStepIndicator.setText("Langkah " + (idx + 1) + " dari " + TOTAL_STEPS);
        progress.setProgress((int) (((idx + 1) * 100f) / TOTAL_STEPS));
        btnBack.setVisibility(idx == 0 ? View.INVISIBLE : View.VISIBLE);
        if (idx == 0) {
            btnNext.setText("Mulai");
        } else if (idx == TOTAL_STEPS - 1) {
            btnNext.setText("Selesai");
        } else {
            btnNext.setText("Lanjut");
        }
    }

    private void finishWizard() {
        settingsDao.setWizardCompleted(true);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private String text(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private void syncFromContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSION_CONTACTS);
            return;
        }
        doSyncContacts();
    }

    private void doSyncContacts() {
        btnImportContacts.setEnabled(false);
        tvImportStatus.setText("Mengimpor...");

        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

        if (cursor == null) {
            tvImportStatus.setText("Gagal membaca kontak");
            btnImportContacts.setEnabled(true);
            return;
        }

        int imported = 0;
        int skipped = 0;
        java.util.Set<String> processedPhones = new java.util.HashSet<>();

        while (cursor.moveToNext()) {
            String name = cursor.getString(0);
            String phone = cursor.getString(1);
            if (name == null || name.isEmpty() || phone == null || phone.isEmpty()) continue;

            String normalized = phone.replaceAll("[^0-9]", "");
            if (normalized.length() < 4) continue;

            String suffix = normalized.substring(normalized.length() - Math.min(normalized.length(), 8));
            if (processedPhones.contains(suffix)) continue;
            processedPhones.add(suffix);

            if (customerDao.existsByPhone(phone)) { skipped++; continue; }

            Customer c = new Customer(name, phone, "");
            customerDao.insert(c);
            imported++;
        }
        cursor.close();

        String msg = imported + " pelanggan diimpor";
        if (skipped > 0) msg += ", " + skipped + " sudah ada";
        tvImportStatus.setText(msg);
        btnImportContacts.setText("Impor Lagi");
        btnImportContacts.setEnabled(true);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                doSyncContacts();
            } else {
                tvImportStatus.setText("Izin kontak ditolak. Anda dapat melakukan impor nanti di menu Pelanggan.");
            }
        }
    }
}
