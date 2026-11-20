package com.crowja.damiupos.util;

import android.content.Context;

import androidx.annotation.Nullable;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jam senyap cabang (web Konfigurasi → "🔕 Jam Senyap Notifikasi"), disinkron lewat app_settings {@code quiet_hours}:
 * JSON {@code [{"start":"21:00","end":"05:00"}, …]}. Selama jam HP berada di salah satu rentang, SEMUA bunyi notifikasi
 * aplikasi dibisukan — pemutar suara langsung dilewati dan notifikasi diposting {@code setSilent(true)} (tetap muncul,
 * tanpa bunyi & getar). Rentang boleh melewati tengah malam (start &gt; end); batas akhir tidak termasuk.
 */
public final class QuietHours {
    private static final Pattern RANGE = Pattern.compile(
            "\\{\\s*\"start\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"end\"\\s*:\\s*\"([^\"]*)\"\\s*\\}");

    private QuietHours() {
    }

    /** Sekarang jam senyap? Galat apa pun → tidak senyap (jangan sampai notifikasi penting hilang diam-diam karena bug). */
    public static boolean isQuiet(@Nullable Context ctx) {
        if (ctx == null) return false;
        try {
            String raw = new SettingsDao(DatabaseHelper.getInstance(ctx.getApplicationContext()))
                    .get(SettingsDao.KEY_QUIET_HOURS, "");
            Calendar c = Calendar.getInstance();
            return isQuietAt(raw, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE));
        } catch (Exception e) {
            return false;
        }
    }

    /** Logika murni (diuji unit): {@code minuteOfDay} 0..1439 berada di salah satu rentang {@code json}? */
    public static boolean isQuietAt(@Nullable String json, int minuteOfDay) {
        if (json == null || json.trim().isEmpty()) return false;
        // Regex, bukan org.json: logika ini diuji unit di JVM biasa (org.json Android tak tersedia di sana). Server menulis
        // tiap rentang persis {"start":"HH:MM","end":"HH:MM"}.
        Matcher m = RANGE.matcher(json);
        while (m.find()) {
            int s = minutes(m.group(1));
            int e = minutes(m.group(2));
            if (s < 0 || e < 0 || s == e) continue;
            boolean in = s < e ? (minuteOfDay >= s && minuteOfDay < e) : (minuteOfDay >= s || minuteOfDay < e);
            if (in) return true;
        }
        return false;
    }

    /** "HH:MM" → menit sejak 00:00, atau -1 bila tak sah. */
    static int minutes(@Nullable String hhmm) {
        if (hhmm == null) return -1;
        String[] p = hhmm.trim().split("[:.]");
        if (p.length != 2) return -1;
        try {
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
