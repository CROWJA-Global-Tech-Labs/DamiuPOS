package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * Override komisi per (reseller, jenis air minum). Tanpa row override,
 * perhitungan memakai rate global dari {@link SettingsDao#getResellerKomisi()}.
 */
public class ResellerRateDao {

    private final DatabaseHelper dbHelper;

    public ResellerRateDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** Map productId → komisi per galon untuk reseller ini. */
    public Map<Long, Double> getRates(long customerId) {
        Map<Long, Double> map = new HashMap<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_RESELLER_RATES,
                new String[]{DatabaseHelper.COL_RR_PRODUCT_ID, DatabaseHelper.COL_RR_KOMISI},
                DatabaseHelper.COL_RR_CUSTOMER_ID + "=?",
                new String[]{String.valueOf(customerId)},
                null, null, null);
        while (c.moveToNext()) {
            map.put(c.getLong(0), c.getDouble(1));
        }
        c.close();
        return map;
    }

    /** Upsert rate untuk (reseller, product). */
    public void setRate(long customerId, long productId, double komisi) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_RR_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_RR_PRODUCT_ID, productId);
        v.put(DatabaseHelper.COL_RR_KOMISI, komisi);
        db.insertWithOnConflict(DatabaseHelper.TABLE_RESELLER_RATES, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Hapus override → kembali pakai rate global. */
    public void deleteRate(long customerId, long productId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(DatabaseHelper.TABLE_RESELLER_RATES,
                DatabaseHelper.COL_RR_CUSTOMER_ID + "=? AND " +
                        DatabaseHelper.COL_RR_PRODUCT_ID + "=?",
                new String[]{String.valueOf(customerId), String.valueOf(productId)});
    }
}
