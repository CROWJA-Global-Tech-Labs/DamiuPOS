package com.damiu.pos;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.TransactionDao;
import com.damiu.pos.model.Transaction;

import java.io.File;
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

    private void exportCsv(boolean share) {
        List<Transaction> transactions = transactionDao.getByDateRange(startDate, endDate);

        if (transactions.isEmpty()) {
            Toast.makeText(this, "Tidak ada data transaksi untuk periode ini", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build CSV content
        StringBuilder csv = new StringBuilder();
        csv.append("LAPORAN PENJUALAN DAMIU POS\n");
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

        // Summary
        double[] summary = transactionDao.getSummaryByDateRange(startDate, endDate);
        csv.append("RINGKASAN\n");
        csv.append("Total Transaksi,").append((int) summary[0]).append("\n");
        csv.append("Galon Terjual,").append((int) summary[1]).append("\n");
        csv.append("Galon Kembali,").append((int) summary[2]).append("\n");
        csv.append("Total Pendapatan,Rp ").append(nf.format(summary[3])).append("\n");
        csv.append("\n");

        // Detail header
        csv.append("No,Tanggal,Pelanggan,Jenis,Jumlah Galon,Harga/Galon,Total Harga,Catatan\n");

        // Detail rows
        int no = 1;
        for (Transaction trx : transactions) {
            csv.append(no++).append(",");
            csv.append(escapeCsv(trx.getTanggal())).append(",");
            csv.append(escapeCsv(trx.getCustomerName())).append(",");
            csv.append(trx.getType()).append(",");
            csv.append(trx.getJumlahGalon()).append(",");

            if (Transaction.TYPE_JUAL.equals(trx.getType())) {
                csv.append("Rp ").append(nf.format(trx.getHargaPerGalon())).append(",");
                csv.append("Rp ").append(nf.format(trx.getTotalHarga())).append(",");
            } else {
                csv.append("-,");
                csv.append("-,");
            }

            csv.append(escapeCsv(trx.getCatatan() != null ? trx.getCatatan() : ""));
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
        Uri uri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider",
                file);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/csv");
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
}
