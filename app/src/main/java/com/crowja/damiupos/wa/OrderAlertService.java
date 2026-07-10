package com.crowja.damiupos.wa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.crowja.damiupos.MainActivity;
import com.crowja.damiupos.OrderInboxActivity;
import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.OrderInboxDao;
import com.crowja.damiupos.db.SettingsDao;

/**
 * Foreground service yang play nada dering loop selama ada pesanan WA
 * pending. Berjalan terlepas dari activity, jadi suara TETAP bunyi
 * walaupun user pindah ke app lain / kunci layar / DAMIU POS di-minimize.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #ACTION_START} — pasang foreground notif + start sound.
 *       Idempotent (call berulang OK, hanya state pertama yg memulai).</li>
 *   <li>{@link #ACTION_REFRESH} — re-cek pending count; kalau 0 → stopSelf.
 *       Dipanggil oleh OrderInboxActivity setelah user approve/tolak.</li>
 *   <li>{@link #ACTION_STOP} — stop service tanpa cek apa pun.</li>
 * </ul>
 *
 * <p>Notifikasi foreground bersifat klikable → buka OrderInboxActivity.
 * Channel pakai importance HIGH supaya tampak menonjol.
 */
public class OrderAlertService extends Service {

    private static final String TAG = "OrderAlertService";

    public static final String ACTION_START = "com.crowja.damiupos.alert.START";
    public static final String ACTION_REFRESH = "com.crowja.damiupos.alert.REFRESH";
    public static final String ACTION_STOP = "com.crowja.damiupos.alert.STOP";

    public static final String CHANNEL_ID = "wa_order_alert";
    private static final int NOTIFICATION_ID = 7701;

    private MediaPlayer player;
    private PowerManager.WakeLock wakeLock;

    /** Helper static — ke{a}m caller cukup panggil ini, kalau service belum running otomatis start. */
    public static void start(Context ctx) {
        // Karyawan marketing tidak menangani pesanan → jangan bunyikan alarm pesanan sama sekali.
        if (com.crowja.damiupos.db.UserDao.isCurrentUserMarketing(ctx)) return;
        Intent i = new Intent(ctx, OrderAlertService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void refresh(Context ctx) {
        // Karyawan marketing tidak menangani pesanan → jangan mulai/ulangi alarm; pastikan berhenti
        // (mis. dibuka dari Inbox saat masih ada pesanan pending hasil sinkron web).
        if (com.crowja.damiupos.db.UserDao.isCurrentUserMarketing(ctx)) { stop(ctx); return; }
        Intent i = new Intent(ctx, OrderAlertService.class).setAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, OrderAlertService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        // Wake lock ringan supaya CPU tidak turun & sound tidak terputus.
        // Layar tetap bisa mati — kita tidak butuh layar nyala.
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "DAMIU::OrderAlert");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(60 * 60 * 1000L); // max 1 jam — safety
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock fail: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "onStartCommand action=" + action);

        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Selalu pasang foreground notif dulu (Android 8+ requirement)
        startForegroundWithNotif();

        // Choke-point terakhir: apa pun jalur pemicunya (restart START_STICKY, ganti user saat alarm
        // jalan, intent langsung), kalau user yang login sekarang MARKETING → jangan bunyikan/tahan
        // notifikasi pesanan. startForeground sudah dipanggil di atas (memenuhi kontrak FGS) jadi
        // aman langsung lepas & berhenti.
        if (com.crowja.damiupos.db.UserDao.isCurrentUserMarketing(this)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_REFRESH.equals(action)) {
            int pending = countPending();
            Log.d(TAG, "REFRESH — pending=" + pending);
            if (pending == 0) {
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            }
            // Update isi notifikasi
            updateNotificationContent(pending);
        }

        // START sound (idempotent)
        startSound();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSound();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ===== Notification =====

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Pesanan WhatsApp",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifikasi persistent saat ada pesanan WA pending");
        // Channel ini TIDAK pakai sound bawaan — sound dimainkan via MediaPlayer
        // (looping). Kalau pakai sound channel, hanya bunyi sekali per posting.
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundWithNotif() {
        int pending = countPending();
        Notification n = buildNotification(pending);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotificationContent(int pending) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(pending));
    }

    private Notification buildNotification(int pending) {
        Intent open = new Intent(this, OrderInboxActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stopIntent = new Intent(this, OrderAlertService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        String title = pending > 1
                ? "📨 " + pending + " pesanan WA menunggu"
                : "📨 Ada pesanan WA baru";
        String text = "Tap untuk buka inbox & balas pelanggan";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_inbox)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // jangan re-bunyi sound channel tiap update
                .setContentIntent(contentPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop bunyi", stopPi)
                .build();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    // ===== Sound =====

    private synchronized void startSound() {
        if (player != null) return; // already playing
        try {
            SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(this));
            String uriStr = settings.getWaRingtoneUri();
            Uri uri = (uriStr != null && !uriStr.isEmpty())
                    ? Uri.parse(uriStr)
                    : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri == null) {
                Log.w(TAG, "No ringtone URI available");
                return;
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.setVolume(1f, 1f);
            mp.prepare();
            mp.start();
            player = mp;
            Log.d(TAG, "Sound started: " + uri);
        } catch (Exception e) {
            Log.w(TAG, "startSound failed: " + e.getMessage());
            stopSound();
        }
    }

    private synchronized void stopSound() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    private int countPending() {
        try {
            return new OrderInboxDao(DatabaseHelper.getInstance(this)).countPending();
        } catch (Exception e) {
            return 0;
        }
    }
}
