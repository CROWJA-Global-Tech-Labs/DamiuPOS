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

import org.json.JSONObject;

/**
 * Notifications + version-store helpers for the online layer. Pure REST — no MQTT.
 * Used by the periodic online tick to surface app updates and admin broadcasts.
 */
public final class OnlineNotifier {

    public static final String CHANNEL_PUSH = "damiu_push";

    private OnlineNotifier() {}

    /** Store the advertised version; notify if it's newer than the installed build. */
    public static void handleVersion(Context ctx, SyncSettings cfg, JSONObject o) {
        if (!o.optBoolean("available", true)) return;
        int code = o.optInt("version_code", 0);
        cfg.setLatestVersion(code, o.optString("version_name"), o.optString("apk_url"),
                o.optString("changelog"), o.optBoolean("mandatory", false));
        if (code > BuildConfig.VERSION_CODE && code != cfg.getDismissedVersion()) {
            postNotif(ctx, "Pembaruan tersedia",
                    "Versi " + o.optString("version_name")
                            + " siap dipasang. Ketuk untuk memperbarui.", 7842);
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

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_PUSH) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_PUSH,
                "Pemberitahuan DAMIU POS", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Pembaruan aplikasi & pesan dari admin");
        nm.createNotificationChannel(ch);
    }
}
