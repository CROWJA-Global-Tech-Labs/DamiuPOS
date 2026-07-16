package com.crowja.damiupos.sync;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Mandatory in-app update. The dashboard just uploads an APK; {@code /api/version} advertises its
 * SHA-256. A device whose own installed APK hash differs MUST update. The prompt is non-dismissible
 * except "Update Sekarang" or "Tunda 1 jam" — snoozing hides it for 1 hour, then it notifies again.
 */
public final class VersionUpdater {

    private VersionUpdater() {}

    /** 1-hour snooze for "Tunda 1 jam". */
    public static final long SNOOZE_MS = 60 * 60 * 1000L;

    /** Background REST check → store the published APK → pre-download → prompt on the UI thread. */
    public static void checkAndPrompt(Activity activity) {
        Context app = activity.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!cfg.isEnrolled()) return;

        new Thread(() -> {
            try {
                JSONObject r = new SyncApi(cfg).version(cfg.getBaseUrl());
                OnlineNotifier.handleUpdate(app, cfg, r);   // stores latest + maybe notifies
            } catch (Throwable ignored) {}
            // Kontrol Versi: heartbeat segar saat aplikasi dibuka — sekaligus melaporkan versi
            // (me() membawa ?vc/&vn) dan mengambil status blokir versi ini dari dashboard.
            try {
                JSONObject me = new SyncApi(cfg).me();
                cfg.setVersionBlocked(me.optBoolean("version_blocked", false),
                        com.crowja.damiupos.BuildConfig.VERSION_CODE,
                        me.optString("blocked_note", ""));
            } catch (Throwable ignored) {}
            autoDownloadIfNeeded(app);   // blocking on this bg thread; ready before we prompt
            activity.runOnUiThread(() -> {
                maybePrompt(activity);
                maybePromptBlocked(activity);   // mengalah bila dialog Pembaruan Wajib tampil
            });
        }).start();
    }

    /** True when the server published an APK whose hash differs from this device's installed APK. */
    public static boolean updateNeeded(Context ctx, SyncSettings cfg) {
        String serverSha = cfg.getUpdateSha();
        if (serverSha == null || serverSha.isEmpty()) return false;
        String mine = myApkSha(ctx, cfg);
        return mine != null && !serverSha.equalsIgnoreCase(mine);
    }

    /** Pre-download the published APK so "Update Sekarang" installs instantly. Call OFF the main thread. */
    public static void autoDownloadIfNeeded(Context ctx) {
        Context app = ctx.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!updateNeeded(app, cfg)) return;
        File apk = apkFile(app);
        String have = sha256(apk);
        if (apk.exists() && have != null && have.equalsIgnoreCase(cfg.getUpdateSha())) return;
        download(app, cfg.getUpdateUrl());
    }

    /** Show the mandatory update dialog if an update is needed and not currently snoozed. */
    public static void maybePrompt(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context app = activity.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!updateNeeded(app, cfg)) return;
        if (System.currentTimeMillis() < cfg.getSnoozeUntil()) return;

        File apk = apkFile(app);
        String have = sha256(apk);
        boolean ready = apk.exists() && have != null && have.equalsIgnoreCase(cfg.getUpdateSha());
        String msg = "Versi baru aplikasi wajib dipasang untuk melanjutkan."
                + (ready ? "\nSudah terunduh & siap dipasang." : "");

        new AlertDialog.Builder(activity)
                .setTitle("Pembaruan Wajib")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Update Sekarang", (d, w) -> updateNow(activity))
                .setNegativeButton("Tunda 1 jam", (d, w) -> snooze(app))
                .show();
    }

    /** Aksi "Update Sekarang" yang bisa dipakai dialog mana pun: pasang APK yang sudah
     *  terunduh & cocok hash, else unduh dulu dari apk_url terbit. */
    public static void updateNow(Activity activity) {
        Context app = activity.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        File apk = apkFile(app);
        String have = sha256(apk);
        boolean ready = apk.exists() && have != null && have.equalsIgnoreCase(cfg.getUpdateSha());
        if (ready) installApk(activity, apk);
        else startUpdate(activity, cfg.getUpdateUrl());
    }

    /**
     * Popup "Versi Dinonaktifkan" (Kontrol Versi dashboard): versi APK yang SEDANG berjalan
     * ditandai tidak boleh dipakai → karyawan diminta meng-update. Dialog Pembaruan Wajib
     * (hash-based, {@link #maybePrompt}) lebih kuat dan sudah meminta update — bila ia berlaku,
     * popup ini mengalah supaya tidak dobel. "Tunda 1 jam" berbagi snooze dengan pembaruan wajib.
     */
    public static void maybePromptBlocked(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Context app = activity.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        if (!cfg.isEnrolled()) return;
        if (!cfg.isVersionBlocked(com.crowja.damiupos.BuildConfig.VERSION_CODE)) return;
        if (updateNeeded(app, cfg)) return;   // dialog Pembaruan Wajib sudah menangani update
        if (System.currentTimeMillis() < cfg.getSnoozeUntil()) return;

        String note = cfg.getVersionBlockedNote();
        String msg = "Versi aplikasi ini (" + com.crowja.damiupos.BuildConfig.VERSION_NAME
                + ") telah DINONAKTIFKAN dari dashboard."
                + (note != null && !note.isEmpty() ? "\n\nCatatan admin: " + note : "")
                + "\n\nSilakan update aplikasi untuk melanjutkan bekerja.";
        new AlertDialog.Builder(activity)
                .setTitle("⚠️ Versi Dinonaktifkan")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Update Sekarang", (d, w) -> updateNow(activity))
                .setNegativeButton("Tunda 1 jam", (d, w) -> snooze(app))
                .show();
    }

    /** Snooze for 1 hour and schedule a reminder notification at the deadline. */
    public static void snooze(Context ctx) {
        Context app = ctx.getApplicationContext();
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
        long until = System.currentTimeMillis() + SNOOZE_MS;
        cfg.setSnoozeUntil(until);
        UpdateReminderReceiver.scheduleAt(app, until);
        Toast.makeText(app, "Diingatkan lagi 1 jam lagi", Toast.LENGTH_SHORT).show();
    }

    /** SHA-256 of this device's own installed APK, cached by PackageInfo.lastUpdateTime. */
    public static String myApkSha(Context ctx, SyncSettings cfg) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long lut = pi.lastUpdateTime;
            if (cfg.getCachedMyApkShaAt() == lut) {
                String cached = cfg.getCachedMyApkSha();
                if (cached != null && !cached.isEmpty()) return cached;
            }
            String path = ctx.getApplicationInfo().sourceDir;
            String sha = sha256(new File(path));
            if (sha != null) cfg.setCachedMyApkSha(sha, lut);
            return sha;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Fallback when the APK wasn't pre-downloaded: download now, then install. */
    private static void startUpdate(Activity activity, String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(activity, "APK belum diunggah oleh admin", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Mengunduh pembaruan…", Toast.LENGTH_SHORT).show();
        Context app = activity.getApplicationContext();
        new Thread(() -> {
            File apk = download(app, url);
            activity.runOnUiThread(() -> {
                if (apk != null) installApk(activity, apk);
                else Toast.makeText(activity, "Gagal mengunduh pembaruan", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private static File apkFile(Context ctx) {
        return new File(new File(ctx.getExternalFilesDir(null), "updates"), "update.apk");
    }

    /** Download to update.apk via a .part temp + atomic rename. */
    private static File download(Context ctx, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            File dir = new File(ctx.getExternalFilesDir(null), "updates");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = apkFile(ctx);
            File tmp = new File(dir, "update.apk.part");
            // Berbagi ConnectionPool proses; timeout baca lebih panjang untuk unduhan APK besar.
            OkHttpClient client = Http.SHARED.newBuilder()
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build();
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
            if (out.exists() && !out.delete()) { /* overwrite via rename */ }
            if (!tmp.renameTo(out)) { tmp.delete(); return null; }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** SHA-256 of a file as lowercase hex, or null on error. */
    static String sha256(File f) {
        if (f == null || !f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Launch the system installer for {@code apk}. On Android 8+ the user must first allow
     * "install unknown apps" for this app; if not granted yet, route them to that setting.
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
                            + "lalu kembali dan tekan Update lagi.")
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
