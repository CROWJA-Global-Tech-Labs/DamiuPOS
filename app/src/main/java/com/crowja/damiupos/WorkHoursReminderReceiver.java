package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * Dipicu {@link android.app.AlarmManager} saat perkiraan target jam kerja
 * harian tercapai. Sebelum memunculkan notifikasi bersuara, verifikasi ulang
 * bahwa user MASIH clock in (bukan istirahat / sudah pulang) DAN benar-benar
 * sudah memenuhi target. Kalau ternyata belum (mis. ada jeda yang tidak
 * tercatat), jadwalkan ulang sisa waktunya.
 */
public class WorkHoursReminderReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.crowja.damiupos.WORK_HOURS_MET";
    public static final String EXTRA_USER_ID = "user_id";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION_FIRE.equals(intent.getAction())) return;
        long userId = intent.getLongExtra(EXTRA_USER_ID, 0);
        if (userId <= 0) return;

        DatabaseHelper db = DatabaseHelper.getInstance(ctx);
        SettingsDao settings = new SettingsDao(db);
        // Hanya kalau user ini masih clock in. getCurrentUserId() = 0 saat
        // istirahat / sudah pulang → jangan ganggu.
        if (settings.getCurrentUserId() != userId) return;

        long targetMs = Math.round(settings.getDailyNormalHours() * 3600000.0);
        long workedMs = ShiftReporter.workedMillisToday(db, userId);
        if (workedMs < targetMs) {
            // Belum benar-benar memenuhi → jadwalkan ulang sisa waktunya.
            WorkHoursReminder.schedule(ctx, userId);
            return;
        }
        WorkHoursReminder.notifyMet(ctx, workedMs);
    }
}
