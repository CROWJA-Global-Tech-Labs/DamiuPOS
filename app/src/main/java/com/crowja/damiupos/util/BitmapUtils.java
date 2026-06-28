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
}
