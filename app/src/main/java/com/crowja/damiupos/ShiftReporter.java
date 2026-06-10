package com.crowja.damiupos;

import android.content.Context;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.Expense;
import com.crowja.damiupos.model.Transaction;

import java.io.File;
import java.io.FileWriter;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Laporan shift saat clock out (fitur multi user & absensi):
 *  - ringkasan teks → dikirim ke WhatsApp nomor admin (configurable),
 *  - file CSV detail → disimpan di folder exports (sama dengan Export).
 */
public final class ShiftReporter {

    private ShiftReporter() {}

    private static final SimpleDateFormat SDF_DB =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_DATE =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_TIME =
            new SimpleDateFormat("HH:mm", Locale.US);

    /** Hasil perhitungan shift untuk ringkasan & CSV. */
    public static class Shift {
        public String clockIn;      // ts IN pertama shift ini
        public int breakCount;      // berapa kali istirahat
        public long workMillis;     // total kerja (IN → BREAK/sekarang)
        public long breakMillis;    // total istirahat (BREAK → IN berikut)
    }

    /** Hitung shift berjalan user dari log absensi (sampai "sekarang"). */
    public static Shift computeShift(DatabaseHelper dbHelper, long userId) {
        Shift s = new Shift();
        List<Attendance> events = new AttendanceDao(dbHelper).getCurrentShiftEvents(userId);
        long now = System.currentTimeMillis();
        long segStart = -1;   // awal segmen kerja (IN)
        long breakStart = -1; // awal segmen istirahat (BREAK)
        for (Attendance a : events) {
            long t = parseMillis(a.getTs(), now);
            if (Attendance.EVENT_IN.equals(a.getEvent())) {
                if (s.clockIn == null) s.clockIn = a.getTs();
                if (breakStart >= 0) {
                    s.breakMillis += Math.max(0, t - breakStart);
                    breakStart = -1;
                }
                segStart = t;
            } else if (Attendance.EVENT_BREAK.equals(a.getEvent())) {
                if (segStart >= 0) {
                    s.workMillis += Math.max(0, t - segStart);
                    segStart = -1;
                }
                s.breakCount++;
                breakStart = t;
            }
        }
        if (segStart >= 0) s.workMillis += Math.max(0, now - segStart);
        if (breakStart >= 0) s.breakMillis += Math.max(0, now - breakStart);
        return s;
    }

    /** Ringkasan teks untuk dikirim ke admin via WhatsApp. */
    public static String buildSummaryText(Context ctx, DatabaseHelper dbHelper,
                                          long userId, String userName) {
        SettingsDao settings = new SettingsDao(dbHelper);
        TransactionDao trxDao = new TransactionDao(dbHelper);
        ExpenseDao expenseDao = new ExpenseDao(dbHelper);
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

        String today = SDF_DATE.format(new Date());
        Shift shift = computeShift(dbHelper, userId);
        double[] sum = trxDao.getSummaryByDateRange(today, today);
        double totalExpense = 0;
        for (Expense e : expenseDao.getByDateRange(today, today)) {
            totalExpense += e.getAmount();
        }

        String depot = settings.getDepotName();
        if (depot == null || depot.isEmpty()) depot = "DAMIU POS";

        StringBuilder sb = new StringBuilder();
        sb.append("*LAPORAN SHIFT — ").append(depot).append("*\n\n");
        sb.append("Operator: ").append(userName).append("\n");
        sb.append("Tanggal: ").append(formatDateId(today)).append("\n");
        sb.append("Clock In: ").append(formatTime(shift.clockIn)).append("\n");
        sb.append("Pulang: ").append(SDF_TIME.format(new Date())).append("\n");
        sb.append("Durasi Kerja: ").append(formatDuration(shift.workMillis)).append("\n");
        if (shift.breakCount > 0) {
            sb.append("Istirahat: ").append(shift.breakCount).append("x (")
              .append(formatDuration(shift.breakMillis)).append(")\n");
        }
        sb.append("\n*Ringkasan Hari Ini*\n");
        sb.append("Transaksi: ").append((int) sum[0]).append("\n");
        sb.append("Galon Keluar: ").append((int) sum[1]).append("\n");
        sb.append("Galon Kembali: ").append((int) sum[2]).append("\n");
        sb.append("Pendapatan: Rp ").append(nf.format(sum[3])).append("\n");
        sb.append("Pengeluaran: Rp ").append(nf.format(totalExpense)).append("\n");
        sb.append("Laba Bersih: Rp ").append(nf.format(sum[3] - totalExpense)).append("\n");
        return sb.toString();
    }

