package com.crowja.damiupos.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import com.crowja.damiupos.sync.WaInboxApi;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Foto profil kontak WhatsApp (dari WA Bridge lewat {@code /api/wa-inbox/avatar}) untuk daftar
 * percakapan &amp; header chat. Cache memori (LRU) + cache disk 24 jam di cacheDir/wa_avatars; kontak
 * tanpa foto diingat selama proses berjalan supaya tak diminta ulang tiap scroll. Gagal/kosong = pemanggil
 * tetap menampilkan inisial.
 */
public final class WaAvatarLoader {

    private static final long DISK_TTL_MS = 24L * 3600 * 1000;
    private static final LruCache<String, Bitmap> MEM = new LruCache<>(80);
    private static final Set<String> NONE = new HashSet<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WaAvatarLoader() {}

    /** Bulatkan sebuah view gambar (foto profil). */
    public static void makeRound(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        v.setClipToOutline(true);
    }

    /**
     * Muat foto untuk {@code iv}; {@code iv} disembunyikan sampai foto siap (inisial di bawahnya
     * tetap tampil). Tag view dipakai menolak jawaban basi saat baris didaur ulang.
     */
    public static void into(Context ctx, String account, String jid, ImageView iv) {
        final String key = account + "|" + jid;
        iv.setTag(key);
        Bitmap hit = MEM.get(key);
        if (hit != null) { iv.setImageBitmap(hit); iv.setVisibility(View.VISIBLE); return; }
        iv.setVisibility(View.GONE);
        iv.setImageDrawable(null);
        load(ctx, account, jid, bm -> {
            if (bm != null && key.equals(iv.getTag())) { iv.setImageBitmap(bm); iv.setVisibility(View.VISIBLE); }
        });
    }

    /** Muat di thread latar; {@code done} di UI thread (null = tak ada foto / gagal). */
    public static void load(Context ctx, String account, String jid, Consumer<Bitmap> done) {
        final String key = account + "|" + jid;
        final Context app = ctx.getApplicationContext();
        Bitmap hit = MEM.get(key);
        if (hit != null) { done.accept(hit); return; }
        synchronized (NONE) {
            if (NONE.contains(key)) { done.accept(null); return; }
        }
        POOL.execute(() -> {
            Bitmap bm = null;
            try {
                File dir = new File(app.getCacheDir(), "wa_avatars");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, Integer.toHexString(key.hashCode()) + ".img");
                boolean fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < DISK_TTL_MS;
                if (!fresh) {
                    int code = new WaInboxApi(app).downloadAvatar(account, jid, f);
                    if (code == 404) {
                        synchronized (NONE) { NONE.add(key); }
                    }
                    if (code != 200) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                if (f.exists() && f.length() > 0) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 2;   // 44dp tampilan — separuh resolusi sudah tajam
                    bm = BitmapFactory.decodeFile(f.getPath(), o);
                    if (bm != null) MEM.put(key, bm);
                }
            } catch (Exception ignored) {
                // tanpa foto: pemanggil tetap menampilkan inisial
            }
            final Bitmap out = bm;
            MAIN.post(() -> done.accept(out));
        });
    }
}
