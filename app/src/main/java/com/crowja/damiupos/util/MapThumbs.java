package com.crowja.damiupos.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.crowja.damiupos.MapTiles;
import com.crowja.damiupos.sync.Http;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Miniatur peta statis (gambar biasa, BUKAN WebView) untuk kartu daftar — dirakit dari tile ESRI
 * yang sama dengan semua peta app ({@link MapTiles}), dengan pin lokasi TEPAT di tengah: jendela
 * 256×256 px dipotong dari 1–4 tile di sekitar titik (beda dengan stempel selfie yang memakai satu
 * tile apa adanya sehingga pinnya bisa di pojok).
 *
 * <p>Tile disimpan di cache disk 30 hari (pelanggan berdekatan berbagi tile yang sama, dan rute ider
 * cenderung berulang), miniatur jadi disimpan di LRU memori. Gagal unduh (offline) = placeholder
 * abu-abu; bind berikutnya mencoba lagi.
 */
public final class MapThumbs {

    private MapThumbs() {}

    private static final int Z = 17;            // ±1,2 m/px → jendela 256 px ≈ 300 m: jalan & gang terbaca
    private static final int SIZE = 256;
    private static final long TILE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = Http.SHARED.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** Kunci miniatur (5 desimal ≈ 1 m — cermin Customer::coordKey di web). */
    public static String key(double lat, double lng) {
        return String.format(Locale.US, "%.5f,%.5f", lat, lng);
    }

    /**
     * Pasang miniatur ke {@code iv} (asinkron). Aman untuk view yang didaur ulang RecyclerView: hasil
     * hanya dipasang bila tag view masih menunjuk titik yang sama.
     */
    public static void load(ImageView iv, double lat, double lng) {
        final String k = key(lat, lng);
        iv.setTag(k);
        Bitmap hit = MEM.get(k);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageDrawable(new ColorDrawable(0xFFE0E0E0));
        final Context app = iv.getContext().getApplicationContext();
        EXEC.execute(() -> {
            Bitmap b = MEM.get(k);
            if (b == null) {
                b = render(app, lat, lng);
                if (b == null) return;
                MEM.put(k, b);
            }
            final Bitmap out = b;
            MAIN.post(() -> {
                if (k.equals(iv.getTag())) iv.setImageBitmap(out);
            });
        });
    }

    private static Bitmap render(Context ctx, double lat, double lng) {
        double n = 1 << Z;
        double gx = (lng + 180.0) / 360.0 * n * 256.0;
        double latRad = Math.toRadians(lat);
        double gy = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n * 256.0;
        int left = (int) Math.floor(gx - SIZE / 2.0);
        int top = (int) Math.floor(gy - SIZE / 2.0);

        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFFE0E0E0);
        boolean any = false;
        int tx0 = Math.floorDiv(left, 256), tx1 = Math.floorDiv(left + SIZE - 1, 256);
        int ty0 = Math.floorDiv(top, 256), ty1 = Math.floorDiv(top + SIZE - 1, 256);
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                Bitmap t = tile(ctx, tx, ty);
                if (t == null) continue;
                c.drawBitmap(t, tx * 256 - left, ty * 256 - top, filter);
                t.recycle();
                any = true;
            }
        }
        if (!any) {
            out.recycle();
            return null;
        }
        drawPin(c, (float) (gx - left), (float) (gy - top));
        return out;
    }

    /** Pin tetes merah + titik putih — ujung runcingnya tepat di koordinat. */
    private static void drawPin(Canvas c, float x, float y) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.Path drop = new android.graphics.Path();
        float r = 13f;
        float cy = y - 2.3f * r;
        drop.moveTo(x, y);
        drop.cubicTo(x - 0.4f * r, cy + 1.4f * r, x - r, cy + 0.9f * r, x - r, cy);
        drop.arcTo(new android.graphics.RectF(x - r, cy - r, x + r, cy + r), 180f, 180f, false);
        drop.cubicTo(x + r, cy + 0.9f * r, x + 0.4f * r, cy + 1.4f * r, x, y);
        drop.close();
        p.setColor(Color.argb(70, 0, 0, 0));
        c.drawCircle(x, y, 4f, p);
        p.setColor(0xFFD32F2F);
        c.drawPath(drop, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(Color.WHITE);
        c.drawPath(drop, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, cy, 0.42f * r, p);
    }

    /** Tile dari cache disk (≤30 hari) atau unduh; tile basi tetap dipakai bila jaringan gagal. */
    private static Bitmap tile(Context ctx, int x, int y) {
        File f = new File(ctx.getCacheDir(), "map_tiles/" + Z + "/" + x + "/" + y + ".png");
        boolean fresh = f.isFile() && f.length() > 0
                && System.currentTimeMillis() - f.lastModified() < TILE_MAX_AGE_MS;
        if (fresh) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        Request req = new Request.Builder()
                .url(MapTiles.tileUrl(Z, x, y))
                .header("User-Agent", MapTiles.userAgent())
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (resp.isSuccessful() && resp.body() != null) {
                byte[] bytes = resp.body().bytes();
                Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (b != null) {
                    save(f, bytes);
                    return b;
                }
            }
        } catch (Exception ignored) {
            // offline / timeout → jatuh ke tile basi di bawah
        }
        return f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private static void save(File f, byte[] bytes) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            // cache hanya optimasi
        }
    }
}
