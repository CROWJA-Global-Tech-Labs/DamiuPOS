package com.crowja.damiupos;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SalaryDao;
import com.crowja.damiupos.model.SalaryConfig;
import com.crowja.damiupos.model.SalaryItem;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

/** Form admin: atur komponen gaji satu staf (slip gaji cut-off). */
public class SalaryConfigActivity extends AppCompatActivity {

    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_USER_NAME = "user_name";

    private long userId;
    private String userName;
    private SalaryDao salaryDao;

    private SwitchMaterial swGajiPokok;
    private TextInputEditText etGajiPokok, etTunjHarian, etLembur;
    private TextInputEditText etJabatan, etStatus, etBankName, etBankNo, etBankHolder;
    private LinearLayout llItems;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_salary_config);

        userId = getIntent().getLongExtra(EXTRA_USER_ID, 0);
        userName = getIntent().getStringExtra(EXTRA_USER_NAME);
        if (userId <= 0) { finish(); return; }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle("Atur Gaji — " + (userName != null ? userName : ""));
        toolbar.setNavigationOnClickListener(v -> finish());

        salaryDao = new SalaryDao(DatabaseHelper.getInstance(this));

        swGajiPokok = findViewById(R.id.swGajiPokok);
        etGajiPokok = findViewById(R.id.etGajiPokok);
        etTunjHarian = findViewById(R.id.etTunjHarian);
        etLembur = findViewById(R.id.etLembur);
        etJabatan = findViewById(R.id.etJabatan);
        etStatus = findViewById(R.id.etStatus);
        etBankName = findViewById(R.id.etBankName);
        etBankNo = findViewById(R.id.etBankNo);
        etBankHolder = findViewById(R.id.etBankHolder);
        llItems = findViewById(R.id.llItems);

        load();

        findViewById(R.id.btnAddIncome).setOnClickListener(v ->
                addItemRow(new SalaryItem(SalaryItem.KIND_INCOME, "", 1, 0)));
        findViewById(R.id.btnAddAngsuran).setOnClickListener(v ->
                addItemRow(new SalaryItem(SalaryItem.KIND_ANGSURAN, "", 0, 0)));
        findViewById(R.id.btnSimpanGaji).setOnClickListener(v -> { if (save()) finish(); });
    }

    private void load() {
        SalaryConfig c = salaryDao.getConfig(userId);
        swGajiPokok.setChecked(c.isGajiPokokEnabled());
        if (c.getGajiPokok() > 0) etGajiPokok.setText(longStr(c.getGajiPokok()));
        if (c.getTunjanganHarian() > 0) etTunjHarian.setText(longStr(c.getTunjanganHarian()));
        if (c.getLemburRate() > 0) etLembur.setText(longStr(c.getLemburRate()));
        etJabatan.setText(c.getJabatan());
        etStatus.setText(c.getStatus());
        etBankName.setText(c.getBankName());
        etBankNo.setText(c.getBankNo());
        etBankHolder.setText(c.getBankHolder() != null && !c.getBankHolder().isEmpty()
                ? c.getBankHolder() : userName);

        boolean hasAngsuran = false;
        for (SalaryItem it : salaryDao.getItems(userId)) {
            addItemRow(it);
            if (it.isAngsuran()) hasAngsuran = true;
        }
        // Migrasi angsuran tunggal lama → item Angsuran (sekali, kalau belum ada).
        if (!hasAngsuran && c.getAngsuranBulan() > 0) {
            addItemRow(new SalaryItem(SalaryItem.KIND_ANGSURAN, "Angsuran",
                    c.getAngsuranSisa(), c.getAngsuranBulan()));
        }
    }

    private void addItemRow(SalaryItem it) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_salary_line, llItems, false);
        TextView tvKind = row.findViewById(R.id.tvKind);
        EditText etLabel = row.findViewById(R.id.etLabel);
        EditText etQty = row.findViewById(R.id.etQty);
        EditText etRate = row.findViewById(R.id.etRate);
        boolean angsuran = it.isAngsuran();
        boolean archived = it.isArchived();
        row.setTag(new RowState(it.getKind(), archived));
        if (archived) {
            tvKind.setText("Angsuran ✓ Lunas (arsip)");
        } else {
            tvKind.setText(angsuran ? "Angsuran" : it.isDeduction() ? "Potongan" : "Tunjangan");
        }
        if (angsuran) {
            etLabel.setHint("Judul (mis. Angsuran Motor)");
            etQty.setHint("Sisa hutang (Rp)");
            etQty.setInputType(InputType.TYPE_CLASS_NUMBER);
            etRate.setHint("Cicilan / bulan (Rp)");
        } else {
            etLabel.setHint("Nama komponen");
            etQty.setHint("Qty");
            etRate.setHint("Nominal (Rp) / satuan");
        }
        etLabel.setText(it.getLabel());
        // qty = sisa hutang (angsuran) atau jumlah (tunjangan); kosong kalau 0.
        etQty.setText(it.getQty() > 0 ? trimNum(it.getQty()) : (angsuran ? "" : "1"));
        if (it.getRate() > 0) etRate.setText(longStr(it.getRate()));
        if (archived) {                                 // arsip → tampil read-only redup
            etLabel.setEnabled(false);
            etQty.setEnabled(false);
            etRate.setEnabled(false);
            row.setAlpha(0.55f);
        }
        row.findViewById(R.id.btnRemove).setOnClickListener(v -> llItems.removeView(row));
        llItems.addView(row);
    }

    private boolean save() {
        SalaryConfig c = new SalaryConfig();
        c.setUserId(userId);
        c.setGajiPokokEnabled(swGajiPokok.isChecked());
        c.setGajiPokok(num(etGajiPokok));
        c.setTunjanganHarian(num(etTunjHarian));
        c.setLemburRate(num(etLembur));
        c.setJabatan(text(etJabatan));
        c.setStatus(text(etStatus));
        c.setBankName(text(etBankName));
        c.setBankNo(text(etBankNo));
        c.setBankHolder(text(etBankHolder));
        salaryDao.saveConfig(c);

        List<SalaryItem> items = new ArrayList<>();
        for (int i = 0; i < llItems.getChildCount(); i++) {
            View row = llItems.getChildAt(i);
            RowState st = row.getTag() instanceof RowState ? (RowState) row.getTag() : null;
            String kind = st != null ? st.kind : SalaryItem.KIND_INCOME;
            boolean archived = st != null && st.archived;
            String label = text((EditText) row.findViewById(R.id.etLabel));
            double qty = num((EditText) row.findViewById(R.id.etQty));
            double rate = num((EditText) row.findViewById(R.id.etRate));
            if (!archived && label.isEmpty() && rate == 0) continue;   // baris kosong → lewati
            // Tunjangan: qty default 1 (qty × nominal). Angsuran: qty = sisa hutang
            // (boleh 0 / tak diisi), cicilan = rate.
            if (SalaryItem.KIND_INCOME.equals(kind) && qty == 0) qty = 1;
            SalaryItem si = new SalaryItem(kind, label, qty, rate);
            si.setArchived(archived);
            items.add(si);
        }
        salaryDao.replaceItems(userId, items);
        Toast.makeText(this, "Konfigurasi gaji tersimpan", Toast.LENGTH_SHORT).show();
        return true;
    }

    private double num(EditText e) {
        String s = e != null && e.getText() != null ? e.getText().toString().trim() : "";
        if (s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException ex) { return 0; }
    }

    /** State per-baris komponen: jenis + status arsip (untuk dipertahankan saat simpan). */
    private static final class RowState {
        final String kind;
        final boolean archived;
        RowState(String kind, boolean archived) { this.kind = kind; this.archived = archived; }
    }

    private static String text(EditText e) {
        return e != null && e.getText() != null ? e.getText().toString().trim() : "";
    }

    private static String longStr(double v) { return String.valueOf((long) v); }

    private static String trimNum(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
