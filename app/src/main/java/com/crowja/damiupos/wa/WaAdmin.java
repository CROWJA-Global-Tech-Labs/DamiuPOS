package com.crowja.damiupos.wa;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

/**
 * Bersama untuk layar menu 💬 WhatsApp (cermin grup menu web "WhatsApp"): gerbang Admin + pesan galat
 * server. Server ({@code /api/admin/wa/*}) tetap penentu akhir — staf Admin aktif cabang ini.
 */
public final class WaAdmin {

    private WaAdmin() {}

    /** Perangkat tersinkron + login Admin + staf punya uuid server → uuid staf; selain itu toast + finish, null. */
    @Nullable
    public static String gate(Activity a) {
        SyncSettings cfg = cfg(a);
        if (!cfg.isEnrolled()) {
            Toast.makeText(a, "Perangkat belum terhubung ke server (Pengaturan → Sinkronisasi).", Toast.LENGTH_LONG).show();
            a.finish();
            return null;
        }
        if (!UserDao.isCurrentUserAdmin(a)) {
            Toast.makeText(a, "Menu WhatsApp hanya untuk Admin.", Toast.LENGTH_LONG).show();
            a.finish();
            return null;
        }
        String staff = LocationReporter.currentStaffUuid(a);
        if (staff == null || staff.isEmpty()) {
            Toast.makeText(a, "Akun Admin ini belum tersinkron ke server — sinkronkan data dulu.", Toast.LENGTH_LONG).show();
            a.finish();
            return null;
        }
        return staff;
    }

    public static SyncSettings cfg(Activity a) {
        return new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(a)));
    }

    public static SyncApi api(Activity a) {
        return new SyncApi(cfg(a));
    }

    /** Pesan yang layak ditampilkan dari galat panggilan API. */
    public static String errorMessage(Exception e) {
        if (e instanceof SyncApi.SyncException) {
            SyncApi.SyncException se = (SyncApi.SyncException) e;
            String m = se.serverMessage();
            return m != null ? m : "Gagal (kode " + se.code + ").";
        }
        return "Gagal — periksa koneksi internet.";
    }

    /** {@code message} balasan server, atau {@code fallback}. */
    public static String message(@Nullable JSONObject r, String fallback) {
        String m = r != null ? r.optString("message", "") : "";
        return m == null || m.trim().isEmpty() || "null".equals(m) ? fallback : m;
    }
}
