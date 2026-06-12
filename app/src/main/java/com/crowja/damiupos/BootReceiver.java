package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * Re-arm pengingat jam kerja setelah perangkat reboot. Alarm AlarmManager
 * hilang saat reboot, jadi kalau masih ada staf yang sedang clock in, kita
 * jadwalkan ulang. {@link WorkHoursReminder#schedule} sendiri no-op kalau
 * target sudah tercapai atau shift sudah ditutup.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            long uid = new SettingsDao(DatabaseHelper.getInstance(ctx)).getCurrentUserId();
            if (uid > 0) WorkHoursReminder.schedule(ctx, uid);
        } catch (Exception ignored) {}
    }
}
