package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * Setelah perangkat reboot, kembalikan semua yang hilang saat mati:
 *  1. Restart {@link LocationService} (sinkronisasi berkelanjutan + lapor koordinat). Menerima
 *     BOOT_COMPLETED adalah salah satu pengecualian yang MEMBOLEHKAN start foreground-service dari
 *     background pada Android 12+.
 *  2. Pasang lagi watchdog + periodic sync (WorkManager biasanya self-reschedule, tapi kita pasang
 *     ulang untuk memastikan).
 *  3. Re-arm pengingat jam kerja bila masih ada staf yang clock in ({@link WorkHoursReminder#schedule}
 *     sendiri no-op bila target tercapai / shift ditutup).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Context app = ctx.getApplicationContext();
        try {
            // Sinkronisasi + lokasi harus hidup lagi tanpa menunggu staf membuka app.
            if (new com.crowja.damiupos.sync.SyncSettings(
                    new SettingsDao(DatabaseHelper.getInstance(app))).isEnrolled()) {
                com.crowja.damiupos.LocationService.ensureOnline(app);
                com.crowja.damiupos.sync.ServiceRestartReceiver.arm(app);
                com.crowja.damiupos.sync.SyncScheduler.schedulePeriodic(app);
            }
        } catch (Throwable ignored) {}
        try {
            long uid = new SettingsDao(DatabaseHelper.getInstance(app)).getCurrentUserId();
            if (uid > 0) WorkHoursReminder.schedule(app, uid);
        } catch (Exception ignored) {}
    }
}
