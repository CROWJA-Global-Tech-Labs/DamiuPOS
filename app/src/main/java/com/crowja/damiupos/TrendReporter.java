package com.crowja.damiupos;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Ringkasan tren bisnis 30 hari (vs 30 hari sebelumnya) sebagai teks — dipakai
 * di laporan cut-off bulanan. Metrik & ambang identik dengan chip tren di
 * dashboard ({@code MainActivity.refreshTrends}): rata-rata per hari + arah
 * NAIK/TURUN/STAGNAN (deadzone ±5%).
 */
public final class TrendReporter {

    private TrendReporter() {}

    private static final int WINDOW = 30;

    /** Bangun blok teks tren untuk dilampirkan ke body email laporan. */
    public static String buildText(DatabaseHelper db) {
        TransactionDao trxDao = new TransactionDao(db);
        CustomerDao custDao = new CustomerDao(db);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        String today = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_YEAR, -(WINDOW - 1));
        String curStart = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_YEAR, -1);
        String prevEnd = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_YEAR, -(WINDOW - 1));
        String prevStart = sdf.format(cal.getTime());

        double[] cur = trxDao.getSummaryByDateRange(curStart, today);   // [count,jual,kembali,rev]
        double[] prev = trxDao.getSummaryByDateRange(prevStart, prevEnd);
        double w = WINDOW;
        int curNew = custDao.getCountCreatedBetween(curStart, today);
        int prevNew = custDao.getCountCreatedBetween(prevStart, prevEnd);

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        StringBuilder sb = new StringBuilder();
        sb.append("TREN 30 HARI (vs 30 hari sebelumnya):\n");
        sb.append("- Pendapatan/hari: Rp ")
                .append(nf.format(Math.round(cur[3] / w)))
                .append(" (").append(dir(cur[3] / w, prev[3] / w)).append(")\n");
        sb.append("- Galon terjual/hari: ")
                .append(oneDecimal(cur[1] / w))
                .append(" (").append(dir(cur[1] / w, prev[1] / w)).append(")\n");
        sb.append("- Transaksi/hari: ")
                .append(oneDecimal(cur[0] / w))
                .append(" (").append(dir(cur[0] / w, prev[0] / w)).append(")\n");
        sb.append("- Penambahan galon beredar/hari: ")
                .append(oneDecimal((cur[1] - cur[2]) / w))
                .append(" (").append(dir((cur[1] - cur[2]) / w, (prev[1] - prev[2]) / w)).append(")\n");
        sb.append("- Pelanggan baru/hari: ")
                .append(oneDecimal(curNew / w))
                .append(" (").append(dir(curNew / w, prevNew / w)).append(")\n");
        return sb.toString();
    }

    /** NAIK / TURUN / STAGNAN dengan ambang ±5%. */
    private static String dir(double cur, double prev) {
        double base = Math.max(Math.abs(prev), 0.0001);
        double pct = (cur - prev) / base;
        if (pct > 0.05) return "NAIK";
        if (pct < -0.05) return "TURUN";
        return "STAGNAN";
    }

    private static String oneDecimal(double v) {
        return String.format(new Locale("id", "ID"), "%.1f", v);
    }
}
