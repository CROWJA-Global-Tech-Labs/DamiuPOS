package com.crowja.damiupos.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.crowja.damiupos.R;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Suara sirine peringatan komplain yang diatur di web Konfigurasi dan disinkron ke semua HP
 * (SettingsDao.KEY_COMPLAINT_SIREN_*): preset bawaan (res/raw), suara unggahan sendiri, atau tanpa suara.
 *
 * <p>Suara sebuah NotificationChannel tak bisa diubah setelah dibuat, jadi tiap pilihan punya id kanal sendiri
 * ({@link Choice#channelId}); kanal lama yang tak terpakai dihapus oleh ComplaintAlert. Suara unggahan diunduh
 * ke {@code filesDir/siren} dan diberikan ke sistem lewat FileProvider; bila belum/gagal diunduh dipakai
 * sirine bawaan supaya alarm tak pernah bisu karena berkas.</p>
 */
public final class ComplaintSirenSound {

    /** Id kanal lama (sirine bawaan) — tetap dipakai agar pengguna lama tak mendapat kanal ganda. */
    public static final String BASE_CHANNEL = "damiu_complaint_alarm";

    private static final String PREFS = "complaint_siren";
    private static final String KEY_CACHED_URL = "cached_url";
    private static final String DIR = "siren";
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private ComplaintSirenSound() {}

    /** Pilihan suara yang berlaku sekarang. */
    public static final class Choice {
        public final String sound;
        public final String channelId;
        @Nullable public final Uri uri;
        public final boolean repeat;

        Choice(String sound, String channelId, @Nullable Uri uri, boolean repeat) {
            this.sound = sound;
            this.channelId = channelId;
            this.uri = uri;
            this.repeat = repeat;
        }

        public boolean silent() {
            return uri == null;
        }
    }

    private static Uri raw(Context c, int resId) {
        return Uri.parse("android.resource://" + c.getPackageName() + "/" + resId);
    }

    /** Kanal TANPA suara untuk pilihan yang diputar aplikasi sendiri (SirenPlayer). */
    public static Choice silentChannelFor(Choice c) {
        return new Choice(c.sound, BASE_CHANNEL + "_app", null, c.repeat);
    }

    /** Sirine bawaan (cadangan bila kanal pilihan gagal dibuat). */
    public static Choice bawaan(Context c, boolean repeat) {
        return new Choice("bawaan", BASE_CHANNEL, raw(c, R.raw.complaint_siren), repeat);
    }

    /** Suara unggahan sendiri → diputar aplikasi (bukan suara kanal notifikasi). */
    public static boolean playedByApp(Choice c) {
        return c != null && "custom".equals(c.sound) && c.uri != null;
    }

    public static Choice current(Context c) {
        SettingsDao d = new SettingsDao(DatabaseHelper.getInstance(c));
        String sound = d.getComplaintSirenSound();
        boolean repeat = d.isComplaintSirenRepeat();
        switch (sound) {
            case "off":
                return new Choice("off", BASE_CHANNEL + "_off", null, repeat);
            case "polisi":
                return new Choice(sound, BASE_CHANNEL + "_polisi", raw(c, R.raw.complaint_siren_polisi), repeat);
            case "alarm":
                return new Choice(sound, BASE_CHANNEL + "_alarm", raw(c, R.raw.complaint_siren_alarm), repeat);
            case "bel":
                return new Choice(sound, BASE_CHANNEL + "_bel", raw(c, R.raw.complaint_siren_bel), repeat);
            case "custom": {
                String url = d.getComplaintSirenUrl();
                File f = cacheFile(c);
                SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!url.isEmpty() && f != null && f.length() > 0 && url.equals(p.getString(KEY_CACHED_URL, ""))) {
                    try {
                        Uri u = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
                        try {
                            c.grantUriPermission("com.android.systemui", u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Throwable ignored) {
                        }
                        return new Choice(sound, BASE_CHANNEL + "_c" + Integer.toHexString(url.hashCode()), u, repeat);
                    } catch (Throwable ignored) {
                        // jatuh ke bawaan di bawah
                    }
                }
                return new Choice("bawaan", BASE_CHANNEL, raw(c, R.raw.complaint_siren), repeat);
            }
            default:
                return new Choice("bawaan", BASE_CHANNEL, raw(c, R.raw.complaint_siren), repeat);
        }
    }

    @Nullable
    private static File cacheFile(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        File[] files = dir.listFiles((d, n) -> n.startsWith("complaint_custom."));
        return files != null && files.length > 0 ? files[0] : null;
    }

    private static String extensionOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        switch (ext) {
            case "mp3": case "wav": case "ogg": case "m4a": case "aac":
                return ext;
            default:
                return "mp3";
        }
    }

    /**
     * Samakan berkas cache dengan pengaturan terbaru: unduh bila URL berubah, hapus bila pilihan bukan
     * "custom"/URL kosong. Panggil dari BACKGROUND thread. True bila cache berubah.
     */
    public static boolean ensureDownloaded(Context c) {
        SettingsDao d = new SettingsDao(DatabaseHelper.getInstance(c));
        String url = d.getComplaintSirenUrl();
        boolean wantCustom = "custom".equals(d.getComplaintSirenSound()) && !url.isEmpty();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = p.getString(KEY_CACHED_URL, "");
        File existing = cacheFile(c);

        if (!wantCustom) {
            if (existing != null) {
                existing.delete();
                p.edit().remove(KEY_CACHED_URL).apply();
                return true;
            }
            return false;
        }
        if (url.equals(cached) && existing != null && existing.length() > 0) return false;

        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) return false;
        File dest = new File(dir, "complaint_custom." + extensionOf(url));
        File tmp = new File(dir, "complaint_custom.tmp");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() != 200) return false;
            long total = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) {
                        tmp.delete();
                        return false;
                    }
                    out.write(buf, 0, n);
                }
            }
            // Pastikan benar-benar audio sebelum menggantikan cache.
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(tmp.getAbsolutePath());
                if (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) == null) {
                    tmp.delete();
                    return false;
                }
            } finally {
                try {
                    mmr.release();
                } catch (Throwable ignored) {
                }
            }
            File[] old = dir.listFiles((x, nme) -> nme.startsWith("complaint_custom.") && !nme.equals("complaint_custom.tmp"));
            if (old != null) for (File o : old) o.delete();
            if (!tmp.renameTo(dest)) return false;
            p.edit().putString(KEY_CACHED_URL, url).apply();
            return true;
        } catch (Throwable t) {
            tmp.delete();
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
