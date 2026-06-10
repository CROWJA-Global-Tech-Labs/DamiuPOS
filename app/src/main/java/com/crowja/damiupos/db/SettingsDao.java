package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.BuildConfig;

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

    public static final String PARSE_MODE_DEFAULT = "default"; // regex saja
    public static final String PARSE_MODE_AI = "ai";           // AI saja
    public static final String PARSE_MODE_HYBRID = "hybrid";   // regex dulu, AI fallback

    private final DatabaseHelper dbHelper;

    public SettingsDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public void set(String key, String value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_SETTING_KEY, key);
        values.put(DatabaseHelper.COL_SETTING_VALUE, value);
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, values,
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

    /** Template pesan follow-up. Lihat {@link #KEY_FOLLOWUP_TEMPLATE} untuk placeholder. */
    public String getFollowUpTemplate() {
        String v = get(KEY_FOLLOWUP_TEMPLATE, DEFAULT_FOLLOWUP_TEMPLATE);
        return v != null && !v.isEmpty() ? v : DEFAULT_FOLLOWUP_TEMPLATE;
    }
    public void setFollowUpTemplate(String s) {
        set(KEY_FOLLOWUP_TEMPLATE, s != null ? s.trim() : "");
    }

}
