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
    // PDF EXPORT
    // ===========================================================================
    //
    // A4 landscape (842 × 595 pt). 30pt margin, kolom diberi lebar proporsional
    // berdasarkan konten yang biasanya muncul:
    //   No (30) | Tanggal (95) | Pelanggan (155) | Jenis Air (130)
    //   | Tipe (60) | Qty (40) | Harga/Galon (110) | Total (130)
    // = 750pt (782pt usable; sisa untuk inset).
    //
    private static final float PAGE_WIDTH = 842f;   // A4 landscape
    private static final float PAGE_HEIGHT = 595f;
    private static final float MARGIN_LEFT = 30f;
    private static final float MARGIN_RIGHT = 30f;
    private static final float MARGIN_TOP = 30f;
    private static final float MARGIN_BOTTOM = 30f;
    private static final float ROW_HEIGHT = 18f;
    private static final float HEADER_HEIGHT = 22f;

    // Lebar tiap kolom (urutan harus match COL_HEADERS)
    private static final float[] COL_WIDTHS = {
            30f,   // No
            95f,   // Tanggal
            155f,  // Pelanggan
            130f,  // Jenis Air
            60f,   // Tipe
            40f,   // Qty
            110f,  // Harga/Galon
            130f   // Total
    };
    private static final String[] COL_HEADERS = {
            "No", "Tanggal", "Pelanggan", "Jenis Air",
            "Tipe", "Qty", "Harga/Galon", "Total"
    };
    // Alignment per kolom: 'L' = left, 'R' = right, 'C' = center
    private static final char[] COL_ALIGN = {
            'C', 'L', 'L', 'L', 'C', 'R', 'R', 'R'
    };

    private void exportPdf(boolean share) {
        boolean wantPenjualan = cbPenjualan.isChecked();
        boolean wantPengeluaran = cbPengeluaran.isChecked();
        boolean wantOmset = cbOmset.isChecked();

        if (!wantPenjualan && !wantPengeluaran && !wantOmset) {
            Toast.makeText(this, "Pilih minimal satu bagian untuk di-export",
                    Toast.LENGTH_SHORT).show();
            return;
        }

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

        SettingsDao settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        String depotName = settingsDao.getDepotName();
        if (depotName == null || depotName.isEmpty()) depotName = "DAMIU POS";

        PdfDocument pdf = new PdfDocument();
        Paint pTitle = new Paint();
        pTitle.setColor(Color.BLACK);
        pTitle.setTextSize(16f);
        pTitle.setFakeBoldText(true);
        pTitle.setAntiAlias(true);

        Paint pSub = new Paint();
        pSub.setColor(Color.DKGRAY);
        pSub.setTextSize(10f);
        pSub.setAntiAlias(true);

        Paint pHeader = new Paint();
        pHeader.setColor(Color.WHITE);
        pHeader.setTextSize(10f);
        pHeader.setFakeBoldText(true);
        pHeader.setAntiAlias(true);

        Paint pHeaderBg = new Paint();
        pHeaderBg.setColor(Color.parseColor("#1565C0"));

        Paint pCell = new Paint();
        pCell.setColor(Color.BLACK);
        pCell.setTextSize(9f);
        pCell.setAntiAlias(true);

        Paint pZebra = new Paint();
        pZebra.setColor(Color.parseColor("#F5F5F5"));

        Paint pBorder = new Paint();
        pBorder.setColor(Color.parseColor("#CCCCCC"));
        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(0.5f);

        // Pre-build summary
        double[] summary = transactionDao.getSummaryByDateRange(startDate, endDate);
        double totalExpense = 0;
        for (Expense e : expenses) totalExpense += e.getAmount();

        // Page layout state
        int pageNum = 1;
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
        PdfDocument.Page page = pdf.startPage(info);
        android.graphics.Canvas c = page.getCanvas();

        float y = drawPdfHeader(c, depotName, summary, totalExpense, wantOmset, pTitle, pSub);

        if (!wantPenjualan) {
            // Skip transactions table entirely; jump straight to expenses section
            if (wantPengeluaran && !expenses.isEmpty()) {
                Object[] state = renderExpensesTable(pdf, page, c, y, expenses,
                        pageNum, pTitle, pSub, pHeader, pHeaderBg, pCell, pBorder, pZebra);
                page = (PdfDocument.Page) state[0];
                c = page.getCanvas();
                pageNum = (int) state[1];
            }
            drawPdfFooter(c, pageNum, pSub);
            pdf.finishPage(page);
            finishPdfWrite(pdf, share);
            return;
        }

        y = drawPdfTableHeader(c, y, pHeader, pHeaderBg);

        int rowIdx = 0;
        int no = 1;
        for (Transaction trx : transactions) {
            // Pagination
            if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN_BOTTOM) {
                drawPdfFooter(c, pageNum, pSub);
                pdf.finishPage(page);
                pageNum++;
                info = new PdfDocument.PageInfo.Builder(
                        (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
                page = pdf.startPage(info);
                c = page.getCanvas();
                y = MARGIN_TOP;
                y = drawPdfTableHeader(c, y, pHeader, pHeaderBg);
                rowIdx = 0;
            }
            String tipe = Transaction.TYPE_JUAL.equals(trx.getType()) ? "Jual" : "Kembali";
            String prodName = trx.getProductName() != null ? trx.getProductName() : "-";
            // Untuk transaksi multi-item, tampilkan nama-nama digabung
            if (trx.getItems() != null && trx.getItems().size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < trx.getItems().size(); i++) {
                    if (i > 0) sb.append(" + ");
                    sb.append(trx.getItems().get(i).productName);
                }
                prodName = sb.toString();
            }
            // Khusus transaksi jual botol kosong saja
            if (trx.getCatatan() != null && trx.getCatatan().contains("[JUAL BOTOL KOSONG]")) {
                prodName = "Botol Galon Kosong";
            }
            String tanggal = trx.getTanggal() != null ? trx.getTanggal() : "";
            // Show date+time without seconds; expected stored "yyyy-MM-dd HH:mm:ss"
            if (tanggal.length() >= 16) tanggal = tanggal.substring(0, 16);
            String[] cells = new String[] {
                    String.valueOf(no++),
                    tanggal,
                    formatCustomer(trx.getCustomerName(), trx.getCustomerPhone()),
                    prodName,
                    tipe,
                    String.valueOf(trx.getJumlahGalon()),
                    Transaction.TYPE_JUAL.equals(trx.getType())
                            ? "Rp " + nf.format(trx.getHargaPerGalon())
                            : "-",
                    Transaction.TYPE_JUAL.equals(trx.getType()) || trx.getTotalHarga() > 0
                            ? "Rp " + nf.format(trx.getTotalHarga())
                            : "-"
            };

            // Zebra background untuk baris ganjil
            if (rowIdx % 2 == 1) {
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumWidths(),
                        y + ROW_HEIGHT, pZebra);
            }
            drawPdfRow(c, y, cells, pCell, pBorder);
            y += ROW_HEIGHT;
            rowIdx++;
        }

        // Tambahan: render Pengeluaran section di halaman baru kalau dipilih
        if (wantPengeluaran && !expenses.isEmpty()) {
            Object[] state = renderExpensesTable(pdf, page, c, y, expenses,
                    pageNum, pTitle, pSub, pHeader, pHeaderBg, pCell, pBorder, pZebra);
            page = (PdfDocument.Page) state[0];
            c = page.getCanvas();
            pageNum = (int) state[1];
        }

        drawPdfFooter(c, pageNum, pSub);
        pdf.finishPage(page);

        finishPdfWrite(pdf, share);
    }

    /** Write the prepared PDF document to disk + share/toast as requested. */
    private void finishPdfWrite(PdfDocument pdf, boolean share) {
        String fileName = "Laporan_DAMIU_" + startDate + "_" + endDate + ".pdf";
        File exportDir = new File(getExternalFilesDir(null), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();
        File file = new File(exportDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            pdf.writeTo(fos);
        } catch (IOException e) {
            pdf.close();
            Toast.makeText(this, "Gagal menyimpan PDF: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        pdf.close();
        if (share) {
            shareFile(file, "application/pdf");
        } else {
            Toast.makeText(this,
                    "PDF disimpan:\n" + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // Lebar kolom + header untuk tabel Pengeluaran (3 kolom: Tanggal, Nama, Nominal)
    private static final float[] EXPENSE_COL_WIDTHS = { 110f, 470f, 170f };
    private static final String[] EXPENSE_COL_HEADERS = { "Tanggal", "Nama Pengeluaran", "Nominal" };
    private static final char[] EXPENSE_COL_ALIGN = { 'L', 'L', 'R' };

    private float sumExpenseWidths() {
        float s = 0;
        for (float w : EXPENSE_COL_WIDTHS) s += w;
        return s;
    }

    /**
     * Render section "PENGELUARAN" di halaman baru (atau lanjutan), dengan
     * tabel sendiri. Returns {currentPage, newPageNum}.
     */
    private Object[] renderExpensesTable(PdfDocument pdf, PdfDocument.Page page,
                                         android.graphics.Canvas c, float yIn,
                                         List<Expense> expenses, int pageNum,
                                         Paint pTitle, Paint pSub,
                                         Paint pHeader, Paint pHeaderBg,
                                         Paint pCell, Paint pBorder, Paint pZebra) {
        // Pindah ke page baru supaya section pengeluaran clean
        drawPdfFooter(c, pageNum, pSub);
        pdf.finishPage(page);
        pageNum++;
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
        page = pdf.startPage(info);
        c = page.getCanvas();
        float y = MARGIN_TOP + 14f;
        c.drawText("PENGELUARAN OPERASIONAL", MARGIN_LEFT, y, pTitle);
        y += 18f;

        // Header table
        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumExpenseWidths(),
                y + HEADER_HEIGHT, pHeaderBg);
        float x = MARGIN_LEFT;
        float textY = y + HEADER_HEIGHT - 7f;
        for (int i = 0; i < EXPENSE_COL_HEADERS.length; i++) {
            String label = EXPENSE_COL_HEADERS[i];
            float colW = EXPENSE_COL_WIDTHS[i];
            float drawX = textX(x, colW, label, EXPENSE_COL_ALIGN[i], pHeader);
            c.drawText(label, drawX, textY, pHeader);
            x += colW;
        }
        y += HEADER_HEIGHT;

        int rowIdx = 0;
        double total = 0;
        for (Expense e : expenses) {
            if (y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN_BOTTOM) {
                drawPdfFooter(c, pageNum, pSub);
                pdf.finishPage(page);
                pageNum++;
                info = new PdfDocument.PageInfo.Builder(
                        (int) PAGE_WIDTH, (int) PAGE_HEIGHT, pageNum).create();
                page = pdf.startPage(info);
                c = page.getCanvas();
                y = MARGIN_TOP;
                // Re-draw header
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumExpenseWidths(),
                        y + HEADER_HEIGHT, pHeaderBg);
                x = MARGIN_LEFT;
                textY = y + HEADER_HEIGHT - 7f;
                for (int i = 0; i < EXPENSE_COL_HEADERS.length; i++) {
                    String label = EXPENSE_COL_HEADERS[i];
                    float colW = EXPENSE_COL_WIDTHS[i];
                    float drawX = textX(x, colW, label, EXPENSE_COL_ALIGN[i], pHeader);
                    c.drawText(label, drawX, textY, pHeader);
                    x += colW;
                }
                y += HEADER_HEIGHT;
                rowIdx = 0;
            }
            String tgl = e.getCreatedAt() != null ? e.getCreatedAt() : "";
            if (tgl.length() >= 16) tgl = tgl.substring(0, 16);
            String[] cells = new String[] {
                    tgl,
                    e.getName() != null ? e.getName() : "(tanpa nama)",
                    "Rp " + nf.format(e.getAmount())
            };
            if (rowIdx % 2 == 1) {
                c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumExpenseWidths(),
                        y + ROW_HEIGHT, pZebra);
            }
            // Draw row (3-col version)
            x = MARGIN_LEFT;
            textY = y + ROW_HEIGHT - 6f;
            for (int i = 0; i < cells.length; i++) {
                float colW = EXPENSE_COL_WIDTHS[i];
                String text = ellipsize(cells[i], colW - 6f, pCell);
                float drawX = textX(x, colW, text, EXPENSE_COL_ALIGN[i], pCell);
                c.drawText(text, drawX, textY, pCell);
                c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
                x += colW;
            }
            c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
            c.drawLine(MARGIN_LEFT, y + ROW_HEIGHT,
                    MARGIN_LEFT + sumExpenseWidths(), y + ROW_HEIGHT, pBorder);

            total += e.getAmount();
            y += ROW_HEIGHT;
            rowIdx++;
        }
        // Footer: total pengeluaran
        y += 8f;
        c.drawText("Total Pengeluaran: Rp " + nf.format(total),
                MARGIN_LEFT, y, pTitle);
        return new Object[] { page, pageNum };
    }

    private float sumWidths() {
        float s = 0;
        for (float w : COL_WIDTHS) s += w;
        return s;
    }

    private float drawPdfHeader(android.graphics.Canvas c, String depotName,
                                double[] summary, double totalExpense,
                                boolean wantOmset, Paint pTitle, Paint pSub) {
        float y = MARGIN_TOP + 14f;
        c.drawText("LAPORAN — " + depotName.toUpperCase(Locale.getDefault()),
                MARGIN_LEFT, y, pTitle);
        y += 14f;
        String periode;
        try {
            Date d1 = sdfDb.parse(startDate);
            Date d2 = sdfDb.parse(endDate);
            periode = startDate.equals(endDate)
                    ? "Periode: " + sdfDisplay.format(d1)
                    : "Periode: " + sdfDisplay.format(d1) + " — " + sdfDisplay.format(d2);
        } catch (Exception e) {
            periode = "Periode: " + startDate + " — " + endDate;
        }
        c.drawText(periode, MARGIN_LEFT, y, pSub);
        y += 12f;
        c.drawText("Diekspor: " + sdfDisplay.format(new Date()), MARGIN_LEFT, y, pSub);
        y += 16f;

        if (wantOmset) {
            // Ringkasan satu baris
            String ringkasan = "Total Trx: " + (int) summary[0]
                    + "    Galon Keluar: " + (int) summary[1]
                    + "    Galon Kembali: " + (int) summary[2]
                    + "    Pendapatan: Rp " + nf.format(summary[3]);
            c.drawText(ringkasan, MARGIN_LEFT, y, pSub);
            y += 12f;
            String laba = "Pengeluaran: Rp " + nf.format(totalExpense)
                    + "    Laba Bersih: Rp " + nf.format(summary[3] - totalExpense);
            c.drawText(laba, MARGIN_LEFT, y, pSub);
            y += 18f;
        }
        return y;
    }

    private float drawPdfTableHeader(android.graphics.Canvas c, float y,
                                     Paint pHeader, Paint pHeaderBg) {
        c.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + sumWidths(),
                y + HEADER_HEIGHT, pHeaderBg);
        float x = MARGIN_LEFT;
        float textY = y + HEADER_HEIGHT - 7f;
        for (int i = 0; i < COL_HEADERS.length; i++) {
            String label = COL_HEADERS[i];
            float colW = COL_WIDTHS[i];
            float drawX = textX(x, colW, label, COL_ALIGN[i], pHeader);
            c.drawText(label, drawX, textY, pHeader);
            x += colW;
        }
        return y + HEADER_HEIGHT;
    }

    private void drawPdfRow(android.graphics.Canvas c, float y, String[] cells,
                            Paint pCell, Paint pBorder) {
        float x = MARGIN_LEFT;
        float textY = y + ROW_HEIGHT - 6f;
        for (int i = 0; i < cells.length; i++) {
            float colW = COL_WIDTHS[i];
            String text = cells[i] != null ? cells[i] : "";
            // Truncate to fit
            text = ellipsize(text, colW - 6f, pCell);
            float drawX = textX(x, colW, text, COL_ALIGN[i], pCell);
            c.drawText(text, drawX, textY, pCell);
            // Vertical separator
            c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
            x += colW;
        }
        // Right border + bottom border
        c.drawLine(x, y, x, y + ROW_HEIGHT, pBorder);
        c.drawLine(MARGIN_LEFT, y + ROW_HEIGHT,
                MARGIN_LEFT + sumWidths(), y + ROW_HEIGHT, pBorder);
    }

    private void drawPdfFooter(android.graphics.Canvas c, int pageNum, Paint pSub) {
        c.drawText("Halaman " + pageNum,
                PAGE_WIDTH - MARGIN_RIGHT - 60f,
                PAGE_HEIGHT - MARGIN_BOTTOM + 18f, pSub);
    }

    private float textX(float colX, float colW, String text, char align, Paint paint) {
        float padding = 4f;
        if (align == 'R') {
            float w = paint.measureText(text);
            return colX + colW - padding - w;
        } else if (align == 'C') {
            float w = paint.measureText(text);
            return colX + (colW - w) / 2f;
        }
        return colX + padding;
    }

    private String ellipsize(String text, float maxWidth, Paint paint) {
        if (text == null) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        String ell = "...";
        float ellW = paint.measureText(ell);
        // Binary truncate
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (paint.measureText(text.substring(0, mid)) + ellW <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ell;
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
