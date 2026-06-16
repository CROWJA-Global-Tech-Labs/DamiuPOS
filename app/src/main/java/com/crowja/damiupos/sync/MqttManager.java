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

import com.crowja.damiupos.MainActivity;
import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Realtime "nervous system" client. Keeps a single MQTT connection (while the app
 * process is alive) and reacts to:
 *   - damiupos/all/version       → store latest version + notify (auto-update prompt)
 *   - damiupos/{branch}/broadcast → admin message notification
 *   - damiupos/{branch}/sync      → trigger a background sync
 *
 * Tolerant: if the broker isn't configured/reachable it simply stays disconnected;
 * REST sync + the on-resume REST version check are the source of truth.
 */
public final class MqttManager {

    public static final String CHANNEL_PUSH = "damiu_push";

    private static MqttManager instance;
    private MqttAsyncClient client;

    private MqttManager() {}

    public static synchronized MqttManager get() {
        if (instance == null) instance = new MqttManager();
        return instance;
    }

    /** Connect + subscribe if enrolled and broker configured. Idempotent. */
    public synchronized void ensureConnected(Context ctx) {
        Context app = ctx.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!cfg.isEnrolled() || !cfg.isMqttConfigured()) return;
        if (client != null && client.isConnected()) return;

        try {
            String uri = (cfg.isMqttTls() ? "ssl://" : "tcp://")
                    + cfg.getMqttHost() + ":" + cfg.getMqttPort();
            String clientId = "damiu-" + cfg.getDeviceUuid();
            if (client == null) {
                client = new MqttAsyncClient(uri, clientId, new MemoryPersistence());
                client.setCallback(new Callback(app, cfg));
            }
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(cfg.getMqttUser());
            opts.setPassword(cfg.getMqttPass().toCharArray());
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setConnectionTimeout(15);
            opts.setKeepAliveInterval(45);
            client.connect(opts);   // async — subscribe happens in connectComplete
        } catch (Throwable ignored) {
            // broker down / bad config → stay offline, polling covers it
        }
    }

    public synchronized void disconnect() {
        try { if (client != null) client.disconnectForcibly(500); } catch (Throwable ignored) {}
    }

    private static String[] topics(SyncSettings cfg) {
        String p = cfg.getMqttPrefix();
        String b = cfg.getBranchCode();
        return new String[]{
                p + "/all/version",
                p + "/" + b + "/broadcast",
                p + "/" + b + "/sync",
        };
    }

    private final class Callback implements MqttCallbackExtended {
        private final Context app;
        private final SyncSettings cfg;
        Callback(Context app, SyncSettings cfg) { this.app = app; this.cfg = cfg; }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            try {
                for (String t : topics(cfg)) client.subscribe(t, 1);
            } catch (Throwable ignored) {}
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            try {
                if (topic.endsWith("/sync")) {
                    SyncScheduler.syncNow(app);
                } else if (topic.endsWith("/version")) {
                    handleVersion(app, cfg, new JSONObject(payload));
                } else if (topic.endsWith("/broadcast")) {
                    JSONObject o = new JSONObject(payload);
                    postNotif(app, o.optString("title", "DAMIU POS"),
                            o.optString("body", ""), 7841);
                }
            } catch (Throwable ignored) {}
        }

        @Override public void connectionLost(Throwable cause) {}
        @Override public void deliveryComplete(IMqttDeliveryToken token) {}
    }

    /** Store the advertised version; notify if it's newer than the installed build. */
    public static void handleVersion(Context ctx, SyncSettings cfg, JSONObject o) {
        if (!o.optBoolean("available", true)) return;
        int code = o.optInt("version_code", 0);
        cfg.setLatestVersion(code, o.optString("version_name"), o.optString("apk_url"),
                o.optString("changelog"), o.optBoolean("mandatory", false));
        if (code > com.crowja.damiupos.BuildConfig.VERSION_CODE && code != cfg.getDismissedVersion()) {
            postNotif(ctx, "Pembaruan tersedia",
                    "Versi " + o.optString("version_name") + " siap dipasang. Ketuk untuk memperbarui.", 7842);
        }
    }

    static void postNotif(Context ctx, String title, String body, int id) {
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
        } catch (SecurityException ignored) {}
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
