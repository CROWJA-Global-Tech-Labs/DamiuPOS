package com.crowja.damiupos;

import android.content.Context;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.User;

/**
 * Menutup sesi staf di HP bila shift-nya sudah berakhir:
 * <ul>
 *   <li><b>Dipulangkan</b> — event terakhir OUT (tombol "Pulangkan" di dashboard / pulang otomatis 23:59 server,
 *       tersinkron ke HP);</li>
 *   <li><b>Lewat tengah malam</b> — event terakhir IN/BREAK dari tanggal SEBELUM hari ini. Server memulangkan semua
 *       staf pukul 23:59, tapi HP baru tahu setelah sync berhasil; HP yang tertidur/aplikasi tertutup semalaman dulu
 *       tetap login sampai pagi (kasus SUSILO ADI, Nov 2026). Cek ini tak butuh jaringan: dipanggil tiap sync,
 *       saat layar utama dibuka, dan pada pukul 00:00 bila layar sedang terbuka.</li>
 * </ul>
 * HP tidak menulis OUT sendiri — OUT 23:59 ditulis server ({@code attendance:auto-out}), jadi tak ada OUT ganda.
 */
public final class SessionGuard {
    private SessionGuard() {}

    public static final int NONE = 0, REMOTE_OUT = 1, MIDNIGHT = 2;

    /** Keputusan murni (diuji unit): event terakhir + tanggal lokalnya vs hari ini ("yyyy-MM-dd"). */
    public static int decide(String lastEvent, String lastLocalDate, String today) {
        if (lastEvent == null) return NONE;
        if (Attendance.EVENT_OUT.equals(lastEvent)) return REMOTE_OUT;
        if (lastLocalDate != null && today != null && lastLocalDate.compareTo(today) < 0) return MIDNIGHT;
        return NONE;
    }

    /** Tutup sesi staf yang sedang login bila shift-nya sudah berakhir. @return true bila sesi ditutup. */
    public static boolean closeIfEnded(Context ctx) {
        Context app = ctx.getApplicationContext();
        DatabaseHelper db = DatabaseHelper.getInstance(app);
        SettingsDao settings = new SettingsDao(db);
        if (!settings.isMultiUserEnabled()) return false;
        AttendanceDao att = new AttendanceDao(db);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());

        // User yang sedang istirahat lalu dipulangkan / lewat tengah malam → hapus resume "Lanjut Kerja".
        long breakUid = settings.getBreakUserId();
        if (breakUid > 0) {
            String[] b = att.lastEventAndLocalDate(breakUid);
            if (b != null && decide(b[0], b[1], today) != NONE) settings.clearBreakUser();
        }

        long uid = settings.getCurrentUserId();
        if (uid <= 0) return false;
        User u = new UserDao(db).getById(uid);
        if (u == null || !u.tracksAttendance()) return false;   // admin/viewer tanpa absensi
        String[] last = att.lastEventAndLocalDate(uid);
        int why = last == null ? NONE : decide(last[0], last[1], today);
        if (why == NONE) return false;

        String name = settings.getCurrentUserName();
        // Tutup sesi — urutan sama dengan alur Pulang lokal (MainActivity.finishClockOut).
        try { WorkHoursReminder.cancel(app, uid); } catch (Throwable ignored) {}
        settings.setShiftActive(false);
        try { LocationService.pollOnly(app); } catch (Throwable ignored) {}
        settings.clearCurrentUser();
        settings.clearBreakUser();

        String who = name != null && !name.isEmpty() ? name + " " : "";
        try {
            com.crowja.damiupos.sync.OnlineNotifier.deliverAdminMessage(app, "Shift Ditutup",
                    why == MIDNIGHT
                            ? who + "otomatis logout pukul 00:00 (pulang otomatis 23:59) — silakan login lagi untuk shift baru."
                            : who + "telah dipulangkan dari dashboard — sesi di perangkat ini ditutup.", 7920);
        } catch (Throwable ignored) {}
        return true;
    }
}
