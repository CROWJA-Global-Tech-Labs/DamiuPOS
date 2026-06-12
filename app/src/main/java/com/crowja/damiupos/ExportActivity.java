package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Expense;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.paywall.PaywallDialogFragment;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExportActivity extends AppCompatActivity {

    private RadioGroup rgPeriode;
    private View layoutCustomRange;
    private com.google.android.material.button.MaterialButton btnStartDate, btnEndDate;
    private TextView tvPeriodeLabel, tvTotalTransaksi, tvTotalPendapatan;
    private TextView tvGalonJual, tvGalonKembali;

    private TransactionDao transactionDao;
    private ExpenseDao expenseDao;

    private MaterialCheckBox cbPenjualan, cbPengeluaran, cbOmset;

    private String startDate;
    private String endDate;
    private String customStartDate;
    private String customEndDate;

    private final SimpleDateFormat sdfDb = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat sdfDisplay = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        transactionDao = new TransactionDao(DatabaseHelper.getInstance(this));
        expenseDao = new ExpenseDao(DatabaseHelper.getInstance(this));

        cbPenjualan = findViewById(R.id.cbExportPenjualan);
        cbPengeluaran = findViewById(R.id.cbExportPengeluaran);
        cbOmset = findViewById(R.id.cbExportOmset);

        rgPeriode = findViewById(R.id.rgPeriode);
        layoutCustomRange = findViewById(R.id.layoutCustomRange);
        btnStartDate = findViewById(R.id.btnStartDate);
        btnEndDate = findViewById(R.id.btnEndDate);
        tvPeriodeLabel = findViewById(R.id.tvPeriodeLabel);
        tvTotalTransaksi = findViewById(R.id.tvTotalTransaksi);
        tvTotalPendapatan = findViewById(R.id.tvTotalPendapatan);
        tvGalonJual = findViewById(R.id.tvGalonJual);
        tvGalonKembali = findViewById(R.id.tvGalonKembali);

        // Initialize custom dates to today
        Calendar cal = Calendar.getInstance();
        customStartDate = sdfDb.format(cal.getTime());
        customEndDate = sdfDb.format(cal.getTime());

        rgPeriode.setOnCheckedChangeListener((group, checkedId) -> {
            layoutCustomRange.setVisibility(
                    checkedId == R.id.rbCustom ? View.VISIBLE : View.GONE);
            calculateDateRange();
            refreshSummary();
        });

        btnStartDate.setOnClickListener(v -> showDatePicker(true));
        btnEndDate.setOnClickListener(v -> showDatePicker(false));

        findViewById(R.id.btnExportCsv).setOnClickListener(v -> exportCsv(false));
        findViewById(R.id.btnShareCsv).setOnClickListener(v -> exportCsv(true));
        findViewById(R.id.btnExportPdf).setOnClickListener(v -> requirePro(() -> exportPdf(false)));
        findViewById(R.id.btnSharePdf).setOnClickListener(v -> requirePro(() -> exportPdf(true)));

        // Initial load
        calculateDateRange();
        refreshSummary();
    }

    private void calculateDateRange() {
        Calendar cal = Calendar.getInstance();
        int checkedId = rgPeriode.getCheckedRadioButtonId();

        if (checkedId == R.id.rbHariIni) {
            startDate = sdfDb.format(cal.getTime());
            endDate = startDate;
        } else if (checkedId == R.id.rbPekanIni) {
            cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
            startDate = sdfDb.format(cal.getTime());
            cal.add(Calendar.DAY_OF_WEEK, 6);
            endDate = sdfDb.format(cal.getTime());
        } else if (checkedId == R.id.rbBulanIni) {
            cal.set(Calendar.DAY_OF_MONTH, 1);
            startDate = sdfDb.format(cal.getTime());
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
            endDate = sdfDb.format(cal.getTime());
        } else if (checkedId == R.id.rbCustom) {
            startDate = customStartDate;
            endDate = customEndDate;
        }
    }

    private void refreshSummary() {
        // Update period label
        try {
            Date d1 = sdfDb.parse(startDate);
            Date d2 = sdfDb.parse(endDate);
            if (startDate.equals(endDate)) {
                tvPeriodeLabel.setText("Periode: " + sdfDisplay.format(d1));
            } else {
                tvPeriodeLabel.setText("Periode: " + sdfDisplay.format(d1) + " - " + sdfDisplay.format(d2));
            }
        } catch (Exception e) {
            tvPeriodeLabel.setText("Periode: " + startDate + " - " + endDate);
        }

        // Get summary
        double[] summary = transactionDao.getSummaryByDateRange(startDate, endDate);
        tvTotalTransaksi.setText(String.valueOf((int) summary[0]));
        tvGalonJual.setText(String.valueOf((int) summary[1]));
        tvGalonKembali.setText(String.valueOf((int) summary[2]));
        tvTotalPendapatan.setText("Rp " + nf.format(summary[3]));
    }

    private void showDatePicker(boolean isStart) {
        Calendar cal = Calendar.getInstance();
        try {
            Date d = sdfDb.parse(isStart ? customStartDate : customEndDate);
            if (d != null) cal.setTime(d);
        } catch (Exception ignored) {}

        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, dayOfMonth);
            String dateStr = sdfDb.format(selected.getTime());

            if (isStart) {
                customStartDate = dateStr;
                btnStartDate.setText(sdfDisplay.format(selected.getTime()));
            } else {
                customEndDate = dateStr;
                btnEndDate.setText(sdfDisplay.format(selected.getTime()));
            }

            calculateDateRange();
            refreshSummary();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * Pro gate: kalau user Pro, langsung jalankan {@code action}. Kalau Free,
     * tampilkan paywall — kalau dia berhasil unlock via rewarded ad, action
     * di-retry otomatis. PDF export pakai ini supaya gating konsisten.
     */
    private void requirePro(Runnable action) {
        SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(this));
        if (s.isProActive()) {
            action.run();
            return;
        }
        PaywallDialogFragment.show(getSupportFragmentManager(),
                "Export PDF hanya untuk pengguna Pro.",
                action::run);
    }

    private void exportCsv(boolean share) {
        boolean wantPenjualan = cbPenjualan.isChecked();
        boolean wantPengeluaran = cbPengeluaran.isChecked();
        boolean wantOmset = cbOmset.isChecked();

        if (!wantPenjualan && !wantPengeluaran && !wantOmset) {
            Toast.makeText(this, "Pilih minimal satu bagian untuk di-export",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Fetch data sesuai checkbox
        List<Transaction> transactions = wantPenjualan || wantOmset
                ? transactionDao.getByDateRange(startDate, endDate)
                : java.util.Collections.emptyList();
        List<Expense> expenses = wantPengeluaran || wantOmset
                ? expenseDao.getByDateRange(startDate, endDate)
                : java.util.Collections.emptyList();

        if (transactions.isEmpty() && expenses.isEmpty()) {
            Toast.makeText(this, "Tidak ada data untuk periode ini",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Build CSV content
        StringBuilder csv = new StringBuilder();
        csv.append("LAPORAN DAMIU POS\n");
        try {
            Date d1 = sdfDb.parse(startDate);
            Date d2 = sdfDb.parse(endDate);
            csv.append("Periode: ").append(sdfDisplay.format(d1))
               .append(" - ").append(sdfDisplay.format(d2)).append("\n");
        } catch (Exception e) {
            csv.append("Periode: ").append(startDate).append(" - ").append(endDate).append("\n");
        }
        csv.append("Diekspor: ").append(sdfDisplay.format(new Date())).append("\n");
        csv.append("\n");

        // ===== OMSET (ringkasan + laba bersih) =====
        double[] summary = transactionDao.getSummaryByDateRange(startDate, endDate);
        double totalExpense = 0;
        for (Expense e : expenses) totalExpense += e.getAmount();

        if (wantOmset) {
            csv.append("OMSET (RINGKASAN)\n");
            csv.append("Total Transaksi,").append((int) summary[0]).append("\n");
            csv.append("Galon Keluar,").append((int) summary[1]).append("\n");
            csv.append("Galon Kembali,").append((int) summary[2]).append("\n");
            csv.append("Total Pendapatan,Rp ").append(nf.format(summary[3])).append("\n");
            csv.append("Total Pengeluaran,Rp ").append(nf.format(totalExpense)).append("\n");
            csv.append("Laba Bersih,Rp ").append(nf.format(summary[3] - totalExpense)).append("\n");
            csv.append("\n");
        }

        // ===== PENJUALAN (detail transaksi) =====
        if (wantPenjualan && !transactions.isEmpty()) {
            csv.append("PENJUALAN\n");
            csv.append("No,Tanggal,Pelanggan,Jenis Air,Tipe,Jumlah Galon,Harga/Galon,Total Harga,Catatan\n");
            int no = 1;
            for (Transaction trx : transactions) {
                csv.append(no++).append(",");
                csv.append(escapeCsv(trx.getTanggal())).append(",");
                csv.append(escapeCsv(formatCustomer(trx.getCustomerName(), trx.getCustomerPhone()))).append(",");
                csv.append(escapeCsv(trx.getProductName() != null ? trx.getProductName() : "-")).append(",");
                csv.append(trx.getType()).append(",");
                csv.append(trx.getJumlahGalon()).append(",");

                if (Transaction.TYPE_JUAL.equals(trx.getType())) {
                    csv.append("Rp ").append(nf.format(trx.getHargaPerGalon())).append(",");
                    csv.append("Rp ").append(nf.format(trx.getTotalHarga())).append(",");
                } else {
                    csv.append("-,-,");
                }

                csv.append(escapeCsv(trx.getCatatan() != null ? trx.getCatatan() : ""));
                csv.append("\n");
            }
            csv.append("\n");
        }

        // ===== PENGELUARAN (detail) =====
        if (wantPengeluaran && !expenses.isEmpty()) {
            csv.append("PENGELUARAN\n");
            csv.append("No,Tanggal,Nama,Nominal,Catatan\n");
            int no = 1;
            for (Expense e : expenses) {
                csv.append(no++).append(",");
                csv.append(escapeCsv(e.getCreatedAt() != null ? e.getCreatedAt() : "")).append(",");
                csv.append(escapeCsv(e.getName())).append(",");
                csv.append("Rp ").append(nf.format(e.getAmount())).append(",");
                csv.append(escapeCsv(e.getNote() != null ? e.getNote() : ""));
                csv.append("\n");
            }
            csv.append("\n");
        }

        // Write file
        String fileName = "Laporan_DAMIU_" + startDate + "_" + endDate + ".csv";
        File exportDir = new File(getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) {
            exportDir.mkdirs();
        }
        File file = new File(exportDir, fileName);

        try (FileWriter writer = new FileWriter(file)) {
            // Write BOM for Excel UTF-8 compatibility
            writer.write('\ufeff');
            writer.write(csv.toString());
            writer.flush();
        } catch (IOException e) {
            Toast.makeText(this, "Gagal menyimpan file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        if (share) {
            shareFile(file);
        } else {
            Toast.makeText(this,
                    "File disimpan:\n" + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareFile(File file) {
        shareFile(file, "text/csv");
    }

    private void shareFile(File file, String mime) {
        Uri uri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider",
                file);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mime);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Laporan Penjualan DAMIU POS");
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(Intent.createChooser(shareIntent, "Bagikan Laporan"));
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ===========================================================================
    // PDF EXPORT (logika di ReportPdfBuilder)
    // ===========================================================================

    private void exportPdf(boolean share) {
        boolean wantPenjualan = cbPenjualan.isChecked();
        boolean wantPengeluaran = cbPengeluaran.isChecked();
        boolean wantOmset = cbOmset.isChecked();

        if (!wantPenjualan && !wantPengeluaran && !wantOmset) {
            Toast.makeText(this, "Pilih minimal satu bagian untuk di-export",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // PDF dibangun oleh ReportPdfBuilder (juga dipakai Rekap Pekanan otomatis).
        File file = ReportPdfBuilder.build(this, startDate, endDate,
                wantPenjualan, wantPengeluaran, wantOmset);
        if (file == null) {
            Toast.makeText(this, "Tidak ada data untuk periode ini",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (share) {
            shareFile(file, "application/pdf");
        } else {
            Toast.makeText(this, "PDF disimpan:\n" + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String safeStr(String s) { return s != null ? s : ""; }

    /** Format kolom pelanggan untuk export: "Nama (NO.HP)" atau "Nama" tanpa nomor. */
    private static String formatCustomer(String name, String phone) {
        String n = name != null && !name.isEmpty() ? name : "-";
        if (phone != null && !phone.trim().isEmpty()) {
            return n + " (" + phone.trim() + ")";
        }
        return n;
    }
}
