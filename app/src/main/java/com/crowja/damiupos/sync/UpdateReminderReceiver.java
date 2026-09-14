package com.crowja.damiupos.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * Fires when a "Tunda 1 jam" snooze expires and re-posts the mandatory-update notification (so the
 * staff are reminded even if the app is in the background). No-op once the device is up to date.
 */
public class UpdateReminderReceiver extends BroadcastReceiver {

    private static final int REQ = 7843;

    /** Schedule a one-shot reminder at {@code whenMs} (inexact + doze-friendly; no exact-alarm permission). */
    public static void scheduleAt(Context ctx, long whenMs) {
        AlarmManager am = (AlarmManager) ctx.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx);
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi);
        } catch (Throwable t) {
            am.set(AlarmManager.RTC_WAKEUP, whenMs, pi);
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), UpdateReminderReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), REQ, i, flags);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (VersionUpdater.updateNeeded(app, cfg)) {
            OnlineNotifier.postNotif(app, "Pembaruan Wajib",
                    "Versi baru aplikasi wajib dipasang. Ketuk untuk memperbarui.", 7842);
        }
    }
}
