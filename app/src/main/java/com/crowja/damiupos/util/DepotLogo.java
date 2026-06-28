package com.crowja.damiupos.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Logo depot disinkron dari web (SettingsDao.KEY_DEPOT_LOGO_URL). Diunduh & di-cache lokal lalu
 * ditampilkan di Pengaturan + struk. Re-download otomatis saat URL berubah (web menambah
 * ?v=timestamp tiap unggah). App ini tak punya library gambar → unduh manual + BitmapFactory.
 */
public final class DepotLogo {

    private static final String PREFS = "depot_logo";
    private static final String KEY_CACHED_URL = "cached_url";
    private static final String FILE = "depot_logo.img";

    private DepotLogo() {}

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** True bila logo tersedia di cache lokal. */
    public static boolean hasCached(Context c) {
        File f = cacheFile(c);
        return f.exists() && f.length() > 0;
    }

    /** Bitmap logo dari cache (TANPA mengunduh), atau null bila belum ada. */
    public static Bitmap cachedBitmap(Context c) {
        File f = cacheFile(c);
        if (!f.exists() || f.length() == 0) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Pastikan logo terunduh sesuai URL terbaru. Unduh hanya bila URL berubah; kosong → hapus cache.
     * Panggil dari BACKGROUND thread. Return true bila berkas cache berubah (perlu refresh UI).
     */
    public static boolean ensureDownloaded(Context c) {
        SettingsDao dao = new SettingsDao(DatabaseHelper.getInstance(c));
        String url = dao.getDepotLogoUrl();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = p.getString(KEY_CACHED_URL, "");

        if (url == null || url.isEmpty()) {
            if (!cached.isEmpty() || hasCached(c)) {     // logo dihapus di web → bersihkan
                cacheFile(c).delete();
                p.edit().remove(KEY_CACHED_URL).apply();
                return true;
            }
            return false;
        }
        if (url.equals(cached) && hasCached(c)) {
            return false;                                 // sudah mutakhir
        }
        if (download(url, cacheFile(c))) {
            p.edit().putString(KEY_CACHED_URL, url).apply();
            return true;
        }
        return false;
    }

    private static boolean download(String urlStr, File dest) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.connect();
            if (conn.getResponseCode() != 200) return false;
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            // Validasi benar-benar gambar sebelum menggantikan cache.
            if (BitmapFactory.decodeFile(tmp.getAbsolutePath()) == null) {
                tmp.delete();
                return false;
            }
            if (dest.exists()) dest.delete();
            return tmp.renameTo(dest);
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Tampilkan logo di ImageView: pakai cache dulu (bila ada), lalu unduh ulang di background bila
     * URL berubah & refresh. Tanpa logo → ImageView disembunyikan (GONE).
     */
    public static void loadInto(ImageView iv) {
        Context app = iv.getContext().getApplicationContext();
        Bitmap cached = cachedBitmap(app);
        iv.setImageBitmap(cached);
        iv.setVisibility(cached != null ? View.VISIBLE : View.GONE);

        new Thread(() -> {
            if (ensureDownloaded(app)) {
                final Bitmap bmp = cachedBitmap(app);
                new Handler(Looper.getMainLooper()).post(() -> {
                    iv.setImageBitmap(bmp);
                    iv.setVisibility(bmp != null ? View.VISIBLE : View.GONE);
                });
            }
        }).start();
    }
}
