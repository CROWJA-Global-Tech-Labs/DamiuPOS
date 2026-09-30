package com.crowja.damiupos.util;

import android.app.Activity;
import android.content.Intent;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.LocationService;
import com.crowja.damiupos.LoginActivity;
import com.crowja.damiupos.WorkHoursReminder;
import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Attendance;

/**
 * Aksi shift yang dipakai bersama beranda (MainActivity) dan layar Pengisian, supaya peran tanpa
 * beranda (Pengisian day-time) memakai jalur absensi YANG SAMA dengan staf lain — bukan salinan yang
 * bisa menyimpang. Catatan BREAK dipakai hitungan jam kerja (tanpa BREAK, server memotong 1 jam otomatis).
 */
public final class ShiftActions {

    private ShiftActions() {
    }

    /**
     * Dialog "Istirahat?" → catat BREAK (+ GPS), sinkron seketika, hentikan pelacakan lokasi (service
     * tetap poll-only), ingat siapa yang istirahat (tombol "Lanjut Kerja" 1 ketukan di layar login),
     * lalu ke layar login. Shift tetap terbuka sampai Pulang.
     */
    public static void confirmBreak(Activity activity, SettingsDao settingsDao) {
        final long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return;
        final String uname = settingsDao.getCurrentUserName();
        new AlertDialog.Builder(activity)
                .setTitle("Istirahat?")
                .setMessage("Aplikasi terkunci selama istirahat. Tekan \"Lanjut Kerja\" "
                        + "untuk melanjutkan tanpa PIN, atau rekan lain bisa login.")
                .setPositiveButton("Ya, Istirahat", (d, w) -> {
                    long attId = new AttendanceDao(DatabaseHelper.getInstance(activity))
                            .log(uid, Attendance.EVENT_BREAK);
                    LocationService.stampAttendanceLocation(activity, attId);   // GPS istirahat untuk dashboard
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(activity.getApplicationContext());   // absensi real-time
                    // Pause pengingat jam kerja — di-rearm saat clock in lagi.
                    WorkHoursReminder.cancel(activity.getApplicationContext(), uid);
                    // Istirahat: berhenti melacak LOKASI, tapi service tetap hidup (poll-only) supaya
                    // polling background tetap aktif selama shift.
                    LocationService.pollOnly(activity.getApplicationContext());
                    settingsDao.setBreakUser(uid, uname);
                    settingsDao.clearCurrentUser();
                    Intent i = new Intent(activity, LoginActivity.class);
                    i.putExtra(LoginActivity.EXTRA_FROM_BREAK, true);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    activity.startActivity(i);
                    activity.finish();
                })
                .setNegativeButton("Batal", null)
                .show();
    }
}
