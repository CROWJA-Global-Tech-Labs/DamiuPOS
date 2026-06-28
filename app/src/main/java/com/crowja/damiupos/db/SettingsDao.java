package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SettingsDao {

    // Developer bypass: when true, app behaves as if Pro is always active.
    // Default mengikuti BuildConfig.BYPASS_PRO (lihat app/build.gradle).
    // Bisa juga di-override saat runtime via setBypassPro() kalau perlu.
    private static volatile boolean BYPASS_PRO = BuildConfig.BYPASS_PRO;

    public static final String KEY_DEFAULT_ONGKIR = "default_ongkir";
    public static final String KEY_POINTS_ENABLED = "points_enabled";
    public static final String KEY_POINTS_PER_AMOUNT = "points_per_amount";
    public static final String KEY_POINTS_REWARD_THRESHOLD = "points_reward_threshold";
    public static final String KEY_DEPOT_NAME = "depot_name";
    public static final String KEY_DEPOT_ADDRESS = "depot_address";
    public static final String KEY_DEPOT_PHONE = "depot_phone";
    /** URL logo depot (di-set di web Konfigurasi, disinkron lewat app_settings). Diunduh & di-cache
     *  lokal lalu ditampilkan di Pengaturan + struk. */
    public static final String KEY_DEPOT_LOGO_URL = "depot_logo_url";
    public static final String KEY_WIZARD_COMPLETED = "wizard_completed";
    public static final String KEY_FOLLOWUP_DAYS = "followup_days";
    public static final String KEY_STOCK_ALERT = "stock_alert";
    public static final String KEY_HARGA_BOTOL_GALON = "harga_botol_galon";
    public static final String KEY_LAST_GALON_OWNERSHIP = "last_galon_ownership";
    public static final String KEY_PRO_ACTIVE = "pro_active";
    public static final String KEY_PRO_PURCHASE_TOKEN = "pro_purchase_token";
    public static final String KEY_PRO_LAST_VERIFIED_AT = "pro_last_verified_at";
    public static final String KEY_PRO_PRODUCT_ID = "pro_product_id";
    public static final String KEY_PRO_EXPIRY_AT = "pro_expiry_at";
    /** Pro temp dari rewarded ad — millis since epoch. Pro aktif kalau > now. */
    public static final String KEY_PRO_TEMP_UNTIL = "pro_temp_until";
    /** Last rewarded ad watched — untuk enforce cooldown (REWARD_COOLDOWN_MS). */
    public static final String KEY_LAST_REWARD_AT = "last_reward_at";
    public static final String KEY_WA_AUTO_DETECT = "wa_auto_detect";
    public static final String KEY_WA_PARSE_MODE = "wa_parse_mode";   // default | ai | hybrid
    public static final String KEY_CLAUDE_API_KEY = "claude_api_key";
    public static final String KEY_WA_RINGTONE_URI = "wa_ringtone_uri";
    /** Last Claude call error message (untuk diagnose di UI Settings). Empty = success. */
    public static final String KEY_LAST_CLAUDE_ERROR = "last_claude_error";
    public static final String KEY_LAST_CLAUDE_AT = "last_claude_at";
    public static final String KEY_WA_REPLY_TEMPLATE = "wa_reply_template";
    public static final String DEFAULT_WA_REPLY_TEMPLATE = "Baik, siap kak";
    /** Komisi reseller per galon (Rupiah). Default 1000. */
    public static final String KEY_RESELLER_KOMISI = "reseller_komisi_per_galon";
    public static final double DEFAULT_RESELLER_KOMISI = 1000;
    /** Template pesan follow-up WhatsApp. Placeholder yang didukung:
     *  {nama} {hari} {tanggal} {hari_lalu} — di-replace saat kirim. */
    public static final String KEY_FOLLOWUP_TEMPLATE = "followup_template";
    public static final String DEFAULT_FOLLOWUP_TEMPLATE =
            "Bismillah, menginformasikan kakak terakhir membeli air minum di "
            + "{hari}, {tanggal} (sudah {hari_lalu} hari). "
            + "Mau saya kirim lagi hari ini kak? Terima kasih";
    /** Waktu (jam) sebelum item APPROVED/REJECTED di-arsipkan otomatis.
     *  Default 24 jam; 0 = matikan auto-archive. */
    public static final String KEY_WA_AUTO_ARCHIVE_HOURS = "wa_auto_archive_hours";
    public static final int DEFAULT_WA_AUTO_ARCHIVE_HOURS = 24;
    /** Id pesanan terakhir yang sudah "diakui" user (ditap) — sound stop kalau
     *  latest pending id <= ini. Reset ke 0 = belum ada yg diakui. */
    public static final String KEY_WA_LAST_ALERT_ID = "wa_last_alert_id";

    /** 1 = fitur multi user & absensi aktif (wajib login PIN + clock in). */
    public static final String KEY_MULTIUSER_ENABLED = "multiuser_enabled";
    /** Jam kerja normal per HARI (ambang lembur harian & basis normalisasi
     *  bulanan: jam wajib = hari kerja × ini). Default 7. */
    public static final String KEY_DAILY_NORMAL_HOURS = "daily_normal_hours";
    public static final double DEFAULT_DAILY_NORMAL_HOURS = 7;
    /** Jumlah hari kerja per pekan (1–7). Basis jam kerja ideal periode:
     *  hari ideal periode = hari kerja/pekan × (jumlah hari periode ÷ 7). */
    public static final String KEY_WORK_DAYS_PER_WEEK = "work_days_per_week";
    public static final int DEFAULT_WORK_DAYS_PER_WEEK = 6;
    /** Asumsi liter per galon untuk Analisa & Prediksi (default 19). */
    public static final String KEY_LITERS_PER_GALON = "liters_per_galon";
    public static final double DEFAULT_LITERS_PER_GALON = 19;
    /** Bonus penjualan per galon untuk staf (Rp). Aktif/nonaktif + nominal. */
    public static final String KEY_SALES_BONUS_ENABLED = "sales_bonus_enabled";
    public static final String KEY_SALES_BONUS_PER_GALON = "sales_bonus_per_galon";
    /** Tanggal cut-off rekap absensi bulanan (1..31). Default 28. Periode =
     *  (cutoff+1 bulan lalu) s/d (cutoff bulan ini). */
    public static final String KEY_PAYROLL_CUTOFF_DAY = "payroll_cutoff_day";
    public static final int DEFAULT_PAYROLL_CUTOFF_DAY = 28;
    /** Id user yang sedang login (clock in). 0 = tidak ada (idle/istirahat). */
    public static final String KEY_CURRENT_USER_ID = "current_user_id";
    public static final String KEY_CURRENT_USER_NAME = "current_user_name";
    /** User yang sedang istirahat (shift masih terbuka) — supaya bisa "Lanjut Kerja"
     *  satu ketukan tanpa PIN/selfie. 0 = tidak ada yang istirahat. */
    public static final String KEY_BREAK_USER_ID = "break_user_id";
    public static final String KEY_BREAK_USER_NAME = "break_user_name";
    /** 1 = ada shift terbuka (antara clock in pertama s/d Pulang), termasuk saat
     *  istirahat. Dipakai service polling agar tetap aktif selama shift. */
    public static final String KEY_SHIFT_ACTIVE = "shift_active";

    public static final String PARSE_MODE_DEFAULT = "default"; // regex saja
    public static final String PARSE_MODE_AI = "ai";           // AI saja
    public static final String PARSE_MODE_HYBRID = "hybrid";   // regex dulu, AI fallback

    /**
     * Kunci konfigurasi bisnis BRANCH yang aman disinkronkan lintas perangkat
     * (app_settings). ALLOWLIST — apa pun di luar daftar ini TIDAK PERNAH dikirim
     * ke server. Sengaja EXCLUDE: rahasia (sync_*, claude_api_key, pro_*), state
     * sesi/perangkat (current_user_*, wizard, cursor, last_*), dan toggle
     * device-spesifik (wa_auto_detect, ringtone).
     */
    public static final Set<String> SHAREABLE_KEYS = new HashSet<>(Arrays.asList(
            KEY_DEFAULT_ONGKIR,
            KEY_POINTS_ENABLED, KEY_POINTS_PER_AMOUNT, KEY_POINTS_REWARD_THRESHOLD,
            KEY_DEPOT_NAME, KEY_DEPOT_ADDRESS, KEY_DEPOT_PHONE, KEY_DEPOT_LOGO_URL,
            KEY_FOLLOWUP_DAYS, KEY_FOLLOWUP_TEMPLATE,
            KEY_STOCK_ALERT, KEY_HARGA_BOTOL_GALON, KEY_RESELLER_KOMISI,
            KEY_MULTIUSER_ENABLED,
            KEY_DAILY_NORMAL_HOURS, KEY_WORK_DAYS_PER_WEEK, KEY_LITERS_PER_GALON,
            KEY_SALES_BONUS_ENABLED, KEY_SALES_BONUS_PER_GALON,
            KEY_PAYROLL_CUTOFF_DAY,
            KEY_WA_REPLY_TEMPLATE, KEY_WA_AUTO_ARCHIVE_HOURS, KEY_WA_PARSE_MODE
    ));

    private final DatabaseHelper dbHelper;

    public SettingsDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public void set(String key, String value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_SETTING_KEY, key);
        values.put(DatabaseHelper.COL_SETTING_VALUE, value);
        // Only whitelisted branch-config keys become dirty for app_settings sync;
        // everything else stays synced=1 (clean) so device-local/secret keys are
        // never pushed to the cloud.
        if (SHAREABLE_KEYS.contains(key)) {
            values.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
            values.put(DatabaseHelper.COL_SYNCED, 0);
        } else {
            values.put(DatabaseHelper.COL_SYNCED, 1);
        }
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------- app_settings sync helpers

    /** Dirty whitelisted settings to push: each = {key, value, edited_at}. */
    public List<String[]> getDirtyShared() {
        List<String[]> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SETTINGS,
                new String[]{DatabaseHelper.COL_SETTING_KEY, DatabaseHelper.COL_SETTING_VALUE,
                        DatabaseHelper.COL_EDITED_AT},
                DatabaseHelper.COL_SYNCED + "=0", null, null, null, null);
        while (c.moveToNext()) {
            String key = c.getString(0);
            if (!SHAREABLE_KEYS.contains(key)) continue;   // defense in depth
            out.add(new String[]{key, c.getString(1), c.getString(2)});
        }
        c.close();
        return out;
    }

    /** All whitelisted (shareable) settings as key→value — uploaded for archiving on enroll. */
    public java.util.Map<String, String> getAllShared() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SETTINGS,
                new String[]{DatabaseHelper.COL_SETTING_KEY, DatabaseHelper.COL_SETTING_VALUE},
                null, null, null, null, null);
        while (c.moveToNext()) {
            String key = c.getString(0);
            if (SHAREABLE_KEYS.contains(key)) out.put(key, c.getString(1));
        }
        c.close();
        return out;
    }

    /** Mark the given setting keys as pushed (synced=1). */
    public void markSharedSynced(List<String> keys) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SYNCED, 1);
        for (String k : keys) {
            db.update(DatabaseHelper.TABLE_SETTINGS, v,
                    DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{k});
        }
    }

    /**
     * Apply a setting received from sync. Only whitelisted keys are accepted.
     * Last-write-wins: skip if a newer un-pushed local edit exists.
     */
    public void applySyncedSetting(String key, String value, String editedAt, boolean deleted) {
        if (key == null || !SHAREABLE_KEYS.contains(key)) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean exists = false; int localSynced = 1; String localEdited = "";
        Cursor c = db.query(DatabaseHelper.TABLE_SETTINGS,
                new String[]{DatabaseHelper.COL_SYNCED, DatabaseHelper.COL_EDITED_AT},
                DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{key}, null, null, null);
        if (c.moveToFirst()) { exists = true; localSynced = c.getInt(0); localEdited = c.getString(1); }
        c.close();
        if (exists && localSynced == 0 && localEdited != null && editedAt != null
                && localEdited.compareTo(editedAt) > 0) {
            return;   // keep newer un-pushed local edit
        }
        if (deleted) {
            db.delete(DatabaseHelper.TABLE_SETTINGS,
                    DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{key});
            return;
        }
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SETTING_KEY, key);
        v.put(DatabaseHelper.COL_SETTING_VALUE, value);
        v.put(DatabaseHelper.COL_EDITED_AT, editedAt);
        v.put(DatabaseHelper.COL_SYNCED, 1);
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String get(String key, String defaultValue) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_SETTINGS, new String[]{DatabaseHelper.COL_SETTING_VALUE},
                DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{key},
                null, null, null);
        String result = defaultValue;
        if (cursor.moveToFirst()) {
            result = cursor.getString(0);
        }
        cursor.close();
        return result;
    }

    // -- Pesan admin tertunda --
    // Pesan dari dashboard ("Kirim Pesan ke Perangkat") yang belum sempat ditampilkan
    // sebagai popup karena app sedang di background. Disimpan lokal (bukan SHAREABLE_KEYS,
    // jadi tidak ikut sync) lalu ditampilkan saat MainActivity berikutnya tampil.
    private static final String K_PENDING_MSG_TITLE = "pending_admin_msg_title";
    private static final String K_PENDING_MSG_BODY  = "pending_admin_msg_body";

    public void setPendingAdminMessage(String title, String body) {
        set(K_PENDING_MSG_TITLE, title != null ? title : "");
        set(K_PENDING_MSG_BODY, body != null ? body : "");
    }
    public String getPendingAdminMessageTitle() { return get(K_PENDING_MSG_TITLE, ""); }
    public String getPendingAdminMessageBody()  { return get(K_PENDING_MSG_BODY, ""); }
    /** Ada pesan admin yang belum ditampilkan sebagai popup? */
    public boolean hasPendingAdminMessage() {
        String t = getPendingAdminMessageTitle();
        return t != null && !t.isEmpty();
    }
    public void clearPendingAdminMessage() {
        set(K_PENDING_MSG_TITLE, "");
        set(K_PENDING_MSG_BODY, "");
    }

    public double getDefaultOngkir() {
        String val = get(KEY_DEFAULT_ONGKIR, "0");
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setDefaultOngkir(double value) {
        set(KEY_DEFAULT_ONGKIR, String.valueOf(value));
    }

    public boolean isPointsEnabled() {
        return "1".equals(get(KEY_POINTS_ENABLED, "0"));
    }

    public void setPointsEnabled(boolean enabled) {
        set(KEY_POINTS_ENABLED, enabled ? "1" : "0");
    }

    /** Rupiah amount required per 1 point. Default 10.000 */
    public double getPointsPerAmount() {
        String val = get(KEY_POINTS_PER_AMOUNT, "10000");
        try {
            double d = Double.parseDouble(val);
            return d > 0 ? d : 10000;
        } catch (NumberFormatException e) {
            return 10000;
        }
    }

    public void setPointsPerAmount(double value) {
        set(KEY_POINTS_PER_AMOUNT, String.valueOf(value));
    }

    /** Points needed for a reward. Default 100 */
    public int getPointsRewardThreshold() {
        String val = get(KEY_POINTS_REWARD_THRESHOLD, "100");
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    public void setPointsRewardThreshold(int value) {
        set(KEY_POINTS_REWARD_THRESHOLD, String.valueOf(value));
    }

    public String getDepotName() { return get(KEY_DEPOT_NAME, ""); }
    public void setDepotName(String v) { set(KEY_DEPOT_NAME, v != null ? v : ""); }

    public String getDepotAddress() { return get(KEY_DEPOT_ADDRESS, ""); }
    public void setDepotAddress(String v) { set(KEY_DEPOT_ADDRESS, v != null ? v : ""); }

    public String getDepotPhone() { return get(KEY_DEPOT_PHONE, ""); }
    public void setDepotPhone(String v) { set(KEY_DEPOT_PHONE, v != null ? v : ""); }

    /** URL logo depot (disinkron dari web). Kosong = belum di-set. */
    public String getDepotLogoUrl() { return get(KEY_DEPOT_LOGO_URL, ""); }

    public boolean isWizardCompleted() { return "1".equals(get(KEY_WIZARD_COMPLETED, "0")); }
    public void setWizardCompleted(boolean v) { set(KEY_WIZARD_COMPLETED, v ? "1" : "0"); }

    public int getFollowupDays() {
        try {
            int v = Integer.parseInt(get(KEY_FOLLOWUP_DAYS, "5"));
            return v > 0 ? v : 5;
        } catch (NumberFormatException e) { return 5; }
    }
    public void setFollowupDays(int v) { set(KEY_FOLLOWUP_DAYS, String.valueOf(v > 0 ? v : 5)); }

    public int getStockAlert() {
        try {
            int v = Integer.parseInt(get(KEY_STOCK_ALERT, "30"));
            return v >= 0 ? v : 30;
        } catch (NumberFormatException e) { return 30; }
    }
    public void setStockAlert(int v) { set(KEY_STOCK_ALERT, String.valueOf(v >= 0 ? v : 30)); }

    /** Harga default satu botol galon kosong (untuk ganti rugi). Default 35.000 */
    public double getHargaBotolGalon() {
        String val = get(KEY_HARGA_BOTOL_GALON, "35000");
        try {
            double d = Double.parseDouble(val);
            return d > 0 ? d : 35000;
        } catch (NumberFormatException e) {
            return 35000;
        }
    }

    public void setHargaBotolGalon(double v) {
        set(KEY_HARGA_BOTOL_GALON, String.valueOf(v > 0 ? v : 35000));
    }

    /** Terakhir yang dipilih di form transaksi: PINJAM atau BELI */
    public String getLastGalonOwnership() {
        return get(KEY_LAST_GALON_OWNERSHIP, "PINJAM");
    }

    public void setLastGalonOwnership(String v) {
        set(KEY_LAST_GALON_OWNERSHIP, v != null ? v : "PINJAM");
    }

    public static void setBypassPro(boolean enabled) {
        BYPASS_PRO = enabled;
    }

    public static boolean isBypassProEnabled() {
        return BYPASS_PRO;
    }

    // ---------------- Pro / subscription status (cached locally) ----------------

    public boolean isProActive() {
        return BYPASS_PRO
                || "1".equals(get(KEY_PRO_ACTIVE, "0"))
                || isProTempActive();
    }

    /** Apakah subscriber permanent (bukan temp dari rewarded ad). */
    public boolean isProSubscriber() {
        return BYPASS_PRO || "1".equals(get(KEY_PRO_ACTIVE, "0"));
    }

    /** Apakah Pro temp dari rewarded ad masih berlaku. */
    public boolean isProTempActive() {
        return getProTempUntil() > System.currentTimeMillis();
    }

    /** Pro temp expiry timestamp (millis since epoch). 0 = tidak pernah claim. */
    public long getProTempUntil() {
        try { return Long.parseLong(get(KEY_PRO_TEMP_UNTIL, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }

    public void setProTempUntil(long millis) {
        set(KEY_PRO_TEMP_UNTIL, String.valueOf(millis));
    }

    /** Timestamp terakhir user menonton rewarded ad — untuk cek cooldown. */
    public long getLastRewardAt() {
        try { return Long.parseLong(get(KEY_LAST_REWARD_AT, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }

    public void setLastRewardAt(long millis) {
        set(KEY_LAST_REWARD_AT, String.valueOf(millis));
    }

    /**
     * Bisa user tonton rewarded ad lagi? True kalau cooldown sudah lewat
     * (default {@code BuildConfig.REWARD_COOLDOWN_MS} = 24 jam dari terakhir).
     */
    public boolean canWatchRewardAd() {
        long last = getLastRewardAt();
        if (last == 0) return true;
        return (System.currentTimeMillis() - last) >= BuildConfig.REWARD_COOLDOWN_MS;
    }

    /** Berapa detik lagi sampai user bisa tonton ad lagi. 0 = bisa sekarang. */
    public long secondsUntilNextReward() {
        long last = getLastRewardAt();
        if (last == 0) return 0L;
        long delta = (last + BuildConfig.REWARD_COOLDOWN_MS) - System.currentTimeMillis();
        return Math.max(0L, delta / 1000L);
    }

    public void setProActive(boolean active) {
        set(KEY_PRO_ACTIVE, active ? "1" : "0");
        set(KEY_PRO_LAST_VERIFIED_AT, String.valueOf(System.currentTimeMillis()));
    }

    public String getProPurchaseToken() {
        return get(KEY_PRO_PURCHASE_TOKEN, "");
    }

    public void setProPurchaseToken(String token) {
        set(KEY_PRO_PURCHASE_TOKEN, token != null ? token : "");
    }

    public long getProLastVerifiedAt() {
        try {
            return Long.parseLong(get(KEY_PRO_LAST_VERIFIED_AT, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Product ID dari subscription yang aktif (mis. damiu_pro_monthly / damiu_pro_yearly). */
    public String getProProductId() {
        return get(KEY_PRO_PRODUCT_ID, "");
    }

    public void setProProductId(String productId) {
        set(KEY_PRO_PRODUCT_ID, productId != null ? productId : "");
    }

    /** Estimasi kapan subscription akan habis (millis since epoch). 0 = tidak diketahui. */
    public long getProExpiryAt() {
        try {
            return Long.parseLong(get(KEY_PRO_EXPIRY_AT, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void setProExpiryAt(long millis) {
        set(KEY_PRO_EXPIRY_AT, String.valueOf(millis));
    }

    // ---------------- WhatsApp auto-detect orders ----------------

    public boolean isWaAutoDetectEnabled() {
        return "1".equals(get(KEY_WA_AUTO_DETECT, "0"));
    }

    public void setWaAutoDetectEnabled(boolean enabled) {
        set(KEY_WA_AUTO_DETECT, enabled ? "1" : "0");
    }

    /** {@link #PARSE_MODE_DEFAULT} | {@link #PARSE_MODE_AI} | {@link #PARSE_MODE_HYBRID} */
    public String getWaParseMode() {
        String v = get(KEY_WA_PARSE_MODE, PARSE_MODE_HYBRID);
        if (PARSE_MODE_DEFAULT.equals(v) || PARSE_MODE_AI.equals(v) || PARSE_MODE_HYBRID.equals(v)) {
            return v;
        }
        return PARSE_MODE_HYBRID;
    }

    public void setWaParseMode(String mode) {
        set(KEY_WA_PARSE_MODE, mode != null ? mode : PARSE_MODE_HYBRID);
    }

    public String getClaudeApiKey() {
        return get(KEY_CLAUDE_API_KEY, "");
    }

    public void setClaudeApiKey(String key) {
        set(KEY_CLAUDE_API_KEY, key != null ? key.trim() : "");
    }

    /** Status terakhir panggilan Claude API. Empty = success / belum pernah call. */
    public String getLastClaudeError() { return get(KEY_LAST_CLAUDE_ERROR, ""); }
    public void setLastClaudeError(String err) {
        set(KEY_LAST_CLAUDE_ERROR, err != null ? err : "");
        set(KEY_LAST_CLAUDE_AT, String.valueOf(System.currentTimeMillis()));
    }
    public long getLastClaudeAt() {
        try { return Long.parseLong(get(KEY_LAST_CLAUDE_AT, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }

    /** URI nada dering yang dipilih user untuk notifikasi pesanan WA.
     *  Empty = pakai default sistem. */
    public String getWaRingtoneUri() { return get(KEY_WA_RINGTONE_URI, ""); }
    public void setWaRingtoneUri(String uri) {
        set(KEY_WA_RINGTONE_URI, uri != null ? uri : "");
    }

    public long getWaLastAlertId() {
        try { return Long.parseLong(get(KEY_WA_LAST_ALERT_ID, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
    public void setWaLastAlertId(long id) {
        set(KEY_WA_LAST_ALERT_ID, String.valueOf(id));
    }

    /** Berapa jam APPROVED/REJECTED disimpan sebelum auto-arsip. 0 = off. */
    public int getWaAutoArchiveHours() {
        try {
            int v = Integer.parseInt(get(KEY_WA_AUTO_ARCHIVE_HOURS,
                    String.valueOf(DEFAULT_WA_AUTO_ARCHIVE_HOURS)));
            return Math.max(0, v);
        } catch (NumberFormatException e) { return DEFAULT_WA_AUTO_ARCHIVE_HOURS; }
    }
    public void setWaAutoArchiveHours(int hours) {
        set(KEY_WA_AUTO_ARCHIVE_HOURS, String.valueOf(Math.max(0, hours)));
    }

    /** Template pesan balasan yang dipakai tombol "Balas" di inbox. */
    public String getWaReplyTemplate() {
        String v = get(KEY_WA_REPLY_TEMPLATE, DEFAULT_WA_REPLY_TEMPLATE);
        return v != null && !v.isEmpty() ? v : DEFAULT_WA_REPLY_TEMPLATE;
    }
    public void setWaReplyTemplate(String s) {
        set(KEY_WA_REPLY_TEMPLATE, s != null ? s.trim() : "");
    }

    /** Komisi reseller per galon dalam Rupiah. */
    public double getResellerKomisi() {
        try {
            double v = Double.parseDouble(get(KEY_RESELLER_KOMISI,
                    String.valueOf(DEFAULT_RESELLER_KOMISI)));
            return v >= 0 ? v : DEFAULT_RESELLER_KOMISI;
        } catch (NumberFormatException e) { return DEFAULT_RESELLER_KOMISI; }
    }
    public void setResellerKomisi(double v) {
        set(KEY_RESELLER_KOMISI, String.valueOf(v >= 0 ? v : DEFAULT_RESELLER_KOMISI));
    }

    // ---------------- Multi user & absensi ----------------

    public boolean isMultiUserEnabled() {
        return "1".equals(get(KEY_MULTIUSER_ENABLED, "0"));
    }
    public void setMultiUserEnabled(boolean enabled) {
        set(KEY_MULTIUSER_ENABLED, enabled ? "1" : "0");
    }

    /**
     * Disable multi-user LOCALLY only — write the value clean (synced=1) so it is never
     * pushed back to the dashboard. Used by the anti-lockout failsafe: a single phone that
     * somehow ends up with no usable user must not turn off staff login branch-wide (the key
     * is now dashboard-authoritative via {@link #SHAREABLE_KEYS}). The dashboard value still
     * wins on the next pull.
     */
    public void setMultiUserEnabledLocal(boolean enabled) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SETTING_KEY, KEY_MULTIUSER_ENABLED);
        v.put(DatabaseHelper.COL_SETTING_VALUE, enabled ? "1" : "0");
        v.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
        v.put(DatabaseHelper.COL_SYNCED, 1);
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Jam kerja normal per hari (ambang lembur harian + basis normalisasi bulanan). */
    public double getDailyNormalHours() {
        try {
            double v = Double.parseDouble(get(KEY_DAILY_NORMAL_HOURS,
                    String.valueOf(DEFAULT_DAILY_NORMAL_HOURS)));
            return v > 0 ? v : DEFAULT_DAILY_NORMAL_HOURS;
        } catch (NumberFormatException e) { return DEFAULT_DAILY_NORMAL_HOURS; }
    }
    public void setDailyNormalHours(double v) {
        set(KEY_DAILY_NORMAL_HOURS, String.valueOf(v > 0 ? v : DEFAULT_DAILY_NORMAL_HOURS));
    }

    /** Jumlah hari kerja per pekan (1–7, default 6). */
    public int getWorkDaysPerWeek() {
        try {
            int v = Integer.parseInt(get(KEY_WORK_DAYS_PER_WEEK,
                    String.valueOf(DEFAULT_WORK_DAYS_PER_WEEK)));
            if (v < 1) return 1;
            if (v > 7) return 7;
            return v;
        } catch (NumberFormatException e) { return DEFAULT_WORK_DAYS_PER_WEEK; }
    }
    public void setWorkDaysPerWeek(int v) {
        if (v < 1) v = 1;
        if (v > 7) v = 7;
        set(KEY_WORK_DAYS_PER_WEEK, String.valueOf(v));
    }

    public double getLitersPerGalon() {
        try {
            double v = Double.parseDouble(get(KEY_LITERS_PER_GALON,
                    String.valueOf(DEFAULT_LITERS_PER_GALON)));
            return v > 0 ? v : DEFAULT_LITERS_PER_GALON;
        } catch (NumberFormatException e) { return DEFAULT_LITERS_PER_GALON; }
    }
    public void setLitersPerGalon(double v) {
        set(KEY_LITERS_PER_GALON, String.valueOf(v > 0 ? v : DEFAULT_LITERS_PER_GALON));
    }

    public boolean isSalesBonusEnabled() {
        return "1".equals(get(KEY_SALES_BONUS_ENABLED, "0"));
    }
    public void setSalesBonusEnabled(boolean enabled) {
        set(KEY_SALES_BONUS_ENABLED, enabled ? "1" : "0");
    }
    public double getSalesBonusPerGalon() {
        try {
            double v = Double.parseDouble(get(KEY_SALES_BONUS_PER_GALON, "0"));
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) { return 0; }
    }
    public void setSalesBonusPerGalon(double v) {
        set(KEY_SALES_BONUS_PER_GALON, String.valueOf(v > 0 ? v : 0));
    }

    /** Tanggal cut-off rekap absensi bulanan (1..31). */
    public int getPayrollCutoffDay() {
        try {
            int v = Integer.parseInt(get(KEY_PAYROLL_CUTOFF_DAY,
                    String.valueOf(DEFAULT_PAYROLL_CUTOFF_DAY)));
            return (v >= 1 && v <= 31) ? v : DEFAULT_PAYROLL_CUTOFF_DAY;
        } catch (NumberFormatException e) { return DEFAULT_PAYROLL_CUTOFF_DAY; }
    }
    public void setPayrollCutoffDay(int v) {
        set(KEY_PAYROLL_CUTOFF_DAY, String.valueOf((v >= 1 && v <= 31) ? v : DEFAULT_PAYROLL_CUTOFF_DAY));
    }

    /** Id user yang sedang clock in. 0 = belum login / istirahat / sudah out. */
    public long getCurrentUserId() {
        try { return Long.parseLong(get(KEY_CURRENT_USER_ID, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
    public String getCurrentUserName() { return get(KEY_CURRENT_USER_NAME, ""); }

    public void setCurrentUser(long id, String name) {
        set(KEY_CURRENT_USER_ID, String.valueOf(id));
        set(KEY_CURRENT_USER_NAME, name != null ? name : "");
    }
    public void clearCurrentUser() { setCurrentUser(0, ""); }

    /** User yang sedang istirahat (shift terbuka) → boleh "Lanjut Kerja" tanpa PIN/selfie. 0 = tidak ada. */
    public long getBreakUserId() {
        try { return Long.parseLong(get(KEY_BREAK_USER_ID, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
    public String getBreakUserName() { return get(KEY_BREAK_USER_NAME, ""); }
    public void setBreakUser(long id, String name) {
        set(KEY_BREAK_USER_ID, String.valueOf(id));
        set(KEY_BREAK_USER_NAME, name != null ? name : "");
    }
    public void clearBreakUser() { setBreakUser(0, ""); }

    /** Shift terbuka (clock in pertama s/d Pulang, termasuk istirahat). */
    public boolean isShiftActive() { return "1".equals(get(KEY_SHIFT_ACTIVE, "0")); }
    public void setShiftActive(boolean v) { set(KEY_SHIFT_ACTIVE, v ? "1" : "0"); }

    /** Template pesan follow-up. Lihat {@link #KEY_FOLLOWUP_TEMPLATE} untuk placeholder. */
    public String getFollowUpTemplate() {
        String v = get(KEY_FOLLOWUP_TEMPLATE, DEFAULT_FOLLOWUP_TEMPLATE);
        return v != null && !v.isEmpty() ? v : DEFAULT_FOLLOWUP_TEMPLATE;
    }
    public void setFollowUpTemplate(String s) {
        set(KEY_FOLLOWUP_TEMPLATE, s != null ? s.trim() : "");
    }

}
