package com.crowja.damiupos.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.MainActivity;
import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONObject;

/**
 * Notifications + version-store helpers for the online layer. Pure REST — no MQTT.
 * Used by the periodic online tick to surface app updates and admin broadcasts.
 */
public final class OnlineNotifier {

    public static final String CHANNEL_PUSH = "damiu_push";

    /** In-app broadcast: pesan admin baru tiba → layar foreground tampilkan popup. */
    public static final String ACTION_ADMIN_MESSAGE = "com.crowja.damiupos.action.ADMIN_MESSAGE";

    private OnlineNotifier() {}

    /**
     * Sampaikan pesan admin (broadcast/command "message" dari dashboard): selalu
     * pasang notifikasi, lalu tampilkan juga sebagai popup dialog in-app. Kalau ada
     * layar yang sedang tampil, dialog muncul seketika lewat {@link #ACTION_ADMIN_MESSAGE};
     * kalau app di background, pesan disimpan sebagai "pending" dan ditampilkan begitu
     * dashboard (MainActivity) dibuka lagi.
     */
    public static void deliverAdminMessage(Context ctx, String title, String body, int notifId) {
        postNotif(ctx, title, body, notifId);
        String t = (title == null || title.isEmpty()) ? "Pesan dari Admin" : title;
        String b = body != null ? body : "";
        try {
            new SettingsDao(DatabaseHelper.getInstance(ctx)).setPendingAdminMessage(t, b);
        } catch (Throwable ignored) {}
        try {
            ctx.sendBroadcast(new Intent(ACTION_ADMIN_MESSAGE).setPackage(ctx.getPackageName()));
        } catch (Throwable ignored) {}
    }

    /**
     * Store the published APK (url + sha) and, when this device's installed APK differs and isn't
     * snoozed, post a MANDATORY update notification. When nothing is published, clear it.
     */
    public static void handleUpdate(Context ctx, SyncSettings cfg, JSONObject o) {
        if (o == null || !o.optBoolean("available", false)) {
            cfg.setUpdate("", "", "");
            return;
        }
        cfg.setUpdate(o.optString("apk_url"), o.optString("apk_sha256"), o.optString("version_name"));
        if (VersionUpdater.updateNeeded(ctx, cfg)
                && System.currentTimeMillis() >= cfg.getSnoozeUntil()) {
            postNotif(ctx, "Pembaruan Wajib",
                    "Versi baru aplikasi wajib dipasang. Ketuk untuk memperbarui.", 7842);
        }
    }

    public static void postNotif(Context ctx, String title, String body, int id) {
        ensureChannel(ctx);
        Intent open = new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open, flags);
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_PUSH)
                .setSmallIcon(R.drawable.ic_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(id, n);
        } catch (SecurityException ignored) {
        }
    }

    /** Pastikan channel notifikasi ada. Publik: pemanggil yang membangun notifikasi sendiri
     *  (mis. {@link com.crowja.damiupos.wa.WaGateway} dengan PendingIntent khusus) wajib
     *  memanggilnya dulu — di Android O+ notifikasi ke channel yang belum ada dibuang diam-diam. */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_PUSH) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_PUSH,
                "Pemberitahuan DAMIU POS", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Pembaruan aplikasi & pesan dari admin");
        nm.createNotificationChannel(ch);
    }
}