    /**
     * Export CSV detail transaksi hari ini + ringkasan shift.
     * Return file yang ditulis, atau null kalau gagal.
     */
    public static File exportShiftCsv(Context ctx, DatabaseHelper dbHelper,
                                      long userId, String userName) {
        TransactionDao trxDao = new TransactionDao(dbHelper);
        ExpenseDao expenseDao = new ExpenseDao(dbHelper);
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

        String today = SDF_DATE.format(new Date());
        Shift shift = computeShift(dbHelper, userId);
        double[] sum = trxDao.getSummaryByDateRange(today, today);
        List<Transaction> transactions = trxDao.getByDateRange(today, today);
        double totalExpense = 0;
        for (Expense e : expenseDao.getByDateRange(today, today)) {
            totalExpense += e.getAmount();
        }

        StringBuilder csv = new StringBuilder();
        csv.append("LAPORAN SHIFT DAMIU POS\n");
        csv.append("Operator,").append(esc(userName)).append("\n");
        csv.append("Tanggal,").append(today).append("\n");
        csv.append("Clock In,").append(esc(shift.clockIn != null ? shift.clockIn : "-")).append("\n");
        csv.append("Clock Out,").append(SDF_DB.format(new Date())).append("\n");
        csv.append("Durasi Kerja,").append(formatDuration(shift.workMillis)).append("\n");
        csv.append("Istirahat,").append(shift.breakCount).append("x (")
           .append(formatDuration(shift.breakMillis)).append(")\n\n");

        csv.append("RINGKASAN HARI INI\n");
        csv.append("Total Transaksi,").append((int) sum[0]).append("\n");
        csv.append("Galon Keluar,").append((int) sum[1]).append("\n");
        csv.append("Galon Kembali,").append((int) sum[2]).append("\n");
        csv.append("Total Pendapatan,Rp ").append(nf.format(sum[3])).append("\n");
        csv.append("Total Pengeluaran,Rp ").append(nf.format(totalExpense)).append("\n");
        csv.append("Laba Bersih,Rp ").append(nf.format(sum[3] - totalExpense)).append("\n\n");

        if (!transactions.isEmpty()) {
            csv.append("DETAIL TRANSAKSI\n");
            csv.append("No,Tanggal,Pelanggan,Tipe,Jumlah Galon,Total Harga\n");
            int no = 1;
            for (Transaction t : transactions) {
                csv.append(no++).append(",");
                csv.append(esc(t.getTanggal())).append(",");
                csv.append(esc(t.getCustomerName() != null ? t.getCustomerName() : "-")).append(",");
                csv.append(t.getType()).append(",");
                csv.append(t.getJumlahGalon()).append(",");
                csv.append(Transaction.TYPE_JUAL.equals(t.getType())
                        ? "Rp " + nf.format(t.getTotalHarga()) : "-");
                csv.append("\n");
            }
        }

        String safeName = userName != null
                ? userName.replaceAll("[^A-Za-z0-9]", "_") : "user";
        String fileName = "Shift_" + safeName + "_" + today + "_"
                + new SimpleDateFormat("HHmm", Locale.US).format(new Date()) + ".csv";
        File dir = new File(ctx.getExternalFilesDir(null), "exports");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File file = new File(dir, fileName);
        try (FileWriter w = new FileWriter(file)) {
            w.write('\ufeff'); // BOM agar Excel baca UTF-8
            w.write(csv.toString());
            w.flush();
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Export laporan shift sebagai XLSX **terenkripsi** (ECMA-376 agile) dengan
     * {@code password} (PIN admin) → file di folder exports. Excel akan minta
     * password saat membuka. Return file, atau null kalau gagal (caller fallback
     * ke {@link #exportShiftCsv}).
     *
     * <p>Catch {@link Throwable} supaya kegagalan POI (mis. NoClassDefFoundError
     * di device lama) tidak meng-crash alur Pulang.
     */
    public static File exportEncryptedShiftXlsx(Context ctx, DatabaseHelper dbHelper,
                                                long userId, String userName,
                                                String password) {
        if (password == null || password.isEmpty()) return null;
        try {
            List<Object[]> rows = buildReportRows(dbHelper, userId, userName);
            byte[] plain = XlsxWriter.toBytes("Laporan Shift", rows);

            String today = SDF_DATE.format(new Date());
            String safeName = userName != null
                    ? userName.replaceAll("[^A-Za-z0-9]", "_") : "user";
            String fileName = "Shift_" + safeName + "_" + today + "_"
                    + new SimpleDateFormat("HHmm", Locale.US).format(new Date()) + ".xlsx";
            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, fileName);

            XlsxEncryptor.encrypt(plain, file, password);
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Baris-baris laporan shift untuk workbook XLSX (mirip isi CSV). */
    private static List<Object[]> buildReportRows(DatabaseHelper dbHelper,
                                                  long userId, String userName) {
        TransactionDao trxDao = new TransactionDao(dbHelper);
        ExpenseDao expenseDao = new ExpenseDao(dbHelper);
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        SettingsDao settings = new SettingsDao(dbHelper);

        String today = SDF_DATE.format(new Date());
        Shift shift = computeShift(dbHelper, userId);
        double[] sum = trxDao.getSummaryByDateRange(today, today);
        List<Transaction> transactions = trxDao.getByDateRange(today, today);
        double totalExpense = 0;
        for (Expense e : expenseDao.getByDateRange(today, today)) {
            totalExpense += e.getAmount();
        }
        String depot = settings.getDepotName();
        if (depot == null || depot.isEmpty()) depot = "DAMIU POS";

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"LAPORAN SHIFT — " + depot});
        rows.add(new Object[]{"Operator", userName});
        rows.add(new Object[]{"Tanggal", today});
        rows.add(new Object[]{"Clock In", shift.clockIn != null ? shift.clockIn : "-"});
        rows.add(new Object[]{"Pulang", SDF_DB.format(new Date())});
        rows.add(new Object[]{"Durasi Kerja", formatDuration(shift.workMillis)});
        rows.add(new Object[]{"Istirahat",
                shift.breakCount + "x (" + formatDuration(shift.breakMillis) + ")"});
        rows.add(new Object[]{});
        rows.add(new Object[]{"RINGKASAN HARI INI"});
        rows.add(new Object[]{"Total Transaksi", (int) sum[0]});
        rows.add(new Object[]{"Galon Keluar", (int) sum[1]});
        rows.add(new Object[]{"Galon Kembali", (int) sum[2]});
        rows.add(new Object[]{"Total Pendapatan", "Rp " + nf.format(sum[3])});
        rows.add(new Object[]{"Total Pengeluaran", "Rp " + nf.format(totalExpense)});
        rows.add(new Object[]{"Laba Bersih", "Rp " + nf.format(sum[3] - totalExpense)});

        if (!transactions.isEmpty()) {
            rows.add(new Object[]{});
            rows.add(new Object[]{"DETAIL TRANSAKSI"});
            rows.add(new Object[]{"No", "Tanggal", "Pelanggan", "Tipe",
                    "Jumlah Galon", "Total Harga"});
            int no = 1;
            for (Transaction t : transactions) {
                rows.add(new Object[]{
                        no++,
                        t.getTanggal() != null ? t.getTanggal() : "",
                        t.getCustomerName() != null ? t.getCustomerName() : "-",
                        t.getType(),
                        t.getJumlahGalon(),
                        Transaction.TYPE_JUAL.equals(t.getType())
                                ? "Rp " + nf.format(t.getTotalHarga()) : "-"
                });
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------- utils

    private static long parseMillis(String ts, long fallback) {
        if (ts == null) return fallback;
        try {
            Date d = SDF_DB.parse(ts);
            return d != null ? d.getTime() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatTime(String ts) {
        if (ts == null) return "-";
        try {
            Date d = SDF_DB.parse(ts);
            return d != null ? SDF_TIME.format(d) : ts;
        } catch (Exception e) {
            return ts.length() >= 16 ? ts.substring(11, 16) : ts;
        }
    }

    private static String formatDateId(String yyyyMmDd) {
        try {
            Date d = SDF_DATE.parse(yyyyMmDd);
            return d != null
                    ? new SimpleDateFormat("EEEE, dd MMM yyyy", new Locale("id", "ID")).format(d)
                    : yyyyMmDd;
        } catch (Exception e) {
            return yyyyMmDd;
        }
    }

    /** "7j 25m" / "45m" */
    public static String formatDuration(long millis) {
        long totalMin = Math.max(0, millis / 60000L);
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h > 0 ? h + "j " + m + "m" : m + "m";
    }

    private static String esc(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
