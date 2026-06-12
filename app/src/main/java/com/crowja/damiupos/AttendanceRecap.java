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

    // Guard in-memory supaya tidak kirim ganda saat dua trigger berdekatan.
    private static volatile boolean recapSending = false;

    /**
     * Kirim rekap periode terbaru yang sudah selesai kalau belum terkirim
     * (guard {@code lastRecapPeriod}). Dipanggil saat karyawan login & saat
     * Pulang → otomatis RETRY di hari berikutnya kalau di hari cut-off tidak ada
     * yang login. Seluruh kerja berat (zip + foto + SMTP) di background thread;
     * {@code lastRecapPeriod} hanya di-set kalau email BENAR-BENAR terkirim.
     */
    public static void maybeSendDueRecap(Context ctx, DatabaseHelper dbHelper, boolean fromLogout) {
        if (recapSending) return;
        final SettingsDao s = new SettingsDao(dbHelper);
        if (!s.isMultiUserEnabled() || !s.isShiftEmailConfigured()) return;

        int cutoff = s.getPayrollCutoffDay();
        final String[] period = mostRecentCompletedPeriod(cutoff);
        final String periodId = period[1];
        if (periodId.equals(s.getLastRecapPeriod())) return; // sudah terkirim
        // Di hari cut-off kirim hanya saat Pulang (data hari itu lengkap); hari
        // sesudahnya login pun memicu (retry).
        if (isCutoffToday(cutoff) && !fromLogout) return;

        recapSending = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                File zip = exportRecapZip(app, dbHelper, period[0], period[1],
                        s.getDailyNormalHours());
                if (zip == null) { recapSending = false; return; }

                String depot = s.getDepotName();
                if (depot == null || depot.isEmpty()) depot = "DAMIU POS";
                String subject = "Rekap Absensi - " + depot + " - "
                        + period[0] + " s/d " + period[1];
                String body = "Rekapitulasi absensi staff periode " + period[0]
                        + " s/d " + period[1] + ".\n\nFile ZIP (data XLSX + foto) terlampir."
                        + "\nDikirim otomatis oleh DAMIU POS.";
                List<File> atts = new ArrayList<>();
                atts.add(zip);

                final File zipFile = zip;
                ShiftEmailSender.sendAsync(app, s.getSmtpHost(), s.getSmtpPort(),
                        s.getSmtpUser(), s.getSmtpPass(), s.getAdminEmail(),
                        subject, body, atts, (success, error) -> {
                            // Tandai terkirim HANYA kalau sukses → gagal = retry
                            // di login berikutnya (hingga berhasil).
                            if (success) {
                                s.setLastRecapPeriod(periodId);
                                // Hemat storage: hapus foto periode + file zip.
                                cleanupPeriodFiles(dbHelper, period[0], period[1], zipFile);
                            }
                            recapSending = false;
                        });
            } catch (Throwable t) {
                recapSending = false;
            }
        }, "recap-due").start();
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

    /**
     * Setelah rekap bulanan sukses terkirim: hapus foto selfie periode + file
     * ZIP rekap untuk menekan footprint storage, lalu kosongkan photo_path.
     */
    private static void cleanupPeriodFiles(DatabaseHelper dbHelper, String start, String end,
                                           File zip) {
        try {
            UserDao userDao = new UserDao(dbHelper);
            AttendanceDao attDao = new AttendanceDao(dbHelper);
            for (User u : userDao.getAll()) {
                for (Attendance a : attDao.getEventsByUserBetween(u.getId(), start, end)) {
                    String p = a.getPhotoPath();
                    if (p == null || p.isEmpty()) continue;
                    File f = new File(p);
                    if (f.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            attDao.clearPhotoPathsBetween(start, end);
            if (zip != null && zip.exists()) {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
            }
        } catch (Throwable ignored) {}
    }

    private static volatile boolean weeklySending = false;

    /**
     * Kirim rekap PEKANAN (PDF "semua konten export" pekan terbaru yang selesai)
     * kalau aktif & belum terkirim. Dipanggil saat login & Pulang → trigger di
     * hari yang dikonfigurasi (default Sabtu) saat logout, dan RETRY di login
     * hari berikutnya kalau gagal/tidak ada yang login. {@code lastWeeklyRecap}
     * di-set hanya kalau email sukses (atau pekan kosong).
     */
    public static void maybeSendDueWeeklyRecap(Context ctx, DatabaseHelper dbHelper,
                                               boolean fromLogout) {
        if (weeklySending) return;
        final SettingsDao s = new SettingsDao(dbHelper);
        if (!s.isMultiUserEnabled() || !s.isShiftEmailConfigured()) return;
        if (!s.isWeeklyRecapEnabled()) return;

        int triggerDow = s.getWeeklyRecapDay();
        final String[] week = mostRecentCompletedWeek(triggerDow);
        final String weekId = week[1];
        if (weekId.equals(s.getLastWeeklyRecap())) return;
        // Di hari trigger kirim hanya saat Pulang; hari sesudahnya login = retry.
        if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == triggerDow && !fromLogout) return;

        weeklySending = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                File pdf = ReportPdfBuilder.build(app, week[0], week[1], true, true, true);
                if (pdf == null) {
                    // Pekan tanpa data → tandai supaya tidak retry terus-menerus.
                    s.setLastWeeklyRecap(weekId);
                    weeklySending = false;
                    return;
                }
                String depot = s.getDepotName();
                if (depot == null || depot.isEmpty()) depot = "DAMIU POS";
                String subject = "Rekap Pekanan - " + depot + " - "
                        + week[0] + " s/d " + week[1];
                String body = "Rekap pekanan (PDF semua konten export) periode "
                        + week[0] + " s/d " + week[1] + " terlampir."
                        + "\nDikirim otomatis oleh DAMIU POS.";
                List<File> atts = new ArrayList<>();
                atts.add(pdf);
                final File pdfFile = pdf;
                ShiftEmailSender.sendAsync(app, s.getSmtpHost(), s.getSmtpPort(),
                        s.getSmtpUser(), s.getSmtpPass(), s.getAdminEmail(),
                        subject, body, atts, (success, error) -> {
                            if (success) {
                                s.setLastWeeklyRecap(weekId);
                                // Hemat storage: hapus file PDF pekanan setelah terkirim.
                                if (pdfFile != null && pdfFile.exists()) {
                                    //noinspection ResultOfMethodCallIgnored
                                    pdfFile.delete();
                                }
                            }
                            weeklySending = false;
                        });
            } catch (Throwable t) {
                weeklySending = false;
            }
        }, "weekly-recap").start();
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
