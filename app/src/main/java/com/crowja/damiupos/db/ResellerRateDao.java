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

    /** Upsert rate untuk (reseller, product). Update-or-insert (bukan REPLACE) supaya
     *  sync_uuid baris dipertahankan saat diubah. */
    public void setRate(long customerId, long productId, double komisi) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String where = DatabaseHelper.COL_RR_CUSTOMER_ID + "=? AND "
                + DatabaseHelper.COL_RR_PRODUCT_ID + "=?";
        String[] args = {String.valueOf(customerId), String.valueOf(productId)};
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_RR_KOMISI, komisi);
        int updated = dbHelper.syncUpdate(db, DatabaseHelper.TABLE_RESELLER_RATES, v, where, args);
        if (updated == 0) {
            v.put(DatabaseHelper.COL_RR_CUSTOMER_ID, customerId);
            v.put(DatabaseHelper.COL_RR_PRODUCT_ID, productId);
            dbHelper.syncInsert(db, DatabaseHelper.TABLE_RESELLER_RATES, v);
        }
    }

    /** Hapus override → kembali pakai rate global. */
    public void deleteRate(long customerId, long productId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        dbHelper.syncDelete(db, DatabaseHelper.TABLE_RESELLER_RATES, "reseller_rates",
                DatabaseHelper.COL_RR_CUSTOMER_ID + "=? AND " +
                        DatabaseHelper.COL_RR_PRODUCT_ID + "=?",
                new String[]{String.valueOf(customerId), String.valueOf(productId)});
    }
}
