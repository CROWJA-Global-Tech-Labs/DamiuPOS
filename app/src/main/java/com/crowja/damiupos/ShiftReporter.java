package com.crowja.damiupos;

import android.content.Context;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Expense;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Laporan shift saat Pulang (clock out): ringkasan TEKS yang dikirim ke email
 * admin sebagai body (tanpa file XLSX). Memuat absensi shift, ringkasan
 * penjualan + detail air minum per jenis, dan daftar follow-up hari ini.
 */
public final class ShiftReporter {

    private ShiftReporter() {}

    private static final SimpleDateFormat SDF_DB =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_DATE =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_TIME =
            new SimpleDateFormat("HH:mm", Locale.US);

    /** Hasil perhitungan shift untuk ringkasan. */
    public static class Shift {
        public String clockIn;      // ts IN pertama shift ini
        public int breakCount;      // berapa kali istirahat
        public long workMillis;     // total kerja (IN → BREAK/sekarang)
        public long breakMillis;    // total istirahat (BREAK → IN berikut)
    }

    /**
     * Total kerja HARI INI (kalender lokal) untuk user: jumlah segmen
     * IN&nbsp;→&nbsp;BREAK/OUT (atau IN&nbsp;→&nbsp;sekarang untuk segmen terbuka),
     * dibatasi hanya event hari ini. Beda dari {@link #computeShift} yang
     * mengikuti "shift berjalan" tanpa batas hari — dipakai untuk pengingat jam
     * kerja &amp; popup apresiasi supaya akurat "jam kerja hari ini" walaupun
     * shift dibiarkan terbuka lintas hari (lupa Pulang).
     */
    public static long workedMillisToday(DatabaseHelper dbHelper, long userId) {
        String today = SDF_DATE.format(new Date());
        List<Attendance> evs = new AttendanceDao(dbHelper)
                .getEventsByUserBetween(userId, today, today);
        long now = System.currentTimeMillis();
        long workedMs = 0;
        long segStart = -1;
        for (Attendance a : evs) {
            long t = parseMillis(a.getTs(), now);
            if (Attendance.EVENT_IN.equals(a.getEvent())) {
                segStart = t;
            } else { // BREAK atau OUT menutup segmen kerja terbuka
                if (segStart >= 0) {
                    workedMs += Math.max(0, t - segStart);
                    segStart = -1;
                }
            }
        }
        if (segStart >= 0) workedMs += Math.max(0, now - segStart);
        return workedMs;
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

    /** Ringkasan teks laporan shift (jadi body email ke admin). */
    public static String buildSummaryText(Context ctx, DatabaseHelper dbHelper,
                                          long userId, String userName) {
        SettingsDao settings = new SettingsDao(dbHelper);
        TransactionDao trxDao = new TransactionDao(dbHelper);
        ExpenseDao expenseDao = new ExpenseDao(dbHelper);
        CustomerDao customerDao = new CustomerDao(dbHelper);
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

        String today = SDF_DATE.format(new Date());
        Shift shift = computeShift(dbHelper, userId);
        double[] sum = trxDao.getSummaryByDateRange(today, today);
        List<Transaction> transactions = trxDao.getByDateRange(today, today);
        List<Expense> expenses = expenseDao.getByDateRange(today, today);
        double totalExpense = 0;
        for (Expense e : expenses) {
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

        // Detail air minum terjual per jenis.
        appendWaterProducts(sb, transactions, nf);

        // Rincian pembayaran per metode (Tunai/QRIS/Transfer).
        appendPaymentMethods(sb, transactions, nf);

        // Rincian tiap pengeluaran (pengeluaran) hari ini.
        appendExpenses(sb, expenses, nf);

        // Daftar pelanggan yang di-follow-up hari ini.
        List<Customer> followedToday = customerDao.getFollowedUpOn(today);
        appendFollowUps(sb, followedToday);

        // Pelanggan yang MASIH perlu di-follow-up tapi belum dihubungi hari ini.
        java.util.Set<Long> followedIds = new java.util.HashSet<>();
        for (Customer c : followedToday) followedIds.add(c.getId());
        appendFollowUpPending(sb,
                customerDao.getFollowUpCandidates(settings.getFollowupDays()), followedIds);

        return sb.toString();
    }

    /** Rincian galon + pendapatan per jenis air minum (transaksi JUAL hari ini). */
    private static void appendWaterProducts(StringBuilder sb, List<Transaction> transactions,
                                            NumberFormat nf) {
        Map<String, double[]> agg = new LinkedHashMap<>(); // nama -> {galon, pendapatan}
        for (Transaction t : transactions) {
            if (!Transaction.TYPE_JUAL.equals(t.getType())) continue;
            // Lewati transaksi jual botol kosong (bukan air minum).
            if (t.getCatatan() != null && t.getCatatan().contains("[JUAL BOTOL KOSONG]")) continue;

            List<TransactionItem> items = t.getItems();
            if (items != null && !items.isEmpty()) {
                for (TransactionItem it : items) {
                    String name = it.productName != null && !it.productName.isEmpty()
                            ? it.productName : "Lainnya";
                    double[] v = agg.get(name);
                    if (v == null) { v = new double[2]; agg.put(name, v); }
                    v[0] += it.jumlah;
                    v[1] += it.getSubtotal();
                }
            } else {
                String name = t.getProductName() != null && !t.getProductName().isEmpty()
                        ? t.getProductName() : "Lainnya";
                double[] v = agg.get(name);
                if (v == null) { v = new double[2]; agg.put(name, v); }
                v[0] += t.getJumlahGalon();
                v[1] += t.getTotalHarga();
            }
        }

        sb.append("\n*Air Minum Terjual (per jenis)*\n");
        if (agg.isEmpty()) {
            sb.append("- (tidak ada)\n");
            return;
        }
        for (Map.Entry<String, double[]> e : agg.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ")
              .append((int) e.getValue()[0]).append(" galon (Rp ")
              .append(nf.format(e.getValue()[1])).append(")\n");
        }
    }

    /** Rincian pendapatan JUAL per metode pembayaran. */
    private static void appendPaymentMethods(StringBuilder sb, List<Transaction> transactions,
                                             NumberFormat nf) {
        double tunai = 0, qris = 0, transfer = 0, lainnya = 0;
        for (Transaction t : transactions) {
            if (!Transaction.TYPE_JUAL.equals(t.getType())) continue;
            double amt = t.getTotalHarga();
            String pm = t.getPaymentMethod();
            if (Transaction.PAY_TUNAI.equals(pm)) tunai += amt;
            else if (Transaction.PAY_QRIS.equals(pm)) qris += amt;
            else if (Transaction.PAY_TRANSFER.equals(pm)) transfer += amt;
            else lainnya += amt; // transaksi lama tanpa metode tercatat
        }
        sb.append("\n*Pembayaran (JUAL)*\n");
        sb.append("Tunai: Rp ").append(nf.format(tunai)).append("\n");
        sb.append("QRIS: Rp ").append(nf.format(qris)).append("\n");
        sb.append("Transfer: Rp ").append(nf.format(transfer)).append("\n");
        if (lainnya > 0) {
            sb.append("Tidak dicatat: Rp ").append(nf.format(lainnya)).append("\n");
        }
    }

    /** Rincian tiap pengeluaran hari ini: nama + nominal (+ catatan kalau ada). */
    private static void appendExpenses(StringBuilder sb, List<Expense> expenses, NumberFormat nf) {
        double total = 0;
        for (Expense e : expenses) total += e.getAmount();
        sb.append("\n*Rincian Pengeluaran* (Rp ").append(nf.format(Math.round(total))).append(")\n");
        if (expenses.isEmpty()) {
            sb.append("- (tidak ada)\n");
            return;
        }
        for (Expense e : expenses) {
            String name = e.getName() != null && !e.getName().isEmpty()
                    ? e.getName() : "(tanpa nama)";
            sb.append("- ").append(name).append(": Rp ")
              .append(nf.format(Math.round(e.getAmount())));
            if (e.getNote() != null && !e.getNote().isEmpty()) {
                sb.append("  ·  ").append(e.getNote());
            }
            sb.append("\n");
        }
    }

    /** Jumlah + nama pelanggan yang di-follow-up hari ini. */
    private static void appendFollowUps(StringBuilder sb, List<Customer> followed) {
        sb.append("\n*Follow Up Hari Ini* (").append(followed.size()).append(")\n");
        if (followed.isEmpty()) {
            sb.append("- (tidak ada)\n");
            return;
        }
        for (Customer c : followed) {
            sb.append("- ").append(c.getName() != null ? c.getName() : "(tanpa nama)").append("\n");
        }
    }

    /**
     * Pelanggan yang MASIH perlu di-follow-up (sudah lewat ambang hari sejak
     * pembelian terakhir, belum dikecualikan) TAPI belum dihubungi hari ini.
     * Detail: nama + tanggal beli terakhir, supaya shift berikutnya tahu siapa
     * yang tersisa.
     */
    private static void appendFollowUpPending(StringBuilder sb, List<Customer> candidates,
                                              java.util.Set<Long> followedTodayIds) {
        List<Customer> pending = new java.util.ArrayList<>();
        for (Customer c : candidates) {
            if (!followedTodayIds.contains(c.getId())) pending.add(c);
        }
        sb.append("\n*Belum Di Follow Up* (").append(pending.size()).append(")\n");
        if (pending.isEmpty()) {
            sb.append("- (tidak ada)\n");
            return;
        }
        for (Customer c : pending) {
            sb.append("- ").append(c.getName() != null ? c.getName() : "(tanpa nama)");
            // getFollowUpCandidates meng-overload createdAt dengan ts beli terakhir.
            String lastJual = c.getCreatedAt();
            if (lastJual != null && lastJual.length() >= 10) {
                sb.append(" (terakhir beli ").append(lastJual, 0, 10).append(")");
            }
            sb.append("\n");
        }
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

    /** Bentuk panjang untuk popup apresiasi: "7 jam 32 menit" / "45 menit". */
    public static String formatDurationLong(long millis) {
        long totalMin = Math.max(0, millis / 60000L);
        long h = totalMin / 60;
        long m = totalMin % 60;
        return h > 0 ? h + " jam " + m + " menit" : m + " menit";
    }
}
