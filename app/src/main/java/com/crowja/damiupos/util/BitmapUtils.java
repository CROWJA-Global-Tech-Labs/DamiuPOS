package com.crowja.damiupos.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.DisplayMetrics;
import android.util.LruCache;

import androidx.exifinterface.media.ExifInterface;

/**
 * Pemuat bitmap hemat memori untuk semua foto (pelanggan, struk, pengeluaran,
 * selfie). Mengganti pola {@code BitmapFactory.decodeFile(path)} full-res yang
 * boros (foto 12MP = ~48MB sebagai ARGB_8888 → OOM/jank di HP lama).
 *
 * <p>Strategi: baca dimensi dulu, hitung {@code inSampleSize} agar hasil decode
 * mendekati ukuran yang dibutuhkan, pakai {@link Bitmap.Config#RGB_565} (foto
 * opaque → hemat 50% memori), lalu koreksi rotasi EXIF. Thumbnail list di-cache
 * (LruCache) supaya scroll tidak men-decode ulang.
 */
public final class BitmapUtils {

    private BitmapUtils() {}

    // Cache thumbnail list ~6 MB. Bitmap yang di-cache TIDAK boleh di-recycle
    // pemanggil (adapter hanya setImageBitmap, tidak recycle → aman).
    private static final LruCache<String, Bitmap> THUMB_CACHE =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /**
     * Decode {@code path} dengan downsample mendekati {@code reqW}×{@code reqH}
     * + RGB_565 + koreksi EXIF. Null kalau gagal. Tidak di-cache.
     */
    public static Bitmap decodeSampled(String path, int reqW, int reqH) {
        if (path == null || path.isEmpty()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = calcInSampleSize(o.outWidth, o.outHeight,
                    Math.max(1, reqW), Math.max(1, reqH));
            // Jangan biarkan sisi mana pun melampaui batas tekstur GPU → kalau tidak,
            // hardware Canvas menolak menggambar ("bitmap too large") & foto tampil kosong.
            while (sample < 32 && (o.outWidth / sample > 4096 || o.outHeight / sample > 4096)) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) {
                // Sebagian file (mis. screenshot Samsung dgn metadata ekstra) gagal di-decode
                // ke RGB_565 → kembalikan ke ARGB_8888 supaya foto tetap tampil.
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bmp = BitmapFactory.decodeFile(path, o);
            }
            return applyExif(bmp, path);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Decode disesuaikan ukuran layar — untuk viewer foto layar penuh. */
    public static Bitmap decodeForScreen(Context ctx, String path) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return decodeSampled(path, dm.widthPixels, dm.heightPixels);
    }

    /**
     * Thumbnail untuk item list (ter-cache). Pakai di adapter RecyclerView agar
     * scroll tidak men-decode ulang. Jangan recycle hasilnya.
     */
    public static Bitmap cachedThumb(String path, int reqW, int reqH) {
        if (path == null || path.isEmpty()) return null;
        String key = path + '#' + reqW + 'x' + reqH;
        Bitmap cached = THUMB_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap bmp = decodeSampled(path, reqW, reqH);
        if (bmp != null) THUMB_CACHE.put(key, bmp);
        return bmp;
    }

    private static int calcInSampleSize(int w, int h, int reqW, int reqH) {
        int sample = 1;
        while ((w / (sample * 2)) >= reqW && (h / (sample * 2)) >= reqH) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap applyExif(Bitmap bmp, String path) {
        if (bmp == null) return null;
        try {
            int orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rot = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0,
                        bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                return rotated;
            }
        } catch (Throwable ignored) {}
        return bmp;
    }

