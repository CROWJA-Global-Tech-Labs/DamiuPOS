package com.crowja.damiupos;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * Pengingat "jam kerja harian sudah terpenuhi" untuk staf.
 *
 * <p>Saat staf clock in, kita jadwalkan satu alarm ({@link AlarmManager}) yang
 * memunculkan notifikasi bersuara begitu akumulasi jam kerja mencapai target
 * harian ({@link SettingsDao#getDailyNormalHours()}, default 7 jam). Alarm
 * di-cancel saat istirahat / pulang dan dijadwalkan ulang saat clock in lagi,
 * sehingga waktu break tidak ikut dihitung. MainActivity juga me-rearm saat
 * resume supaya pengingat tetap aktif walau proses sempat dimatikan sistem.
 *
 * <p>Memakai {@code setAndAllowWhileIdle} (inexact, tetap jalan saat Doze) —
 * tidak butuh izin SCHEDULE_EXACT_ALARM. Selisih beberapa menit dapat diterima
 * untuk pengingat seperti ini.
 */
public final class WorkHoursReminder {

    /** Channel khusus — IMPORTANCE_HIGH + sound (beda dari channel alert WA). */
    public static final String CHANNEL_ID = "work_hours_reminder";
    private static final int NOTIF_ID = 7811;
    private static final int REQ_BASE = 9100;

    private WorkHoursReminder() {}

    /**
     * Buat channel notifikasi bersuara. Idempotent, TAPI kalau nada dering
     * pilihan user (getWaRingtoneUri) berubah, channel di-buat ulang — karena
     * sound channel itu immutable setelah dibuat.
     */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Uri desired = resolveSound(ctx);
        NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            Uri cur = existing.getSound();
            boolean same = (cur == null && desired == null)
                    || (cur != null && cur.equals(desired));
            if (same) return;
            // Nada berubah → channel lama dihapus supaya bisa pakai sound baru.
            nm.deleteNotificationChannel(CHANNEL_ID);
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Pengingat Jam Kerja", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notifikasi saat jam kerja harian sudah terpenuhi");
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(desired, aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 400, 200, 400});
        nm.createNotificationChannel(ch);
    }

    private static Uri resolveSound(Context ctx) {
        try {
            String uriStr = new SettingsDao(DatabaseHelper.getInstance(ctx)).getWaRingtoneUri();
            if (uriStr != null && !uriStr.isEmpty()) return Uri.parse(uriStr);
        } catch (Exception ignored) {}
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    /**
     * (Re)jadwalkan pengingat untuk {@code userId}. No-op kalau target sudah
     * terpenuhi (remaining &lt;= 0) — mencegah notifikasi dobel saat kembali
     * dari istirahat setelah target tercapai.
     */
    public static void schedule(Context ctx, long userId) {
        if (userId <= 0) return;
        ensureChannel(ctx);
        DatabaseHelper db = DatabaseHelper.getInstance(ctx);
        SettingsDao settings = new SettingsDao(db);
        long targetMs = Math.round(settings.getDailyNormalHours() * 3600000.0);
        long workedMs = ShiftReporter.workedMillisToday(db, userId);
        long remaining = targetMs - workedMs;
        if (remaining <= 0) { cancel(ctx, userId); return; }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long triggerAt = System.currentTimeMillis() + remaining;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt,
                    pendingIntent(ctx, userId));
        } catch (Exception ignored) {}
    }

    /** Batalkan pengingat (saat istirahat / pulang). */
    public static void cancel(Context ctx, long userId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(ctx, userId));
    }

    private static PendingIntent pendingIntent(Context ctx, long userId) {
        Intent i = new Intent(ctx, WorkHoursReminderReceiver.class)
                .setAction(WorkHoursReminderReceiver.ACTION_FIRE)
                .putExtra(WorkHoursReminderReceiver.EXTRA_USER_ID, userId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_BASE + (int) userId, i, flags);
    }

    /** Tampilkan notifikasi bersuara "jam kerja terpenuhi". */
    public static void notifyMet(Context ctx, long workedMs) {
        ensureChannel(ctx);
        String worked = ShiftReporter.formatDurationLong(workedMs);
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 0, open, piFlags);
        String big = "Kamu sudah bekerja " + worked + " hari ini dan memenuhi target. "
                + "Terima kasih atas kerja kerasmu — boleh bersiap pulang.";
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle("Jam kerja sudah terpenuhi 🎉")
                .setContentText("Kamu sudah bekerja " + worked + " hari ini.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(big))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS belum diberikan — abaikan diam-diam.
        }
    }
}
