package com.crowja.damiupos.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.User;

/**
 * Gerbang "Tampilan Baru (gaya Gojek)". Untuk sementara HANYA untuk sesi login Admin; peran lain tak
 * pernah mengevaluasi preferensinya sehingga tampilan mereka tak berubah sama sekali.
 *
 * <p><b>Sesi admin</b> = multi-user AKTIF, ada pengguna login (uid &gt; 0), barisnya ada, berperan admin
 * dan aktif — cermin aturan kartu operator di beranda lama (MainActivity.refreshOperatorBar). Perangkat
 * single-user (pemilik tanpa login) TIDAK dihitung admin. {@link UserDao#isCurrentUserAdmin} sengaja tidak
 * dipakai: ia tak memeriksa flag multi-user (uid basi tetap terbaca admin).</p>
 *
 * <p><b>Pengaman crash-loop.</b> Beranda baru menandai "boot berjalan" di onCreate dan "boot sukses"
 * beberapa detik setelah onResume. Kalau MainActivity mendapati tanda boot masih menggantung (proses mati
 * di tengah jalan = crash), tampilan baru dimatikan otomatis dan admin kembali ke tampilan lama —
 * aplikasi tak pernah terkunci di layar yang terus crash.</p>
 */
public final class GojekUi {

    private GojekUi() {}

    private static final String PREFS = "gojek_ui";
    private static final String KEY_BOOT_PENDING = "boot_pending";
    private static final String KEY_AUTO_DISABLED = "auto_disabled_at";

    /** Multi-user aktif + login sebagai admin yang aktif. */
    public static boolean isAdminSession(Context ctx) {
        try {
            SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(ctx));
            if (!sd.isMultiUserEnabled() || sd.getCurrentUserId() <= 0) return false;
            User u = UserDao.currentUser(ctx);
            return u != null && u.isAdmin() && u.isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Preferensi perangkat "Tampilan Baru" (bawaan: aktif). Tidak disinkron. */
    public static boolean isPreferred(Context ctx) {
        try {
            return new SettingsDao(DatabaseHelper.getInstance(ctx)).isGojekUiPreferred();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setPreferred(Context ctx, boolean on) {
        new SettingsDao(DatabaseHelper.getInstance(ctx)).setGojekUiPreferred(on);
        if (on) prefs(ctx).edit().remove(KEY_BOOT_PENDING).remove(KEY_AUTO_DISABLED).commit();
    }

    /** Tampilan baru berlaku untuk sesi ini? (admin + preferensi aktif). */
    public static boolean isEnabled(Context ctx) {
        return isAdminSession(ctx) && isPreferred(ctx);
    }

    /** Overlay tema Gojek untuk layar LAMA (dipanggil sebelum setContentView, lihat DamiuApplication). */
    public static void applyOverlay(Activity a) {
        try {
            if (!isEnabled(a)) return;
            a.getTheme().applyStyle(com.crowja.damiupos.R.style.ThemeOverlay_DAMIUPOS_Gojek, true);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ pengaman crash-loop

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Panggil di awal onCreate beranda baru (ditulis sinkron supaya bertahan bila proses mati). */
    public static void markBootStart(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_BOOT_PENDING, true).commit();
    }

    /** Panggil beberapa detik setelah onResume beranda baru berhasil. */
    public static void markBootOk(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_BOOT_PENDING, false).apply();
    }

    /**
     * Dipanggil MainActivity sebelum mengalihkan ke beranda baru: bila boot sebelumnya tak pernah sukses
     * (crash), matikan tampilan baru dan kembalikan true (pemanggil tetap di tampilan lama).
     */
    public static boolean recoverFromCrashedBoot(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getBoolean(KEY_BOOT_PENDING, false)) return false;
        try {
            new SettingsDao(DatabaseHelper.getInstance(ctx)).setGojekUiPreferred(false);
        } catch (Throwable ignored) {
        }
        p.edit().putBoolean(KEY_BOOT_PENDING, false).putLong(KEY_AUTO_DISABLED, System.currentTimeMillis()).commit();
        return true;
    }
}
