package com.crowja.damiupos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Expense;

import java.io.File;
import java.io.FileOutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * PDF "Analisa & Prediksi" — versi cetak/kirim dari layar AnalyticsActivity.
 * Menghitung ulang metrik yang sama (penjualan 30 hari, air baku, segel/tutup,
 * biaya & laba per galon) dan menatanya dalam A4 portrait.
 */
public final class AnalyticsPdf {

    private AnalyticsPdf() {}

    private static final int WINDOW_DAYS = 30;
    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));
    private static final SimpleDateFormat SDF_DB = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_DISP =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));

    private static final float W = 595f, H = 842f, ML = 36f, MR = 36f, MT = 44f;

    public static File build(Context ctx, DatabaseHelper db) {
        try {
            TransactionDao trxDao = new TransactionDao(db);
            ExpenseDao expenseDao = new ExpenseDao(db);
            SettingsDao s = new SettingsDao(db);
            double lpg = s.getLitersPerGalon();
            String depot = s.getDepotName();
            if (depot == null || depot.isEmpty()) depot = "DAMIU POS";

            String today = SDF_DB.format(new Date());
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -WINDOW_DAYS);
            String d30 = SDF_DB.format(cal.getTime());

            double[] s30 = trxDao.getSummaryByDateRange(d30, today);
            int galon30 = (int) s30[1];
            double rev30 = s30[3];
            double dailyGalon = galon30 / (double) WINDOW_DAYS;
            double dailyLiters = dailyGalon * lpg;
            // Harga rata-rata per galon dari penjualan air minum (lintas jenis,
            // tanpa ongkir & botol kosong).
            double[] water = trxDao.getWaterSalesByDateRange(d30, today);
            double avgPrice = water[0] > 0 ? water[1] / water[0] : 0;

            double litersBought = expenseDao.getTotalLitersByCategory(Expense.CAT_AIR_BAKU);
            double costAirBaku = expenseDao.getTotalAmountByCategory(Expense.CAT_AIR_BAKU);
            String firstBaku = expenseDao.getEarliestDateByCategory(Expense.CAT_AIR_BAKU);
            int galonSince = firstBaku != null
                    ? (int) trxDao.getSummaryByDateRange(firstBaku, today)[1] : 0;
            double litersConsumed = galonSince * lpg;
            double sisaLiter = litersBought - litersConsumed;
            double avgBakuPerGalon = litersBought > 0 ? costAirBaku / (litersBought / lpg) : 0;
            double latestBaku = expenseDao.getLatestUnitPrice(
                    Expense.CAT_AIR_BAKU, DatabaseHelper.COL_EXPENSE_LITERS);
            double costPerGalonBaku = latestBaku > 0 ? latestBaku * lpg : avgBakuPerGalon;

            double segelPerPcs = unitCost(expenseDao, Expense.CAT_SEGEL,
                    DatabaseHelper.COL_EXPENSE_PCS);
            double tutupPerPcs = unitCost(expenseDao, Expense.CAT_TUTUP,
                    DatabaseHelper.COL_EXPENSE_PCS);
            double costTotal = costPerGalonBaku + segelPerPcs + tutupPerPcs;
            double laba = avgPrice - costTotal;

            PdfDocument pdf = new PdfDocument();
            PdfDocument.Page page = pdf.startPage(
                    new PdfDocument.PageInfo.Builder((int) W, (int) H, 1).create());
            Canvas c = page.getCanvas();
            Paint pTitle = paint(15f, Color.BLACK, true);
            Paint pSec = paint(12f, Color.parseColor("#1565C0"), true);
            Paint pLbl = paint(10f, Color.parseColor("#444444"), false);
            Paint pVal = paint(10f, Color.BLACK, true);
            Paint pNote = paint(8.5f, Color.parseColor("#9E9E9E"), false);

            float[] y = {MT};
            c.drawText("ANALISA & PREDIKSI", ML, y[0], pTitle); y[0] += 15f;
            c.drawText(depot + "  •  " + WINDOW_DAYS + " hari terakhir  •  dibuat "
                    + SDF_DISP.format(new Date()), ML, y[0], pNote); y[0] += 18f;

            section(c, y, "Penjualan", pSec);
            row(c, y, "Galon air terjual", galon30 + " galon", pLbl, pVal);
            row(c, y, "Rata-rata per hari", oneDec(dailyGalon) + " galon", pLbl, pVal);
            row(c, y, "Harga rata-rata / galon", "Rp " + NF.format(Math.round(avgPrice)), pLbl, pVal);
            row(c, y, "Pendapatan", "Rp " + NF.format(Math.round(rev30)), pLbl, pVal);
            row(c, y, "Proyeksi pendapatan / bulan",
                    "Rp " + NF.format(Math.round(dailyGalon * 30 * avgPrice)), pLbl, pVal);

            section(c, y, "Air Baku & Prediksi Order", pSec);
            row(c, y, "Air baku dibeli", NF.format(Math.round(litersBought)) + " L", pLbl, pVal);
            row(c, y, "Air baku terpakai (≈)", NF.format(Math.round(litersConsumed)) + " L", pLbl, pVal);
            row(c, y, "Sisa air baku (≈)", NF.format(Math.round(sisaLiter)) + " L", pLbl, pVal);
            row(c, y, "Konsumsi / hari (≈)", NF.format(Math.round(dailyLiters)) + " L", pLbl, pVal);
            note(c, y, "Prediksi: " + literPrediction(litersBought, dailyLiters, sisaLiter), pNote);

            section(c, y, "Perlengkapan (Segel & Tutup)", pSec);
            pcsBlock(c, y, expenseDao, trxDao, Expense.CAT_SEGEL, "Segel", dailyGalon, today, pLbl, pVal, pNote);
            pcsBlock(c, y, expenseDao, trxDao, Expense.CAT_TUTUP, "Tutup", dailyGalon, today, pLbl, pVal, pNote);

            section(c, y, "Biaya & Laba per Galon (harga terbaru)", pSec);
            row(c, y, "Biaya air baku / galon", "Rp " + NF.format(Math.round(costPerGalonBaku)), pLbl, pVal);
            row(c, y, "Biaya segel / galon", "Rp " + NF.format(Math.round(segelPerPcs)), pLbl, pVal);
            row(c, y, "Biaya tutup / galon", "Rp " + NF.format(Math.round(tutupPerPcs)), pLbl, pVal);
            row(c, y, "Total biaya / galon", "Rp " + NF.format(Math.round(costTotal)), pLbl, pVal);
            row(c, y, "Harga jual rata-rata / galon", "Rp " + NF.format(Math.round(avgPrice)), pLbl, pVal);
            row(c, y, "Laba kotor / galon", "Rp " + NF.format(Math.round(laba)), pLbl,
                    paint(10f, laba < 0 ? Color.parseColor("#C62828") : Color.parseColor("#2E7D32"), true));

            y[0] += 6f;
            note(c, y, "Asumsi 1 galon = " + oneDec(lpg) + " L, 1 galon = 1 segel + 1 tutup. "
                    + "Laba kotor pakai harga beli terbaru; belum termasuk biaya operasional lain.",
                    pNote);

            pdf.finishPage(page);

            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) { pdf.close(); return null; }
            File file = new File(dir, "Analisa_Prediksi_"
                    + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".pdf");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                pdf.writeTo(fos);
            }
            pdf.close();
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    private static double unitCost(ExpenseDao dao, String cat, String pcsCol) {
        double bought = dao.getTotalPcsByCategory(cat);
        double cost = dao.getTotalAmountByCategory(cat);
        double avg = bought > 0 ? cost / bought : 0;
        double latest = dao.getLatestUnitPrice(cat, pcsCol);
        return latest > 0 ? latest : avg;
    }

    private static void pcsBlock(Canvas c, float[] y, ExpenseDao dao, TransactionDao trxDao,
                                 String cat, String label, double dailyGalon, String today,
                                 Paint pLbl, Paint pVal, Paint pNote) {
        double bought = dao.getTotalPcsByCategory(cat);
        String first = dao.getEarliestDateByCategoryPcs(cat);
        int galonSince = first != null ? (int) trxDao.getSummaryByDateRange(first, today)[1] : 0;
        double sisa = bought - galonSince;
        row(c, y, label + " dibeli", NF.format(Math.round(bought)) + " pcs", pLbl, pVal);
        row(c, y, label + " sisa (≈)", NF.format(Math.round(sisa)) + " pcs", pLbl, pVal);
        String pred;
        if (bought <= 0) pred = "belum ada data " + label.toLowerCase(Locale.US) + ".";
        else if (dailyGalon <= 0) pred = "belum ada penjualan untuk prediksi.";
        else if (sisa <= 0) pred = "⚠ stok habis — segera order!";
        else {
            int daysLeft = (int) Math.floor(sisa / dailyGalon);
            Calendar rc = Calendar.getInstance();
            rc.add(Calendar.DAY_OF_YEAR, daysLeft);
            pred = "cukup ± " + daysLeft + " hari (order ~" + SDF_DISP.format(rc.getTime()) + ").";
        }
        note(c, y, "Prediksi " + label + ": " + pred, pNote);
    }

    private static String literPrediction(double bought, double dailyLiters, double sisa) {
        if (bought <= 0) return "belum ada data belanja air baku.";
        if (dailyLiters <= 0) return "belum ada penjualan untuk prediksi konsumsi.";
        if (sisa <= 0) return "⚠ stok air baku habis — segera order!";
        int daysLeft = (int) Math.floor(sisa / dailyLiters);
        Calendar rc = Calendar.getInstance();
        rc.add(Calendar.DAY_OF_YEAR, daysLeft);
        return "cukup ± " + daysLeft + " hari lagi (order ~" + SDF_DISP.format(rc.getTime()) + ").";
    }

    private static void section(Canvas c, float[] y, String title, Paint p) {
        y[0] += 8f;
        c.drawText(title, ML, y[0], p);
        y[0] += 14f;
    }

    private static void row(Canvas c, float[] y, String label, String value, Paint pLbl, Paint pVal) {
        c.drawText(label, ML + 6f, y[0], pLbl);
        c.drawText(value, W - MR - pVal.measureText(value), y[0], pVal);
        y[0] += 15f;
    }

    private static void note(Canvas c, float[] y, String text, Paint p) {
        for (String ln : text.split("\\r?\\n")) {
            c.drawText(ln, ML + 6f, y[0], p);
            y[0] += 11f;
        }
    }

    private static String oneDec(double v) {
        return String.format(new Locale("id", "ID"), "%.1f", v);
    }

    private static Paint paint(float size, int color, boolean bold) {
        Paint p = new Paint();
        p.setColor(color);
        p.setTextSize(size);
        p.setFakeBoldText(bold);
        p.setAntiAlias(true);
        return p;
    }
}
