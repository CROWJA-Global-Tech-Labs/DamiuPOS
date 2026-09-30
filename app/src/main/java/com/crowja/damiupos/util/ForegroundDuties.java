package com.crowja.damiupos.util;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.LocationService;
import com.crowja.damiupos.WorkHoursReminder;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.OnlineNotifier;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.VersionUpdater;

/**
 * Kewajiban "layar induk yang hidup lama" yang dipakai bersama {@code MainActivity} dan layar
 * yang menggantikan beranda untuk satu peran ({@code PengisianActivity}). Dulu ditulis langsung di
 * MainActivity — peran yang dialihkan sebelum setContentView diam-diam kehilangan semuanya: gerbang
 * versi dinonaktifkan/update wajib, nyala-ulang service sinkron/online, popup pesan admin, izin
 * notifikasi. Satu tempat supaya layar pengganti berikutnya tak mengulang kelalaian yang sama.
 */
public final class ForegroundDuties {

    private ForegroundDuties() {}

    public static final int REQ_POST_NOTIF = 9311;

    /** Buat channel pengingat jam kerja + minta izin POST_NOTIFICATIONS (API 33+). */
    public static void ensureNotificationAccess(Activity a) {
        WorkHoursReminder.ensureChannel(a);
        if (Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(a,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(a,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
        }
    }

    /** Sekali saat layar induk dibuka: izin notifikasi + sinkron berkala + cek versi baru (hash). */
    public static void onLaunch(Activity a) {
        ensureNotificationAccess(a);
        SyncScheduler.schedulePeriodic(a.getApplicationContext());
        VersionUpdater.checkAndPrompt(a);
    }

    /**
     * Tiap onResume layar induk: gerbang versi dinonaktifkan dari dashboard (murah, baca flag
     * lokal, hormati snooze 1 jam), sinkron sekali, dan hidupkan service
     * polling "online" (config/versi/pesan admin tetap mengalir walau proses sempat mati).
     */
    public static void onResume(Activity a) {
        VersionUpdater.maybePromptBlocked(a);
        SyncScheduler.syncNow(a.getApplicationContext());
        LocationService.ensureOnline(a.getApplicationContext());
    }

    /**
     * Pasang penerima "pesan admin baru" (broadcast {@link OnlineNotifier#ACTION_ADMIN_MESSAGE}).
     * Pasangkan dengan {@code unregisterReceiver} di onPause.
     */
    public static void registerAdminMessageReceiver(Activity a, BroadcastReceiver r) {
        IntentFilter f = new IntentFilter(OnlineNotifier.ACTION_ADMIN_MESSAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            a.registerReceiver(r, f);
        }
    }

    /**
     * Tampilkan pesan admin tertunda ("Kirim Pesan ke Perangkat") sebagai popup lalu bersihkan
     * (tampil sekali). Sumber kebenaran = pending message di settings, jadi aman dipanggil dari
     * broadcast maupun onResume. @param shown popup yang sedang tampil (ditutup dulu, anti-tumpuk).
     * @return dialog yang baru ditampilkan, atau {@code shown} bila tak ada pesan tertunda.
     */
    public static AlertDialog showPendingAdminMessage(Activity a, SettingsDao settings, AlertDialog shown) {
        if (a.isFinishing() || a.isDestroyed() || settings == null) return shown;
        if (!settings.hasPendingAdminMessage()) return shown;
        String title = settings.getPendingAdminMessageTitle();
        String body = settings.getPendingAdminMessageBody();
        settings.clearPendingAdminMessage();
        if (shown != null && shown.isShowing()) shown.dismiss();
        return new AlertDialog.Builder(a)
                .setIcon(android.R.drawable.ic_dialog_email)
                .setTitle(title == null || title.isEmpty() ? "Pesan dari Admin" : title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show();
    }
}
