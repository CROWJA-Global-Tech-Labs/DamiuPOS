package com.damiu.pos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class SettingsDao {

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
}