    /**
     * Salin gambar yang dipilih dari galeri (content:// Uri) ke {@code dest} (file lokal app),
     * supaya alur foto yang sudah ada (path lokal → MediaUploader) tetap berlaku. EXIF ikut tersalin
     * apa adanya, jadi koreksi rotasi {@link #decodeSampled} tetap bekerja. Return true bila sukses.
     */
    /**
     * Downscale + recompress a photo for upload: long side ≤ {@code maxDim}, JPEG {@code quality}.
     * Keeps uploads small and safely under the server's size cap — full-res camera/gallery photos
     * (often &gt; 5 MB) would otherwise be rejected (422) and never reach the dashboard. EXIF rotation
     * is already baked in by {@link #decodeSampled}. Writes to {@code dest}; true on success.
     */
    public static boolean compressForUpload(String srcPath, java.io.File dest, int maxDim, int quality) {
        Bitmap bmp = decodeSampled(srcPath, maxDim, maxDim);
        if (bmp == null) return false;
        try {
            int longSide = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longSide > maxDim) {
                float r = maxDim / (float) longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bmp.getWidth() * r)),
                        Math.max(1, Math.round(bmp.getHeight() * r)), true);
                if (scaled != bmp) { bmp.recycle(); bmp = scaled; }
            }
            boolean ok;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                // compress() menelan IOException (mis. disk penuh) dan mengembalikan false,
                // meninggalkan JPEG parsial — WAJIB dicek; kalau tidak, file korup dianggap sukses
                // lalu MENIMPA foto asli via rename di pemanggil.
                ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
                out.flush();
            }
            if (!ok) { dest.delete(); return false; }
            return dest.length() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            bmp.recycle();
        }
    }

    /**
     * Unduh gambar dari {@code url} ke cache (sekali; dipakai ulang bila sudah ada) lalu kembalikan
     * file-nya. Untuk menampilkan foto baris hasil sync yang hanya punya photo_url (tanpa file lokal),
     * mis. struk pengeluaran/pelanggan dari device lain. Panggil OFF main thread. Null bila gagal.
     */
    public static java.io.File downloadToCache(Context ctx, String url, String name) {
        if (ctx == null || url == null || url.isEmpty() || name == null) return null;
        java.net.HttpURLConnection conn = null;
        java.io.File tmp = null;
        try {
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "remote_img");
            if (!dir.exists() && !dir.mkdirs()) return null;
            java.io.File out = new java.io.File(dir, name);
            if (out.exists() && out.length() > 0) return out;   // cached
            tmp = new java.io.File(dir, name + ".part");
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            // Sebagian CDN (mis. Hostinger yang meng-host dashboard) MENYAJIKAN varian gambar berbeda
            // (mengoptimasi/mengecilkan) atau memblok berdasarkan User-Agent default Java/Android. Kirim
            // UA + Accept ala-browser supaya bytes yang diterima konsisten dengan yang tampil di web.
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DAMIUPOS");
            conn.setRequestProperty("Accept", "image/*,*/*");
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                android.util.Log.w("DAMIU", "downloadToCache HTTP " + code + " " + url);
                return null;
            }
            long expected = conn.getContentLengthLong();   // -1 bila server tak kirim Content-Length
            long written = 0;
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) { os.write(buf, 0, n); written += n; }
            }
            // TOLAK unduhan terpotong (jaringan HP putus di tengah) — kalau tidak, file rusak ter-cache
            // permanen: decode gagal → avatar kosong SELAMANYA (cache-hit mencegah unduh ulang).
            if (written == 0 || (expected >= 0 && written != expected)) {
                android.util.Log.w("DAMIU", "downloadToCache truncated " + written + "/" + expected + " " + url);
                tmp.delete();
                return null;
            }
            if (!tmp.renameTo(out)) { tmp.delete(); return null; }
            return out;
        } catch (Throwable t) {
            android.util.Log.w("DAMIU", "downloadToCache error " + url + " : " + t, t);
            if (tmp != null) tmp.delete();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static boolean copyUriToFile(Context ctx, android.net.Uri uri, java.io.File dest) {
        if (ctx == null || uri == null || dest == null) return false;
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
