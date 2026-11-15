package com.crowja.damiupos.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.User;

/**
 * Gerbang "Tampilan Tema v2". Berlaku untuk setiap sesi login (lihat {@link #isEligibleSession}); peran
 * Pengisian & perangkat Guided Delivery tetap di berandanya sendiri.
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

    /**
     * Sesi yang mendapat "Tampilan Tema v2": multi-user aktif, ada yang login, pengguna aktif — peran APA
     * PUN, kecuali yang sudah punya beranda sendiri: peran Pengisian (PengisianActivity) dan perangkat
     * Guided Delivery (DeliveryQueueActivity). Single-user tanpa login tetap tampilan lama.
     */
    public static boolean isEligibleSession(Context ctx) {
        try {
            SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(ctx));
            if (!sd.isMultiUserEnabled() || sd.getCurrentUserId() <= 0) return false;
            User u = UserDao.currentUser(ctx);
            if (u == null || !u.isActive() || u.usesPengisianHome()) return false;
            return !new com.crowja.damiupos.sync.SyncSettings(sd).isGuidedDeliveryDevice();
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

    // ------------------------------------------------------------------ cache isEnabled
    // isEnabled dievaluasi di SETIAP pembuatan activity (overlay di DamiuApplication) = ±4 baca SQLite
    // di main thread. Hasilnya disimpan sebentar (TTL) dan dibuang eksplisit lewat invalidate() saat
    // sesi login, flag multi-user, atau preferensi berubah. Perubahan yang tak lewat setter tsb
    // (peran user ditarik sync, nilai multi-user dari pull) paling lambat terlihat setelah TTL.

    private static final long CACHE_TTL_NANOS = 5_000_000_000L; // 5 detik

    private static final class Snapshot {
        final boolean enabled;
        final long atNanos;
        final long gen;

        Snapshot(boolean enabled, long atNanos, long gen) {
            this.enabled = enabled;
            this.atNanos = atNanos;
            this.gen = gen;
        }
    }

    private static volatile Snapshot cache;
    private static final java.util.concurrent.atomic.AtomicLong GEN = new java.util.concurrent.atomic.AtomicLong();

    /** Buang hasil isEnabled yang tersimpan (login/logout, multi-user, preferensi berubah). */
    public static void invalidate() {
        GEN.incrementAndGet();
        cache = null;
    }

    /** Tampilan Tema v2 berlaku untuk sesi ini? (sesi layak + preferensi aktif). Di-cache ±5 dtk. */
    public static boolean isEnabled(Context ctx) {
        long now = System.nanoTime();
        Snapshot s = cache;
        if (s != null && s.gen == GEN.get() && now - s.atNanos < CACHE_TTL_NANOS) return s.enabled;
        long gen = GEN.get();
        boolean on = isEligibleSession(ctx) && isPreferred(ctx);
        // Kalau invalidate() terjadi selagi menghitung, gen-nya sudah beda → snapshot ini tak dipakai.
        cache = new Snapshot(on, now, gen);
        return on;
    }

    /**
     * Overlay tema Gojek untuk layar LAMA. Dipanggil dari DamiuApplication.onActivityCreated, yang
     * berjalan DI DALAM super.onCreate() — sebelum setContentView, jadi seluruh view & dekor ikut.
     */
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
