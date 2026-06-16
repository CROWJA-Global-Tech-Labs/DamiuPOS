package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * "Analisa & Prediksi" — fitur pintar yang menghitung metrik penjualan +
 * memprediksi kapan perlu order air baku, dari data transaksi & pengeluaran
 * kategori "Belanja Air Baku". Asumsi 1 galon = 19 liter.
 */
public class AnalyticsActivity extends AppCompatActivity {

    private static final int WINDOW_DAYS = 30;

    /** Asumsi liter per galon — dapat diatur di layar ini (default 19). */
    private double litersPerGalon = 19.0;

    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
    private final SimpleDateFormat sdfDb = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat sdfDisplay =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));

    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_analytics);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        container = findViewById(R.id.llMetrics);
        compute();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Export PDF")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { exportPdf(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** Bangun PDF analisa & prediksi lalu bagikan. */
    private void exportPdf() {
        final DatabaseHelper db = DatabaseHelper.getInstance(this);
        Toast.makeText(this, "Membuat PDF…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File f = AnalyticsPdf.build(getApplicationContext(), db);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (f == null) {
                    Toast.makeText(this, "Gagal membuat PDF", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Uri uri = FileProvider.getUriForFile(this,
                            getPackageName() + ".fileprovider", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/pdf");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.putExtra(Intent.EXTRA_SUBJECT, "Analisa & Prediksi");
                    i.setClipData(new android.content.ClipData("Analisa & Prediksi",
                            new String[]{"application/pdf"},
                            new android.content.ClipData.Item(uri)));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Bagikan Analisa & Prediksi"));
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal membagikan: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "analytics-pdf").start();
    }

    private void compute() {
        container.removeAllViews();
        DatabaseHelper db = DatabaseHelper.getInstance(this);
        TransactionDao trxDao = new TransactionDao(db);
        ExpenseDao expenseDao = new ExpenseDao(db);
        SettingsDao settings = new SettingsDao(db);
        litersPerGalon = settings.getLitersPerGalon();

        // Kartu pengaturan: asumsi liter per galon (default 19).
        addLitersSettingCard(settings);

        String today = sdfDb.format(new Date());
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -WINDOW_DAYS);
        String d30 = sdfDb.format(cal.getTime());

        // Penjualan 30 hari: [count, galonJual, galonKembali, pendapatan]
        double[] s30 = trxDao.getSummaryByDateRange(d30, today);
        int galon30 = (int) s30[1];
        double rev30 = s30[3];
        double dailyGalon = galon30 / (double) WINDOW_DAYS;
        double dailyLiters = dailyGalon * litersPerGalon;
        // Harga rata-rata per galon = total harga air minum (lintas jenis, TANPA
        // ongkir & botol kosong) ÷ total galon air. Bukan rev30/galon30 yang
        // ikut menghitung ongkir.
        double[] water = trxDao.getWaterSalesByDateRange(d30, today);
        double avgPrice = water[0] > 0 ? water[1] / water[0] : 0;

        // Air baku: dibeli (liter) & biaya, sejak mulai dicatat.
        double litersBought = expenseDao.getTotalLitersByCategory(Expense.CAT_AIR_BAKU);
        double costAirBaku = expenseDao.getTotalAmountByCategory(Expense.CAT_AIR_BAKU);
        String firstBaku = expenseDao.getEarliestDateByCategory(Expense.CAT_AIR_BAKU);
        // Konsumsi dihitung dari galon terjual SEJAK mulai catat air baku
        // supaya saldo air baku adil (bukan dari seluruh histori).
        int galonSince = firstBaku != null ? (int) trxDao.getSummaryByDateRange(firstBaku, today)[1] : 0;
        double litersConsumed = galonSince * litersPerGalon;
        double sisaLiter = litersBought - litersConsumed;
        // Biaya per galon pakai HARGA BELI TERBARU (laba ikut menyesuaikan kalau
        // harga air baku/segel/tutup berubah); fallback ke rata-rata kalau entry
        // terbaru tak ada.
        double avgBakuPerGalon = litersBought > 0
                ? costAirBaku / (litersBought / litersPerGalon) : 0;
        double latestBakuPerLiter = expenseDao.getLatestUnitPrice(
                Expense.CAT_AIR_BAKU, DatabaseHelper.COL_EXPENSE_LITERS);
        double costPerGalonBaku = latestBakuPerLiter > 0
                ? latestBakuPerLiter * litersPerGalon : avgBakuPerGalon;

        // Biaya perlengkapan per galon (1 segel + 1 tutup tiap galon terjual).
        double segelBought = expenseDao.getTotalPcsByCategory(Expense.CAT_SEGEL);
        double segelCost = expenseDao.getTotalAmountByCategory(Expense.CAT_SEGEL);
        double avgSegelPerPcs = segelBought > 0 ? segelCost / segelBought : 0;
        double latestSegelPerPcs = expenseDao.getLatestUnitPrice(
                Expense.CAT_SEGEL, DatabaseHelper.COL_EXPENSE_PCS);
        double segelPerPcs = latestSegelPerPcs > 0 ? latestSegelPerPcs : avgSegelPerPcs;

        double tutupBought = expenseDao.getTotalPcsByCategory(Expense.CAT_TUTUP);
        double tutupCost = expenseDao.getTotalAmountByCategory(Expense.CAT_TUTUP);
        double avgTutupPerPcs = tutupBought > 0 ? tutupCost / tutupBought : 0;
        double latestTutupPerPcs = expenseDao.getLatestUnitPrice(
                Expense.CAT_TUTUP, DatabaseHelper.COL_EXPENSE_PCS);
        double tutupPerPcs = latestTutupPerPcs > 0 ? latestTutupPerPcs : avgTutupPerPcs;

        double costPerGalonTotal = costPerGalonBaku + segelPerPcs + tutupPerPcs;
        double labaPerGalon = avgPrice - costPerGalonTotal;

        // ---- Penjualan ----
        LinearLayout sec1 = section("Penjualan (" + WINDOW_DAYS + " hari terakhir)");
        row(sec1, "Galon air terjual", galon30 + " galon", 0);
        row(sec1, "Rata-rata per hari", oneDecimal(dailyGalon) + " galon", 0);
        row(sec1, "Harga rata-rata / galon", "Rp " + nf.format(Math.round(avgPrice)), 0);
        row(sec1, "Pendapatan", "Rp " + nf.format(Math.round(rev30)), 0);
        row(sec1, "Proyeksi pendapatan / bulan",
                "Rp " + nf.format(Math.round(dailyGalon * 30 * avgPrice)), 0);

        // ---- Air Baku & Prediksi ----
        LinearLayout sec2 = section("Air Baku & Prediksi Order");
        row(sec2, "Air baku dibeli", nf.format(Math.round(litersBought)) + " L", 0);
        row(sec2, "Air baku terpakai (≈)", nf.format(Math.round(litersConsumed)) + " L", 0);
        row(sec2, "Sisa air baku (≈)", nf.format(Math.round(sisaLiter)) + " L",
                sisaLiter <= 0 ? Color.parseColor("#C62828") : 0);
        row(sec2, "Konsumsi / hari (≈)", nf.format(Math.round(dailyLiters)) + " L", 0);

        String prediksi;
        int prediksiColor = Color.parseColor("#1565C0");
        if (litersBought <= 0) {
            prediksi = "Belum ada data belanja air baku. Catat pengeluaran kategori "
                    + "\"Belanja Air Baku\" (isi liter) agar prediksi muncul.";
            prediksiColor = Color.parseColor("#757575");
        } else if (dailyLiters <= 0) {
            prediksi = "Belum ada penjualan dalam " + WINDOW_DAYS
                    + " hari terakhir untuk memprediksi konsumsi.";
            prediksiColor = Color.parseColor("#757575");
        } else if (sisaLiter <= 0) {
            prediksi = "⚠ Stok air baku tercatat sudah habis — segera order air baku!";
            prediksiColor = Color.parseColor("#C62828");
        } else {
            int daysLeft = (int) Math.floor(sisaLiter / dailyLiters);
            Calendar rc = Calendar.getInstance();
            rc.add(Calendar.DAY_OF_YEAR, daysLeft);
            prediksi = "Cukup untuk ± " + daysLeft + " hari lagi.\nPerkiraan perlu order lagi: "
                    + sdfDisplay.format(rc.getTime()) + ".";
        }
        bigNote(sec2, "Prediksi Order Air Baku", prediksi, prediksiColor);

        // ---- Perlengkapan: Segel & Tutup Galon (1 pcs / galon) ----
        LinearLayout sec4 = section("Perlengkapan Galon (Segel & Tutup)");
        addPcsPrediction(sec4, expenseDao, trxDao, Expense.CAT_SEGEL, "Segel Galon",
                dailyGalon, today);
        addPcsPrediction(sec4, expenseDao, trxDao, Expense.CAT_TUTUP, "Tutup Galon",
                dailyGalon, today);

        // ---- Biaya & Laba (pakai harga beli terbaru) ----
        LinearLayout sec3 = section("Biaya & Laba per Galon (harga terbaru)");
        row(sec3, "Biaya air baku / galon", "Rp " + nf.format(Math.round(costPerGalonBaku)), 0);
        row(sec3, "Biaya segel / galon", "Rp " + nf.format(Math.round(segelPerPcs)), 0);
        row(sec3, "Biaya tutup / galon", "Rp " + nf.format(Math.round(tutupPerPcs)), 0);
        row(sec3, "Total biaya / galon", "Rp " + nf.format(Math.round(costPerGalonTotal)), 0);
        row(sec3, "Harga jual rata-rata / galon", "Rp " + nf.format(Math.round(avgPrice)), 0);
        row(sec3, "Laba kotor / galon",
                "Rp " + nf.format(Math.round(labaPerGalon)),
                labaPerGalon < 0 ? Color.parseColor("#C62828") : Color.parseColor("#2E7D32"));

        // Catatan asumsi
        TextView note = new TextView(this);
        note.setText("Asumsi 1 galon = " + trimDouble(litersPerGalon) + " liter, "
                + "dan 1 galon terjual memakai 1 segel + 1 tutup. "
                + "Prediksi memakai rata-rata penjualan " + WINDOW_DAYS + " hari terakhir; "
                + "saldo air baku/segel/tutup dihitung sejak pertama kali dicatat. Laba kotor "
                + "= harga jual − (air baku + segel + tutup) per galon, memakai HARGA BELI "
                + "TERBARU tiap input (otomatis menyesuaikan saat harga berubah); belum "
                + "termasuk biaya operasional lain.");
        note.setTextSize(11f);
        note.setTextColor(Color.parseColor("#9E9E9E"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        note.setLayoutParams(lp);
        container.addView(note);
    }

    /** Kartu pengaturan: ubah asumsi liter per galon, lalu hitung ulang. */
    private void addLitersSettingCard(SettingsDao settings) {
        LinearLayout sec = section("Pengaturan");
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        r.setLayoutParams(rlp);

        TextView l = new TextView(this);
        l.setText("Liter per galon");
        l.setTextColor(getColorCompat(R.color.text_secondary));
        l.setTextSize(13f);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(trimDouble(litersPerGalon));
        et.setTextSize(14f);
        et.setMinEms(3);
        et.setGravity(Gravity.END);

        Button btn = new Button(this);
        btn.setText("Terapkan");
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> {
            double val;
            try { val = Double.parseDouble(et.getText().toString().trim()); }
            catch (Exception e) { val = 0; }
            if (val <= 0) {
                Toast.makeText(this, "Nilai liter tidak valid", Toast.LENGTH_SHORT).show();
                return;
            }
            settings.setLitersPerGalon(val);
            Toast.makeText(this, "Disimpan: " + trimDouble(val) + " L/galon",
                    Toast.LENGTH_SHORT).show();
            compute(); // hitung ulang dengan asumsi baru
        });

        r.addView(l);
        r.addView(et);
        r.addView(btn);
        sec.addView(r);
    }

    /** Tampilkan double tanpa ".0" kalau bulat. */
    private String trimDouble(double v) {
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(new Locale("id", "ID"), "%.1f", v);
    }

    /**
     * Tambah baris + prediksi order untuk perlengkapan per-galon yang dihitung
     * dalam pcs (1 pcs / galon terjual): Segel atau Tutup galon. Saldo dihitung
     * dari galon terjual sejak pertama kali kategori ini dicatat.
     */
    private void addPcsPrediction(LinearLayout sec, ExpenseDao expenseDao, TransactionDao trxDao,
                                  String category, String label, double dailyGalon, String today) {
        double bought = expenseDao.getTotalPcsByCategory(category);
        String first = expenseDao.getEarliestDateByCategoryPcs(category);
        int galonSince = first != null ? (int) trxDao.getSummaryByDateRange(first, today)[1] : 0;
        double consumed = galonSince;            // 1 pcs per galon terjual
        double sisa = bought - consumed;

        row(sec, label + " dibeli", nf.format(Math.round(bought)) + " pcs", 0);
        row(sec, label + " terpakai (≈)", nf.format(Math.round(consumed)) + " pcs", 0);
        row(sec, "Sisa (≈)", nf.format(Math.round(sisa)) + " pcs",
                sisa <= 0 ? Color.parseColor("#C62828") : 0);

        String prediksi;
        int color = Color.parseColor("#1565C0");
        if (bought <= 0) {
            prediksi = "Belum ada data belanja " + label + ". Catat pengeluaran kategori \""
                    + label + "\" (isi pcs) agar prediksi muncul.";
            color = Color.parseColor("#757575");
        } else if (dailyGalon <= 0) {
            prediksi = "Belum ada penjualan dalam " + WINDOW_DAYS
                    + " hari terakhir untuk memprediksi pemakaian.";
            color = Color.parseColor("#757575");
        } else if (sisa <= 0) {
            prediksi = "⚠ Stok " + label + " tercatat sudah habis — segera order!";
            color = Color.parseColor("#C62828");
        } else {
            int daysLeft = (int) Math.floor(sisa / dailyGalon);
            Calendar rc = Calendar.getInstance();
            rc.add(Calendar.DAY_OF_YEAR, daysLeft);
            prediksi = "Cukup untuk ± " + daysLeft + " hari lagi.\nPerkiraan perlu order lagi: "
                    + sdfDisplay.format(rc.getTime()) + ".";
        }
        bigNote(sec, "Prediksi Order " + label, prediksi, color);
    }

    // ---------------------------------------------------------------- UI build

    /** Buat card section dengan judul, return inner container untuk row-row. */
    private LinearLayout section(String title) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);
        card.setRadius(dp(12));
        card.setCardElevation(dp(2));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColorCompat(R.color.text_primary));
        t.setTextSize(14f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        inner.addView(t);

        card.addView(inner);
        container.addView(card);
        return inner;
    }

    /** Baris label (kiri) + nilai (kanan). highlight=0 → warna default. */
    private void row(LinearLayout parent, String label, String value, int highlight) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        r.setLayoutParams(rlp);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColorCompat(R.color.text_secondary));
        l.setTextSize(13f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        l.setLayoutParams(llp);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(highlight != 0 ? highlight : getColorCompat(R.color.text_primary));
        v.setTextSize(14f);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setGravity(Gravity.END);

        r.addView(l);
        r.addView(v);
        parent.addView(r);
    }

    /** Catatan prediksi yang menonjol (judul kecil + teks besar berwarna). */
    private void bigNote(LinearLayout parent, String title, String text, int color) {
        View div = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(8);
        div.setLayoutParams(dlp);
        div.setBackgroundColor(Color.parseColor("#E0E0E0"));
        parent.addView(div);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColorCompat(R.color.text_secondary));
        t.setTextSize(12f);
        parent.addView(t);

        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(15f);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(2);
        v.setLayoutParams(vlp);
        parent.addView(v);
    }

    private String oneDecimal(double v) {
        return String.format(new Locale("id", "ID"), "%.1f", v);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private int getColorCompat(int resId) {
        return getResources().getColor(resId);
    }
}
