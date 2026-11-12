package com.crowja.damiupos.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.crowja.damiupos.ChatLogActivity;
import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

/**
 * 😠 Alarm komplain: pesanan yang dikomplain pelanggan memunculkan notifikasi dengan SIRENE nyaring (suara khusus
 * res/raw/complaint_siren, kanal sendiri, volume ALARM) yang terus berbunyi sampai notifikasinya diketuk/digeser
 * (FLAG_INSISTENT), berisi teks komplain + ringkasan transaksi. Diketuk → chat komplain order itu. Popup yang sama
 * juga tampil di layar yang sedang terbuka (jalur "pesan admin" yang sudah ada).
 */
public final class ComplaintAlert {

    public static final String CHANNEL_ID = "damiu_complaint_alarm";

    private ComplaintAlert() {}

    /** Sirene + popup untuk tujuan bebas (mis. daftar Komplain & Masalah) — pengingat berulang item belum dibaca. */
    public static void deliverTo(Context ctx, Intent open, String title, String body, int notifId) {
        post(ctx, open, title, body, notifId);
    }

    public static void deliver(Context ctx, String title, String body, @Nullable String trxUuid,
                               @Nullable String customerName, int notifId) {
        Intent open = new Intent(ctx, ChatLogActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (trxUuid != null && !trxUuid.isEmpty()) {
            open.putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, trxUuid);
            open.putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, customerName != null ? customerName : "");
        }
        post(ctx, open, title, body, notifId);
    }

    private static void post(Context ctx, Intent open, String title, String body, int notifId) {
        ensureChannel(ctx);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Uri sound = soundUri(ctx);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setVibrate(new long[]{0, 600, 250, 600, 250, 600});
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setSound(sound, android.media.AudioManager.STREAM_ALARM);
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;   // sirene berulang sampai diketuk / digeser
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, n);
        } catch (SecurityException ignored) {
        }
        // Popup di layar yang sedang tampil (atau begitu dashboard dibuka) — memakai jalur pesan admin.
        try {
            new SettingsDao(DatabaseHelper.getInstance(ctx)).setPendingAdminMessage(title, body);
            ctx.sendBroadcast(new Intent(OnlineNotifier.ACTION_ADMIN_MESSAGE).setPackage(ctx.getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    private static Uri soundUri(Context ctx) {
        return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.complaint_siren);
    }

    /** Kanal dibuat sekali dengan suara sirene khusus; suara kanal tak bisa diubah aplikasi sesudahnya. */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Alarm komplain pelanggan", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Sirene nyaring saat pesanan dikomplain pelanggan");
        ch.setSound(soundUri(ctx), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 250, 600, 250, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }
}
