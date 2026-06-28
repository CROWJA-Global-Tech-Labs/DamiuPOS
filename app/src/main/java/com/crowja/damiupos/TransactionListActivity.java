package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.TransactionAdapter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class TransactionListActivity extends AppCompatActivity {

    /** Intent extra untuk set filter tanggal awal. Nilai: "TODAY". */
    public static final String EXTRA_DATE_FILTER = "extra_date_filter";
    public static final String DATE_FILTER_TODAY = "TODAY";

    private TransactionDao transactionDao;
    private TransactionAdapter adapter;
    private RecyclerView rv;
    private TextView tvEmpty, tvSummaryCount, tvSummaryTotal;
    private MaterialButton btnDateRange, btnSort;
    private MaterialButtonToggleGroup toggleTypeFilter;
    private TextInputEditText etSearch;
    private android.widget.CheckBox cbPaySemua, cbPayTunai, cbPayQris, cbPayTransfer;
    private boolean suppressPayEvents = false;

    // Filter state
    private String typeFilter = "ALL"; // ALL, JUAL, KEMBALI
    private String startDate = null;   // yyyy-MM-dd
    private String endDate = null;
    private String search = "";
    private int sortMode = 0; // 0=newest, 1=oldest, 2=highest, 3=lowest

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        transactionDao = new TransactionDao(DatabaseHelper.getInstance(this));

        rv = findViewById(R.id.rvTransactions);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSummaryCount = findViewById(R.id.tvSummaryCount);
        tvSummaryTotal = findViewById(R.id.tvSummaryTotal);
        btnDateRange = findViewById(R.id.btnDateRange);
        btnSort = findViewById(R.id.btnSort);
        toggleTypeFilter = findViewById(R.id.toggleTypeFilter);
        etSearch = findViewById(R.id.etSearch);
        cbPaySemua = findViewById(R.id.cbPaySemua);
        cbPayTunai = findViewById(R.id.cbPayTunai);
        cbPayQris = findViewById(R.id.cbPayQris);
        cbPayTransfer = findViewById(R.id.cbPayTransfer);
        // "Semua" dipilih → bersihkan metode spesifik; dimatikan tanpa metode lain
        // → kembalikan ke "Semua" (filter tidak pernah kosong total).
        cbPaySemua.setOnCheckedChangeListener((b, checked) -> {
            if (suppressPayEvents) return;
            suppressPayEvents = true;
            if (checked) {
                cbPayTunai.setChecked(false);
                cbPayQris.setChecked(false);
                cbPayTransfer.setChecked(false);
            } else if (noPayMethodChecked()) {
                cbPaySemua.setChecked(true);
            }
            suppressPayEvents = false;
            reload();
        });
        android.widget.CompoundButton.OnCheckedChangeListener methodListener = (b, checked) -> {
            if (suppressPayEvents) return;
            suppressPayEvents = true;
            if (checked) {
                cbPaySemua.setChecked(false);          // pilih metode spesifik → matikan "Semua"
            } else if (noPayMethodChecked()) {
                cbPaySemua.setChecked(true);           // tak ada metode → kembali ke "Semua"
            }
            suppressPayEvents = false;
            reload();
        };
        cbPayTunai.setOnCheckedChangeListener(methodListener);
        cbPayQris.setOnCheckedChangeListener(methodListener);
        cbPayTransfer.setOnCheckedChangeListener(methodListener);

        adapter = new TransactionAdapter(true);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        adapter.setOnItemClickListener(trx -> {
            Intent i = new Intent(this, ReceiptActivity.class);
            i.putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, trx.getId());
            startActivity(i);
        });

        adapter.setOnItemLongClickListener(this::confirmDelete);

        toggleTypeFilter.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnFilterJual) typeFilter = "JUAL";
            else if (checkedId == R.id.btnFilterKembali) typeFilter = "KEMBALI";
            else typeFilter = "ALL";
            reload();
        });

        btnDateRange.setOnClickListener(v -> pickDateRange());
        btnSort.setOnClickListener(v -> pickSort());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                search = s.toString().trim().toLowerCase(Locale.getDefault());
                reload();
            }
        });

        // Apply preset filter dari Intent (mis. dibuka dari card Pendapatan Hari Ini)
        applyPresetDateFilter();
    }

    private void applyPresetDateFilter() {
        String preset = getIntent().getStringExtra(EXTRA_DATE_FILTER);
        if (DATE_FILTER_TODAY.equals(preset)) {
            Calendar cal = Calendar.getInstance();
            String today = String.format(Locale.US, "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH));
            startDate = today;
            endDate = today;
            btnDateRange.setText("Hari Ini");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<Transaction> all;
        if (startDate != null && endDate != null) {
            all = transactionDao.getByDateRange(startDate, endDate);
        } else {
            all = transactionDao.getAll();
        }
        // Set metode pembayaran yang dipilih (kosong = semua metode).
        java.util.Set<String> payFilter = new java.util.HashSet<>();
        if (cbPayTunai.isChecked()) payFilter.add(Transaction.PAY_TUNAI);
        if (cbPayQris.isChecked()) payFilter.add(Transaction.PAY_QRIS);
        if (cbPayTransfer.isChecked()) payFilter.add(Transaction.PAY_TRANSFER);

        List<Transaction> filtered = new ArrayList<>();
        for (Transaction t : all) {
            if (!"ALL".equals(typeFilter) && !typeFilter.equals(t.getType())) continue;
            if (!payFilter.isEmpty() && !payFilter.contains(t.getPaymentMethod())) continue;
            if (!search.isEmpty()) {
                String name = t.getCustomerName() != null
                        ? t.getCustomerName().toLowerCase(Locale.getDefault()) : "";
                if (!name.contains(search)) continue;
            }
            filtered.add(t);
        }

        // Sort
        switch (sortMode) {
            case 1: // oldest first
                java.util.Collections.sort(filtered, (a, b) -> safeStr(a.getEffectiveTime())
                        .compareTo(safeStr(b.getEffectiveTime())));
                break;
            case 2: // highest total
                java.util.Collections.sort(filtered, (a, b) -> Double.compare(b.getTotalHarga(), a.getTotalHarga()));
                break;
            case 3: // lowest total
                java.util.Collections.sort(filtered, (a, b) -> Double.compare(a.getTotalHarga(), b.getTotalHarga()));
                break;
            default: // newest first
                java.util.Collections.sort(filtered, (a, b) -> safeStr(b.getEffectiveTime())
                        .compareTo(safeStr(a.getEffectiveTime())));
        }

        adapter.setData(filtered);

        if (filtered.isEmpty()) {
            rv.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            rv.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }

        // Summary
        // JUAL: sum totalHarga
        // KEMBALI: only ganti rugi entries contribute (totalHarga > 0)
        double total = 0;
        for (Transaction t : filtered) {
            if (Transaction.TYPE_JUAL.equals(t.getType())) {
                total += t.getTotalHarga();
            } else if (Transaction.TYPE_KEMBALI.equals(t.getType()) && t.getTotalHarga() > 0) {
                total += t.getTotalHarga();
            }
        }
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        tvSummaryCount.setText(filtered.size() + " trx");
        tvSummaryTotal.setText("Rp " + nf.format(total));
    }

    private boolean noPayMethodChecked() {
        return !cbPayTunai.isChecked() && !cbPayQris.isChecked() && !cbPayTransfer.isChecked();
    }

    private String safeStr(String s) { return s != null ? s : ""; }

    private void confirmDelete(Transaction trx) {
        String title = "Hapus Transaksi?";
        String custName = trx.getCustomerName() != null ? trx.getCustomerName() : "-";
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        String msg = "Yakin hapus transaksi ini?\n\n"
                + "Pelanggan: " + custName + "\n"
                + "Tanggal: " + safeStr(trx.getTanggal()) + "\n"
                + "Jumlah: " + trx.getJumlahGalon() + " galon\n"
                + "Total: Rp " + nf.format(trx.getTotalHarga()) + "\n\n"
                + "Tindakan ini tidak bisa dibatalkan.";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("YA, HAPUS", (d, w) -> {
                    int rows = transactionDao.delete(trx.getId());
                    if (rows > 0) {
                        Toast.makeText(this, "Transaksi dihapus", Toast.LENGTH_SHORT).show();
                        reload();
                    } else {
                        Toast.makeText(this, "Gagal menghapus transaksi", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("TIDAK", null)
                .show();
    }

    private void pickSort() {
        String[] options = {"Terbaru", "Terlama", "Harga Tertinggi", "Harga Terendah"};
        new AlertDialog.Builder(this)
                .setTitle("Urutkan")
                .setSingleChoiceItems(options, sortMode, (d, which) -> {
                    sortMode = which;
                    btnSort.setText(options[which]);
                    d.dismiss();
                    reload();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void pickDateRange() {
        String[] options = {"Semua Tanggal", "Hari Ini", "7 Hari Terakhir",
                "30 Hari Terakhir", "Bulan Ini", "Rentang Kustom..."};
        new AlertDialog.Builder(this)
                .setTitle("Filter Tanggal")
                .setItems(options, (d, which) -> {
                    Calendar cal = Calendar.getInstance();
                    String today = fmt(cal);
                    switch (which) {
                        case 0:
                            startDate = null; endDate = null;
                            btnDateRange.setText("Semua Tanggal");
                            reload();
                            break;
                        case 1:
                            startDate = today; endDate = today;
                            btnDateRange.setText("Hari Ini");
                            reload();
                            break;
                        case 2:
                            endDate = today;
                            cal.add(Calendar.DAY_OF_YEAR, -6);
                            startDate = fmt(cal);
                            btnDateRange.setText("7 Hari");
                            reload();
                            break;
                        case 3:
                            endDate = today;
                            cal.add(Calendar.DAY_OF_YEAR, -29);
                            startDate = fmt(cal);
                            btnDateRange.setText("30 Hari");
                            reload();
                            break;
                        case 4:
                            cal.set(Calendar.DAY_OF_MONTH, 1);
                            startDate = fmt(cal);
                            endDate = today;
                            btnDateRange.setText("Bulan Ini");
                            reload();
                            break;
                        case 5:
                            pickCustomRange();
                            break;
                    }
                })
                .show();
    }

    private void pickCustomRange() {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog start = new DatePickerDialog(this, (vStart, y1, m1, d1) -> {
            Calendar s = Calendar.getInstance();
            s.set(y1, m1, d1);
            String sd = fmt(s);
            DatePickerDialog end = new DatePickerDialog(this, (vEnd, y2, m2, d2) -> {
                Calendar e = Calendar.getInstance();
                e.set(y2, m2, d2);
                String ed = fmt(e);
                startDate = sd; endDate = ed;
                btnDateRange.setText(sd + " → " + ed);
                reload();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            end.setTitle("Tanggal Akhir");
            end.show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        start.setTitle("Tanggal Awal");
        start.show();
    }

    private String fmt(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
