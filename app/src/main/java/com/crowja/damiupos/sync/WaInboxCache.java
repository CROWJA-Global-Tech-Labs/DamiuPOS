package com.crowja.damiupos.sync;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache balasan Chat WA (daftar akun + percakapan per akun/jendela waktu) untuk pola
 * "tampilkan yang tersimpan dulu, segarkan di latar": ganti akun / buka ulang layar langsung terisi,
 * bukan menunggu Bridge. Memori (per proses) + berkas di cacheDir/wa_inbox (bertahan antar buka-tutup
 * aplikasi; dibersihkan Android bila ruang menipis). Isinya JSON mentah dari {@link WaInboxApi}.
 */
public final class WaInboxCache {

    private static final ConcurrentHashMap<String, String> MEM = new ConcurrentHashMap<>();

    private WaInboxCache() {}

    public static String accountsKey() { return "accounts"; }

    public static String convKey(String account, int days) { return "conv_" + account + "_" + days; }

    @Nullable
    public static String get(Context ctx, String key) {
        String hit = MEM.get(key);
        if (hit != null) return hit;
        File f = file(ctx, key);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            MEM.put(key, s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static void put(Context ctx, String key, String json) {
        MEM.put(key, json);
        try {
            File f = file(ctx, key);
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // cache hanya percepatan — gagal menulis tak boleh mengganggu layar
        }
    }

    private static File file(Context ctx, String key) {
        return new File(new File(ctx.getCacheDir(), "wa_inbox"), key.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }
}
