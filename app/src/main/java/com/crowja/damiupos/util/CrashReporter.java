package com.crowja.damiupos.util;

import android.content.Context;
import android.os.Build;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.Http;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Pencatat crash. Saat aplikasi tertutup paksa, stack trace disimpan ke berkas; saat aplikasi dibuka
 * lagi, laporan dikirim ke server ({@code POST /api/client-crash}, tanpa token — crash bisa terjadi
 * sebelum HP terdaftar, mis. saat memindai QR provisioning) lalu berkasnya dihapus. Tanpa SDK pihak
 * ketiga: cukup untuk melacak crash yang hanya terlihat sebagai "DAMIU POS ditutup karena bug".
 */
public final class CrashReporter {

    private static final String FILE = "last_crash.txt";

    private CrashReporter() {}

    /** Pasang sekali di Application.onCreate (paling awal). Handler bawaan tetap dipanggil. */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("thread=" + thread.getName());
                ex.printStackTrace(pw);
                pw.flush();
                String body = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + "\n" + sw;
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    /** Kirim laporan crash tertunda (bila ada) di latar; berkas dihapus setelah server menerima. */
    public static void sendPending(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final File f = new File(app.getFilesDir(), FILE);
        if (!f.exists()) return;
        new Thread(() -> {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String at = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : "";
                if (text.length() > 19000) text = text.substring(0, 19000);
                String base = SyncSettings.DEFAULT_BASE_URL;
                String deviceUuid = "";
                try {
                    SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
                    if (!cfg.getBaseUrl().isEmpty()) base = cfg.getBaseUrl();
                    deviceUuid = cfg.getDeviceUuid();
                } catch (Throwable ignored) {
                    // DB bisa jadi sumber crash-nya — tetap laporkan ke server bawaan
                }
                JSONObject body = new JSONObject()
                        .put("trace", text)
                        .put("version_code", BuildConfig.VERSION_CODE)
                        .put("version_name", BuildConfig.VERSION_NAME)
                        .put("device", (Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE).trim())
                        .put("device_uuid", deviceUuid)
                        .put("at", at);
                Request req = new Request.Builder()
                        .url(base.replaceAll("/+$", "") + "/api/client-crash")
                        .header("Accept", "application/json")
                        .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                        .build();
                try (Response r = Http.SHARED.newCall(req).execute()) {
                    if (r.isSuccessful()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Throwable ignored) {
                // coba lagi saat aplikasi dibuka berikutnya
            }
        }).start();
    }
}
