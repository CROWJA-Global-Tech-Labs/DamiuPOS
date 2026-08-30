package com.crowja.damiupos;

import android.content.Context;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
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

    /**
     * Periode cut-off yang BERLANGSUNG (memuat hari ini): kalau hari ini ≤ cutoff
     * bulan ini → berakhir di cutoff bulan ini; kalau sudah lewat → berakhir di
     * cutoff bulan depan. Return {startDate, endDate} "yyyy-MM-dd".
     */
    public static String[] currentPeriod(int cutoffDay) {
        Calendar today = Calendar.getInstance();
        int dimThis = today.getActualMaximum(Calendar.DAY_OF_MONTH);
        int cutoffThis = Math.min(cutoffDay, dimThis);
        Calendar end = Calendar.getInstance();
        if (today.get(Calendar.DAY_OF_MONTH) <= cutoffThis) {
            end.set(Calendar.DAY_OF_MONTH, cutoffThis);
        } else {
            end.add(Calendar.MONTH, 1);
            int dimNext = end.getActualMaximum(Calendar.DAY_OF_MONTH);
            end.set(Calendar.DAY_OF_MONTH, Math.min(cutoffDay, dimNext));
        }
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.MONTH, -1);
        start.add(Calendar.DAY_OF_MONTH, 1);
        return new String[]{SDF_DATE.format(start.getTime()), SDF_DATE.format(end.getTime())};
    }

    /** Ringkasan jam kerja satu staf dalam periode (hingga hari ini). */
    public static final class PeriodSummary {
        public int workingDays;          // hari yang benar-benar kerja
        public double totalHours;        // total jam kerja terkumpul (termasuk shift terbuka hari ini)
        public double requiredHours;     // jam ideal = hari kerja ideal × jam ideal/hari
        public double diffHours;         // totalHours - requiredHours (+ lebih, - kurang)
        public double dailyOvertimeHours; // akumulasi lembur harian (jam di atas jam ideal/hari)
    }

    /**
     * Hitung akumulasi jam kerja staf dari {@code start} s/d hari ini dalam
     * periode cut-off: hari-hari lampau dari log (IN→OUT), hari ini memakai
     * {@code ShiftReporter.workedMillisToday} supaya shift terbuka ikut terhitung.
     * Jam ideal = hari kerja × {@code dailyNormalHours}.
     */
    public static PeriodSummary computePeriodSummary(DatabaseHelper db, long userId,
            String start, String end, double dailyNormalHours) {
        PeriodSummary ps = new PeriodSummary();
        String today = SDF_DATE.format(new Date());
        String rangeEnd = today.compareTo(end) < 0 ? today : end;   // jangan lewati hari ini
        AttendanceDao attDao = new AttendanceDao(db);
        List<Attendance> events = attDao.getEventsByUserBetween(userId, start, rangeEnd);

        LinkedHashMap<String, List<Attendance>> byDay = new LinkedHashMap<>();
        for (Attendance a : events) {
            String d = a.getTs() != null && a.getTs().length() >= 10
                    ? a.getTs().substring(0, 10) : "?";
            List<Attendance> l = byDay.get(d);
            if (l == null) { l = new ArrayList<>(); byDay.put(d, l); }
            l.add(a);
        }
        for (Map.Entry<String, List<Attendance>> e : byDay.entrySet()) {
            if (e.getKey().equals(today)) continue;   // hari ini dihitung terpisah
            DayResult dr = computeDay(e.getValue());
            ps.totalHours += dr.workHours;
            ps.dailyOvertimeHours += Math.max(0, dr.workHours - dailyNormalHours);
            if (!"-".equals(dr.masuk)) ps.workingDays++;
        }
        // Hari ini (termasuk shift yang masih berjalan).
        if (start.compareTo(today) <= 0 && today.compareTo(end) <= 0) {
            long todayMs = ShiftReporter.workedMillisToday(db, userId);
            if (todayMs > 0) {
                double todayH = todayMs / 3600000.0;
                ps.totalHours += todayH;
                ps.dailyOvertimeHours += Math.max(0, todayH - dailyNormalHours);
                ps.workingDays++;
            }
        }
        // Jam ideal periode = hari kerja ideal × jam kerja ideal/hari. Hari kerja
        // ideal diskalakan dari "hari kerja/pekan" sesuai panjang periode cut-off:
        //   idealDays = hariKerjaPerPekan × (jumlahHariPeriode ÷ 7).
        int workDaysPerWeek = new SettingsDao(db).getWorkDaysPerWeek();
        double periodDays = periodLengthDays(start, end);
        double idealDays = workDaysPerWeek * (periodDays / 7.0);
        ps.requiredHours = idealDays * dailyNormalHours;
        ps.diffHours = ps.totalHours - ps.requiredHours;
        return ps;
    }

    /** Jumlah hari (inklusif) dalam periode {@code start}..{@code end}
     *  ("yyyy-MM-dd"); minimal 1. */
    private static double periodLengthDays(String start, String end) {
        try {
            Date s = SDF_DATE.parse(start);
            Date e = SDF_DATE.parse(end);
            if (s != null && e != null) {
                long days = Math.round((e.getTime() - s.getTime()) / 86400000.0) + 1;
                return days >= 1 ? days : 1;
            }
        } catch (Exception ignored) {}
        return 30;   // fallback periode bulanan
    }

    /**
     * Periode cut-off SEBELUM periode berjalan (satu bulan lebih awal dari
     * {@link #monthlyPeriod}). Dipakai admin untuk generate slip periode lalu.
     */
    public static String[] previousPeriod(int cutoffDay) {
        Calendar end = Calendar.getInstance();
        end.add(Calendar.MONTH, -1);
        int dim = end.getActualMaximum(Calendar.DAY_OF_MONTH);
        end.set(Calendar.DAY_OF_MONTH, Math.min(cutoffDay, dim));
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.MONTH, -1);
        start.add(Calendar.DAY_OF_MONTH, 1);
        return new String[]{SDF_DATE.format(start.getTime()), SDF_DATE.format(end.getTime())};
    }

    /** True kalau hari ini adalah tanggal cut-off (atau akhir bulan kalau cutoff
     *  melebihi jumlah hari bulan ini). */
    public static boolean isCutoffToday(int cutoffDay) {
        Calendar now = Calendar.getInstance();
        int dim = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        return now.get(Calendar.DAY_OF_MONTH) == Math.min(cutoffDay, dim);
    }

    /**
     * Periode yang PALING BARU SELESAI (cut-off-nya sudah lewat/hari ini),
     * berdasarkan "hari ini". Dipakai untuk kirim/retry rekap: berlaku di hari
     * cut-off maupun hari-hari sesudahnya hingga periode berikutnya. Return
     * {startDate, endDate}.
     */
    public static String[] mostRecentCompletedPeriod(int cutoffDay) {
        Calendar today = Calendar.getInstance();
        int dimThis = today.getActualMaximum(Calendar.DAY_OF_MONTH);
        int cutoffThisMonth = Math.min(cutoffDay, dimThis);
        Calendar end = Calendar.getInstance();
        if (today.get(Calendar.DAY_OF_MONTH) >= cutoffThisMonth) {
            // Cut-off bulan ini sudah lewat / hari ini → periode berakhir bln ini.
            end.set(Calendar.DAY_OF_MONTH, cutoffThisMonth);
        } else {
            // Belum sampai cut-off → periode terakhir berakhir bulan lalu.
            end.add(Calendar.MONTH, -1);
            int dimPrev = end.getActualMaximum(Calendar.DAY_OF_MONTH);
            end.set(Calendar.DAY_OF_MONTH, Math.min(cutoffDay, dimPrev));
        }
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.MONTH, -1);
        start.add(Calendar.DAY_OF_MONTH, 1);
        return new String[]{SDF_DATE.format(start.getTime()), SDF_DATE.format(end.getTime())};
    }

    /**
     * Pekan yang PALING BARU SELESAI: berakhir pada kemunculan terakhir
     * {@code triggerDow} (Calendar.DAY_OF_WEEK) ≤ hari ini, mencakup 7 hari
     * (end-6 .. end). Return {startDate, endDate}.
     */
    public static String[] mostRecentCompletedWeek(int triggerDow) {
        Calendar end = Calendar.getInstance();
        int todayDow = end.get(Calendar.DAY_OF_WEEK);
        int diff = ((todayDow - triggerDow) % 7 + 7) % 7; // hari sejak trigger terakhir
        end.add(Calendar.DAY_OF_YEAR, -diff);
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_YEAR, -6);
        return new String[]{SDF_DATE.format(start.getTime()), SDF_DATE.format(end.getTime())};
    }

    // ------------------------------------------------------------------ export

    /** Ambang ukuran zip — di atas ini, foto dikecilkan ke 360p. */
    private static final long MAX_ZIP_BYTES = 18L * 1024 * 1024;

    /**
     * Bangun rekap → file ZIP (kompresi maksimum) berisi RekapAbsensi.xlsx +
     * folder foto (selfie masuk/pulang). Kalau zip > 18MB, foto dikecilkan ke
     * 360p lalu di-zip ulang. Tanpa enkripsi. Return file zip, atau null gagal.
     */
    public static File exportRecapZip(Context ctx, DatabaseHelper dbHelper,
                                      String startDate, String endDate,
                                      double dailyNormalHours) {
        try {
            List<Object[]> rows = buildRows(dbHelper, startDate, endDate, dailyNormalHours);
            byte[] xlsx = XlsxWriter.toBytes("Rekap Absensi", rows, COL_WIDTHS);
            List<PhotoEntry> photos = collectPhotos(dbHelper, startDate, endDate);

            File dir = new File(ctx.getExternalFilesDir(null), "exports");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File zip = new File(dir, "RekapAbsensi_" + startDate + "_sd_" + endDate + ".zip");

            writeZip(zip, xlsx, photos, 0);           // 0 = foto resolusi penuh
            if (zip.length() > MAX_ZIP_BYTES) {
                writeZip(zip, xlsx, photos, 360);     // kecilkan foto ke 360p
            }
            return zip;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class PhotoEntry {
        final File file;
        final String entryName;
        PhotoEntry(File file, String entryName) { this.file = file; this.entryName = entryName; }
    }

    /** Nama hari Indonesia dari "yyyy-MM-dd" (mis. "Rabu"). "" kalau gagal. */
    private static String dayNameId(String yyyyMMdd) {
        try {
            Date d = SDF_DATE.parse(yyyyMMdd);
            return d != null
                    ? new SimpleDateFormat("EEEE", new Locale("id", "ID")).format(d) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Kumpulkan foto selfie semua user dalam periode. */
    private static List<PhotoEntry> collectPhotos(DatabaseHelper dbHelper,
                                                  String start, String end) {
        List<PhotoEntry> out = new ArrayList<>();
        UserDao userDao = new UserDao(dbHelper);
        AttendanceDao attDao = new AttendanceDao(dbHelper);
        for (User u : userDao.getAll()) {
            String safe = u.getName() != null
                    ? u.getName().replaceAll("[^A-Za-z0-9]", "_") : "user";
            for (Attendance a : attDao.getEventsByUserBetween(u.getId(), start, end)) {
                String p = a.getPhotoPath();
                if (p == null || p.isEmpty()) continue;
                File f = new File(p);
                if (!f.exists()) continue;
                String ev = Attendance.EVENT_IN.equals(a.getEvent()) ? "MASUK"
                        : Attendance.EVENT_OUT.equals(a.getEvent()) ? "PULANG" : a.getEvent();
                String ts = a.getTs() != null ? a.getTs() : "";
                String date = ts.length() >= 10 ? ts.substring(0, 10) : "tanpa-tanggal";
                String time = ts.length() >= 19 ? ts.substring(11, 19).replace(":", "") : "";
                String day = dayNameId(date);
                // Folder per hari & tanggal; file diawali prefix MASUK_/PULANG_.
                String folder = "foto/" + date + (day.isEmpty() ? "" : " " + day);
                out.add(new PhotoEntry(f, folder + "/" + ev + "_" + safe + "_" + time + ".jpg"));
            }
        }
        return out;
    }

    /** Tulis zip: xlsx + foto. {@code targetMinSide}>0 → foto dikecilkan. */
    private static void writeZip(File zip, byte[] xlsx, List<PhotoEntry> photos,
                                 int targetMinSide) throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zip))) {
            zos.setLevel(9); // kompresi maksimum
            zos.putNextEntry(new java.util.zip.ZipEntry("RekapAbsensi.xlsx"));
            zos.write(xlsx);
            zos.closeEntry();
            for (PhotoEntry pe : photos) {
                zos.putNextEntry(new java.util.zip.ZipEntry(pe.entryName));
                byte[] small = targetMinSide > 0 ? downscaleJpeg(pe.file, targetMinSide) : null;
                if (small != null) {
                    zos.write(small);
                } else {
                    copyFile(pe.file, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void copyFile(File f, java.io.OutputStream out) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** Decode + kecilkan foto sehingga sisi terpendek ≈ {@code targetMinSide}, JPEG q70. */
    private static byte[] downscaleJpeg(File f, int targetMinSide) {
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            int minSide = Math.min(w, h);
            android.graphics.BitmapFactory.Options opt =
                    new android.graphics.BitmapFactory.Options();
            int sample = 1;
            while (minSide / (sample * 2) >= targetMinSide) sample *= 2;
            opt.inSampleSize = sample;
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            if (bmp == null) return null;
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            float factor = (float) targetMinSide / Math.min(bw, bh);
            if (factor < 1f) {
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                        bmp, Math.round(bw * factor), Math.round(bh * factor), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------- rows

    /** Lebar kolom (satuan karakter Excel) disesuaikan isi tiap kolom. */
    private static final double[] COL_WIDTHS =
            {22, 12, 9, 18, 18, 9, 18, 18};

    public static List<Object[]> buildRows(DatabaseHelper dbHelper, String startDate,
                                           String endDate, double dailyNormalHours) {
        UserDao userDao = new UserDao(dbHelper);
        AttendanceDao attDao = new AttendanceDao(dbHelper);
        SettingsDao settings = new SettingsDao(dbHelper);
        String depot = settings.getDepotName();
        if (depot == null || depot.isEmpty()) depot = "DAMIU POS";

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{cell("REKAPITULASI ABSENSI — " + depot, XlsxWriter.STYLE_TITLE)});
        rows.add(new Object[]{"Periode", startDate + " s/d " + endDate});
        rows.add(new Object[]{"Jam kerja normal/hari", dailyNormalHours});
        rows.add(new Object[]{"Catatan", "Lembur Hari = kerja di atas jam normal hari itu. "
                + "Final dinormalisasi: jam wajib = hari kerja × jam normal. "
                + "Surplus → Lembur, kurang → Kekurangan."});
        rows.add(new Object[]{});
        rows.add(styled(XlsxWriter.STYLE_HEADER, "Nama Staff", "Tanggal", "Masuk",
                "Istirahat", "Selesai Istirahat", "Pulang", "Durasi Kerja (jam)",
                "Lembur Hari (jam)"));

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
                rows.add(styled(XlsxWriter.STYLE_DATA, u.getName(), e.getKey(), dr.masuk,
                        dr.istirahat, dr.selesai, dr.pulang, round2(dr.workHours),
                        round2(dailyOt)));
                totalHours += dr.workHours;
                if (!"-".equals(dr.masuk)) workingDays++; // hari yang benar2 kerja
            }

            // Normalisasi bulanan: jam wajib = hari kerja × jam normal/hari.
            double required = workingDays * dailyNormalHours;
            double net = totalHours - required;
            double lembur = Math.max(0, net);       // surplus → lembur
            double kekurangan = Math.max(0, -net);  // defisit → kekurangan

            rows.add(styled(XlsxWriter.STYLE_DATA, "TOTAL " + u.getName(), "", "Hari kerja",
                    workingDays, "Jam wajib", round2(required), "Total kerja", round2(totalHours)));
            rows.add(styled(XlsxWriter.STYLE_DATA, "", "", "", "", "", "", "Lembur (jam)",
                    round2(lembur)));
            rows.add(styled(XlsxWriter.STYLE_DATA, "", "", "", "", "", "", "Kekurangan (jam)",
                    round2(kekurangan)));
            rows.add(new Object[]{});
        }
        return rows;
    }

    private static XlsxWriter.Cell cell(Object value, int style) {
        return new XlsxWriter.Cell(value, style);
    }

    /** Bangun satu baris dengan semua sel memakai {@code style} yang sama. */
    private static Object[] styled(int style, Object... values) {
        Object[] out = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = new XlsxWriter.Cell(values[i], style);
        }
        return out;
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

    /**
     * Stempel absensi menjadi milidetik. WAJIB lewat {@link com.crowja.damiupos.util.Ts}: baris
     * absensi yang ditulis dari WEB (link koreksi absensi / rekap dashboard) tiba sebagai ISO-UTC
     * ("…T…Z"), sedangkan HP menulis waktu lokal. Parser satu-format dulu melempar pada bentuk
     * ISO lalu mengembalikan 0 — hari itu terhitung 0 jam di rekap HP padahal dashboard
     * menampilkannya utuh.
     *
     * @return 0 bila kosong/tak terurai (durasi hari itu jadi 0, bukan angka raksasa)
     */
    private static long parseMillis(String ts) {
        long ms = com.crowja.damiupos.util.Ts.millis(ts);

        return ms == Long.MAX_VALUE ? 0 : ms;
    }

    /** Jam:menit LOKAL — jangan substring mentah: stempel asal-web berzona UTC (selisih 7 jam). */
    private static String hm(String ts) {
        String v = com.crowja.damiupos.util.Ts.hm(ts);

        return v.isEmpty() ? (ts != null ? ts : "-") : v;
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
