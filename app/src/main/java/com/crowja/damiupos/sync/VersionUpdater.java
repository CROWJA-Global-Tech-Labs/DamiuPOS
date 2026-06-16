package com.crowja.damiupos.sync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * In-app auto-update. Checks {@code /api/version} (also fed instantly by the MQTT
 * retained version message), and — when a newer build is published — prompts the
 * user, downloads the APK, and launches the system installer.
 */
public final class VersionUpdater {

    private VersionUpdater() {}

    /** Background REST check → store latest → prompt on the UI thread. */
    public static void checkAndPrompt(Activity activity) {
        Context app = activity.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!cfg.isEnrolled()) return;

        new Thread(() -> {
            try {
                JSONObject r = new SyncApi(cfg).version(cfg.getBaseUrl());
                if (r.optBoolean("available", false)) {
                    MqttManager.handleVersion(app, cfg, r);   // stores latest + maybe notifies
                }
            } catch (Throwable ignored) {}
            activity.runOnUiThread(() -> maybePrompt(activity));
        }).start();
    }

    /** Show the update dialog if a newer (non-dismissed) version is known. */
    public static void maybePrompt(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(activity)));
        int code = cfg.getLatestVersionCode();
        boolean mandatory = cfg.isLatestVersionMandatory();
        if (code <= BuildConfig.VERSION_CODE) return;
        if (!mandatory && code == cfg.getDismissedVersion()) return;

        String log = cfg.getLatestVersionChangelog();
        String msg = "Versi " + cfg.getLatestVersionName() + " tersedia."
                + (log != null && !log.isEmpty() ? "\n\n" + log : "");

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Pembaruan Aplikasi")
                .setMessage(msg)
                .setPositiveButton("Perbarui", (d, w) -> startUpdate(activity, cfg.getLatestVersionUrl()));
        if (mandatory) {
            b.setCancelable(false);
        } else {
            b.setNegativeButton("Nanti", (d, w) -> cfg.setDismissedVersion(code));
        }
        b.show();
    }

    private static void startUpdate(Activity activity, String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(activity, "URL APK belum diatur oleh admin", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Mengunduh pembaruan…", Toast.LENGTH_SHORT).show();
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            File apk = download(app, url);
            activity.runOnUiThread(() -> {
                if (apk != null) {
                    install(activity, apk);
                } else {
                    Toast.makeText(activity, "Gagal mengunduh pembaruan", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static File download(Context ctx, String url) {
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "updates");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, "update.apk");
            OkHttpClient client = new OkHttpClient();
            Request req = new Request.Builder().url(url).build();
            try (Response r = client.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                try (InputStream in = r.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void install(Context ctx, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(ctx, "Tidak dapat memasang APK", Toast.LENGTH_LONG).show();
        }
    }
}
