package com.crowja.damiupos;

import android.content.Context;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.User;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rekapitulasi absensi staff → XLSX (untuk export manual range tanggal &
 * auto-kirim bulanan saat cut-off). Per staff per hari: Masuk, Istirahat
 * (bisa banyak), Selesai Istirahat, Pulang, durasi kerja, lembur hari (kerja di
 * atas jam normal/hari). Final per staff dinormalisasi bulanan: jam wajib =
 * hari kerja × jam normal/hari; surplus → Lembur, defisit → Kekurangan.
 */
public final class AttendanceRecap {

    private AttendanceRecap() {}

    private static final SimpleDateFormat SDF_DB =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_DATE =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    // ---------------------------------------------------------------- periode

    /**
     * Periode bulanan berdasarkan cut-off untuk "hari ini": dari (cutoff+1)
     * bulan lalu s/d (cutoff) bulan ini. Return {startDate, endDate} "yyyy-MM-dd".
     */
    public static String[] monthlyPeriod(int cutoffDay) {
        Calendar end = Calendar.getInstance();
        int dim = end.getActualMaximum(Calendar.DAY_OF_MONTH);
        end.set(Calendar.DAY_OF_MONTH, Math.min(cutoffDay, dim));
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.MONTH, -1);
        start.add(Calendar.DAY_OF_MONTH, 1); // (cutoff bln lalu) + 1 hari
        return new String[]{SDF_DATE.format(start.getTime()), SDF_DATE.format(end.getTime())};
    }

    /** True kalau hari ini adalah tanggal cut-off (atau akhir bulan kalau cutoff
     *  melebihi jumlah hari bulan ini). */
    public static boolean isCutoffToday(int cutoffDay) {
        Calendar now = Calendar.getInstance();
        int dim = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        return now.get(Calendar.DAY_OF_MONTH) == Math.min(cutoffDay, dim);
    }

    // ------------------------------------------------------------------ export

    /**
     * Bangun rekap → XLSX terenkripsi (password) di folder exports.
     * Return file, atau null kalau gagal. {@code dailyNormalHours} = jam kerja
     * normal per hari (ambang lembur harian + basis normalisasi bulanan).
     */
    public static File exportEncrypted(Context ctx, DatabaseHelper dbHelper,
                                       String startDate, String endDate,
                                       double dailyNormalHours, String password) {
        if (password == null || password.isEmpty()) return null;
        try {
            List<Object[]> rows = buildRows(dbHelper, startDate, endDate, dailyNormalHours);
            byte[] plain = XlsxWriter.toBytes("Rekap Absensi", rows);
            String fileName = "RekapAbsensi_" + startDate + "_sd_" + endDate + ".xlsx";
            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, fileName);
            XlsxEncryptor.encrypt(plain, file, password);
            return file;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------- rows

    public static List<Object[]> buildRows(DatabaseHelper dbHelper, String startDate,
                                           String endDate, double dailyNormalHours) {
        UserDao userDao = new UserDao(dbHelper);
        AttendanceDao attDao = new AttendanceDao(dbHelper);
        SettingsDao settings = new SettingsDao(dbHelper);
        String depot = settings.getDepotName();
        if (depot == null || depot.isEmpty()) depot = "DAMIU POS";

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"REKAPITULASI ABSENSI — " + depot});
        rows.add(new Object[]{"Periode", startDate + " s/d " + endDate});
        rows.add(new Object[]{"Jam kerja normal/hari", dailyNormalHours});
        rows.add(new Object[]{"Catatan", "Lembur Hari = kerja di atas jam normal hari itu. "
                + "Final dinormalisasi: jam wajib = hari kerja × jam normal. "
                + "Surplus → Lembur, kurang → Kekurangan."});
        rows.add(new Object[]{});
        rows.add(new Object[]{"Nama Staff", "Tanggal", "Masuk", "Istirahat",
                "Selesai Istirahat", "Pulang", "Durasi Kerja (jam)", "Lembur Hari (jam)"});

        for (User u : userDao.getAll()) {
            List<Attendance> events = attDao.getEventsByUserBetween(u.getId(), startDate, endDate);
            if (events.isEmpty()) continue;

            // Kelompokkan event per tanggal (urut, karena query sudah ASC).
            LinkedHashMap<String, List<Attendance>> byDay = new LinkedHashMap<>();
            for (Attendance a : events) {
                String d = a.getTs() != null && a.getTs().length() >= 10
                        ? a.getTs().substring(0, 10) : "?";
                List<Attendance> l = byDay.get(d);
                if (l == null) { l = new ArrayList<>(); byDay.put(d, l); }
                l.add(a);
            }

            double totalHours = 0;
            int workingDays = 0;
            for (Map.Entry<String, List<Attendance>> e : byDay.entrySet()) {
                DayResult dr = computeDay(e.getValue());
                double dailyOt = Math.max(0, dr.workHours - dailyNormalHours);
                rows.add(new Object[]{u.getName(), e.getKey(), dr.masuk, dr.istirahat,
                        dr.selesai, dr.pulang, round2(dr.workHours), round2(dailyOt)});
                totalHours += dr.workHours;
                if (!"-".equals(dr.masuk)) workingDays++; // hari yang benar2 kerja
            }

            // Normalisasi bulanan: jam wajib = hari kerja × jam normal/hari.
            double required = workingDays * dailyNormalHours;
            double net = totalHours - required;
            double lembur = Math.max(0, net);       // surplus → lembur
            double kekurangan = Math.max(0, -net);  // defisit → kekurangan

            rows.add(new Object[]{"", "TOTAL " + u.getName(), "Hari kerja", workingDays,
                    "Jam wajib", round2(required), "Total kerja", round2(totalHours)});
            rows.add(new Object[]{"", "", "", "", "", "", "Lembur (jam)", round2(lembur)});
            rows.add(new Object[]{"", "", "", "", "", "", "Kekurangan (jam)", round2(kekurangan)});
            rows.add(new Object[]{});
        }
        return rows;
    }

    private static class DayResult {
        String masuk = "-", pulang = "-", istirahat = "", selesai = "";
        double workHours = 0;
    }

    /** Hitung satu hari dari event-event kronologisnya. */
    private static DayResult computeDay(List<Attendance> evs) {
        DayResult r = new DayResult();
        List<String> brk = new ArrayList<>();
        List<String> res = new ArrayList<>();
        long segStart = -1;
        boolean pendingBreak = false;
        boolean masukSet = false;

        for (Attendance a : evs) {
            long t = parseMillis(a.getTs());
            String hm = hm(a.getTs());
            if (Attendance.EVENT_IN.equals(a.getEvent())) {
                if (!masukSet) { r.masuk = hm; masukSet = true; }
                else if (pendingBreak) { res.add(hm); } // selesai istirahat
                pendingBreak = false;
                segStart = t;
            } else if (Attendance.EVENT_BREAK.equals(a.getEvent())) {
                if (segStart >= 0) { r.workHours += (t - segStart) / 3600000.0; segStart = -1; }
                brk.add(hm);
                pendingBreak = true;
            } else if (Attendance.EVENT_OUT.equals(a.getEvent())) {
                if (segStart >= 0) { r.workHours += (t - segStart) / 3600000.0; segStart = -1; }
                if (pendingBreak) { res.add(hm); pendingBreak = false; }
                r.pulang = hm;
            }
        }
        r.istirahat = join(brk);
        r.selesai = join(res);
        if (r.workHours < 0) r.workHours = 0;
        return r;
    }

    // ------------------------------------------------------------------ utils

    private static long parseMillis(String ts) {
        if (ts == null) return 0;
        try {
            Date d = SDF_DB.parse(ts);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String hm(String ts) {
        return ts != null && ts.length() >= 16 ? ts.substring(11, 16) : (ts != null ? ts : "-");
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
