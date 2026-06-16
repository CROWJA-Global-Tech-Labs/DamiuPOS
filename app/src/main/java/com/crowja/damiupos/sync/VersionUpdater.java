package com.crowja.damiupos.sync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
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
 * In-app auto-update. When the dashboard publishes a newer build, {@code /api/version}
 * advertises it; the app downloads the APK in the background (during polling or on
 * resume) and then asks the logged-in user to install now or postpone for later. The
 * downloaded APK is kept so "Nanti" can install instantly next time.
 */
public final class VersionUpdater {

    private VersionUpdater() {}

    /** Background REST check → store latest → pre-download → prompt on the UI thread. */
    public static void checkAndPrompt(Activity activity) {
        Context app = activity.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!cfg.isEnrolled()) return;

        new Thread(() -> {
            try {
                JSONObject r = new SyncApi(cfg).version(cfg.getBaseUrl());
                if (r.optBoolean("available", false)) {
                    OnlineNotifier.handleVersion(app, cfg, r);   // stores latest + maybe notifies
                }
            } catch (Throwable ignored) {}
            autoDownloadIfNeeded(app);   // blocking on this bg thread; ready before we prompt
            activity.runOnUiThread(() -> maybePrompt(activity));
        }).start();
    }

    /**
     * Download the latest published APK in the background if it's newer than this build
     * and not already downloaded. Safe to call repeatedly (no-op once the APK is ready).
     * Call OFF the main thread (it blocks on the network).
     */
    public static void autoDownloadIfNeeded(Context ctx) {
        Context app = ctx.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        int code = cfg.getLatestVersionCode();
        if (code <= BuildConfig.VERSION_CODE) return;
        if (cfg.getDownloadedVersion() == code && apkFileFor(app, code).exists()) return;
        String url = cfg.getLatestVersionUrl();
        if (url == null || url.isEmpty()) return;
        File apk = download(app, url, code);
        if (apk != null) cfg.setDownloadedVersion(code);
    }

    /** Show the update dialog if a newer (non-dismissed) version is known. */
    public static void maybePrompt(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(activity)));
        int code = cfg.getLatestVersionCode();
        boolean mandatory = cfg.isLatestVersionMandatory();
        if (code <= BuildConfig.VERSION_CODE) return;
        if (!mandatory && code == cfg.getDismissedVersion()) return;

        boolean ready = cfg.getDownloadedVersion() == code && apkFileFor(activity, code).exists();
        String log = cfg.getLatestVersionChangelog();
        String msg = "Versi " + cfg.getLatestVersionName() + " tersedia."
                + (ready ? "\nSudah terunduh & siap dipasang." : "")
                + (log != null && !log.isEmpty() ? "\n\n" + log : "");

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Pembaruan Aplikasi")
                .setMessage(msg);
        if (ready) {
            b.setPositiveButton("Pasang Sekarang", (d, w) -> installApk(activity, apkFileFor(activity, code)));
        } else {
            b.setPositiveButton("Perbarui", (d, w) -> startUpdate(activity, cfg.getLatestVersionUrl(), code));
        }
        if (mandatory) {
            b.setCancelable(false);
        } else {
            // "Nanti" = pasang lain kali; APK yang sudah diunduh tetap disimpan.
            b.setNegativeButton("Nanti", (d, w) -> cfg.setDismissedVersion(code));
        }
        b.show();
    }

    /** Fallback when the APK wasn't pre-downloaded: download now, then install. */
    private static void startUpdate(Activity activity, String url, int code) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(activity, "URL APK belum diatur oleh admin", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Mengunduh pembaruan…", Toast.LENGTH_SHORT).show();
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            File apk = download(app, url, code);
            if (apk != null) {
                new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app))).setDownloadedVersion(code);
            }
            activity.runOnUiThread(() -> {
                if (apk != null) {
                    installApk(activity, apk);
                } else {
                    Toast.makeText(activity, "Gagal mengunduh pembaruan", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static File apkFileFor(Context ctx, int code) {
        return new File(new File(ctx.getExternalFilesDir(null), "updates"), "update-" + code + ".apk");
    }

    /** Download to update-<code>.apk via a .part temp + atomic rename; clean older APKs. */
    private static File download(Context ctx, String url, int code) {
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "updates");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = apkFileFor(ctx, code);
            File tmp = new File(dir, "update-" + code + ".apk.part");
            OkHttpClient client = new OkHttpClient();
            Request req = new Request.Builder().url(url).build();
            try (Response r = client.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                try (InputStream in = r.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                }
            }
            if (out.exists() && !out.delete()) { /* will overwrite via rename below */ }
            if (!tmp.renameTo(out)) { tmp.delete(); return null; }
            cleanupOld(dir, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Remove stale update files (older versions / interrupted .part) to free space. */
    private static void cleanupOld(File dir, File keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.equals(keep) && f.getName().startsWith("update")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /**
     * Launch the system installer for {@code apk}. On Android 8+ the user must first
     * allow "install unknown apps" for this app; if not granted yet, route them to that
     * setting and let them return and tap Pasang again.
     */
    private static void installApk(Activity activity, File apk) {
        Context ctx = activity.getApplicationContext();
        if (apk == null || !apk.exists()) {
            Toast.makeText(ctx, "Berkas pembaruan tidak ditemukan", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Izinkan Pemasangan")
                    .setMessage("Agar pembaruan bisa dipasang, izinkan aplikasi ini memasang APK. "
                            + "Kamu akan diarahkan ke Pengaturan — aktifkan \"Izinkan dari sumber ini\", "
                            + "lalu kembali dan tekan Pasang lagi.")
                    .setPositiveButton("Buka Pengaturan", (d, w) -> {
                        try {
                            activity.startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + ctx.getPackageName())));
                        } catch (Throwable t) {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                            } catch (Throwable ignored) {
                                Toast.makeText(ctx, "Buka Pengaturan > Pasang aplikasi tak dikenal",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            return;
        }
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
