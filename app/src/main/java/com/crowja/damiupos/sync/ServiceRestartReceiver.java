package com.crowja.damiupos.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.crowja.damiupos.LocationService;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * "Watchdog" yang menghidupkan ulang {@link LocationService} (sinkronisasi berkelanjutan + lapor
 * koordinat) DAN memicu ulang push {@link SyncScheduler} bila keduanya dibunuh/ditunda sistem/OEM
 * saat aplikasi berada di background — masalah nyata di HP Samsung/Xiaomi yang agresif menidurkan
 * app (kasus nyata 2026-08-09: transaksi tersimpan lokal + struk WA terkirim, tapi push WorkManager
 * berhenti berjalan berjam-jam sehingga tak pernah sampai ke server — tak ada error, hanya diam).
 *
 * <p>Dipicu oleh ALARM PERSIS yang mengulang-jadwal dirinya sendiri (~15 menit). Alarm persis adalah
 * salah satu dari sedikit jalur yang MASIH BOLEH memulai foreground-service dari background pada
 * Android 12+ (selain BOOT_COMPLETED dan onTaskRemoved). Karena APK didistribusikan sendiri (bukan
 * lewat Play Store), izin alarm persis aman dipakai; tetap di-guard {@code canScheduleExactAlarms()}
 * dengan fallback alarm tak-persis.
 *
 * <p>Idempoten: {@link LocationService#ensureOnline} = no-op bila service sudah hidup, dan
 * {@link SyncScheduler#syncNow} memakai {@code ExistingWorkPolicy.REPLACE} (aman dipanggil berkali-
 * kali) — watchdog yang menembak saat semuanya sehat tidak berdampak apa-apa selain satu sync ringan.
 */
public class ServiceRestartReceiver extends BroadcastReceiver {

    public static final String ACTION_RESTART = "com.crowja.damiupos.RESTART_SYNC_SERVICE";

    /** Interval watchdog (ms) — cukup sering untuk memulihkan cepat, cukup jarang agar hemat daya. */
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        // Perangkat sudah di-UNENROLL → HENTIKAN rantai alarm (JANGAN re-arm), supaya tidak ada
        // watchdog yang membangunkan perangkat selamanya untuk app yang tak lagi dipakai.
        // (Jangan taruh arm() di finally: return di sini pun akan lewati finally-nya — dulu itu
        // bug: alarm tetap ter-arm meski sudah unenroll.) Bila status tak bisa dipastikan (DB
        // error), anggap masih enrolled agar watchdog tidak mati diam-diam.
        boolean enrolled;
        try {
            enrolled = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app))).isEnrolled();
        } catch (Throwable t) {
            enrolled = true;
        }
        if (!enrolled) return;

        try {
            LocationService.ensureOnline(app);
        } catch (Throwable ignored) {
            // Start FGS dari background bisa dilempar sistem — jangan crash; alarm berikutnya coba lagi.
        }
        try {
            // Re-kick push/pull WorkManager — pemulihan diam bila periodic work-nya dibunuh/ditunda
            // OS (Doze/App-Standby/OEM sleep) sejak alarm terakhir. syncNow() aman dipanggil berkali-
            // kali (REPLACE unique work); tak berefek bila sync sebenarnya sudah sehat.
            SyncScheduler.syncNow(app);
        } catch (Throwable ignored) {
        }
        arm(app);   // jadwalkan tembakan berikutnya (hanya saat masih enrolled)
    }

    /** (Re)jadwalkan tembakan watchdog berikutnya (~15 menit). Aman dipanggil berkali-kali. */
    public static void arm(Context ctx) {
        armIn(ctx, INTERVAL_MS);
    }

    /**
     * Jadwalkan watchdog {@code delayMs} dari sekarang — dipakai onTaskRemoved dgn jeda pendek
     * (beberapa detik) supaya app yang disapu dari Recents bangkit cepat. Slot alarm sama (satu
     * PendingIntent), jadi ini menimpa jadwal 15-menit; saat menembak, onReceive memasang lagi
     * jadwal normal.
     */
    public static void armIn(Context ctx, long delayMs) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long triggerAt = System.currentTimeMillis() + Math.max(1000L, delayMs);
        PendingIntent pi = pendingIntent(app);
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
            if (exact) {
                // Alarm PERSIS → boleh memulai FGS dari background (pengecualian Android 12+).
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                // Tanpa izin alarm persis: pakai tak-persis (konvensi app lain). Start FGS mungkin
                // ditunda sistem, tapi onTaskRemoved + BOOT + START_STICKY + pengecualian baterai
                // tetap menutup kasus-kasus utama.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Throwable ignored) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi); } catch (Throwable ignored2) {}
        }
    }

    /** Batalkan watchdog (dipanggil saat perangkat di-unenroll / service di-stop permanen). */
    public static void cancel(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(app));
    }

    private static PendingIntent pendingIntent(Context app) {
        Intent i = new Intent(app, ServiceRestartReceiver.class).setAction(ACTION_RESTART);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(app, 0, i, flags);
    }
}
