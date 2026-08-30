package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.TransactionItem;

/**
 * "Bonus Beli N Gratis 1" — buku besar LOKAL (cermin App\Support\ProductBonus di web, tapi dihitung
 * dari riwayat transaksi PERANGKAT INI saja — sama filosofi dengan sistem poin yang sudah ada,
 * {@see TransactionDao#getTotalJualPointsBasisByCustomer}).
 */
public class ProductBonusDao {

    private final DatabaseHelper dbHelper;

    public ProductBonusDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(long customerId, String productName, int qty, long trxId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_PB_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_PB_PRODUCT_NAME, productName);
        v.put(DatabaseHelper.COL_PB_QTY, qty);
        v.put(DatabaseHelper.COL_PB_TRX_ID, trxId);
        return db.insert(DatabaseHelper.TABLE_PRODUCT_BONUS, null, v);
    }

    /** Unit gratis yang SUDAH diberikan (lokal, perangkat ini) untuk pelanggan+jenis produk ini. */
    public int alreadyGranted(long customerId, String productName) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(" + DatabaseHelper.COL_PB_QTY + "),0) FROM "
                        + DatabaseHelper.TABLE_PRODUCT_BONUS
                        + " WHERE " + DatabaseHelper.COL_PB_CUSTOMER_ID + "=? AND "
                        + DatabaseHelper.COL_PB_PRODUCT_NAME + "=?",
                new String[]{String.valueOf(customerId), productName});
        int total = 0;
        if (c.moveToFirst()) total = c.getInt(0);
        c.close();
        return total;
    }

    /** Total galon TERBAYAR (price &gt; 0) seumur pelanggan (transaksi PERANGKAT INI saja) untuk
     *  sebuah nama produk — cermin App\Support\ProductBonus::lifetimePaidQty di web. */
    public int lifetimePaidQty(long customerId, String productName) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT " + DatabaseHelper.COL_ITEMS_JSON + " FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                        + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + "=? AND " + DatabaseHelper.COL_TYPE + "=?",
                new String[]{String.valueOf(customerId), "JUAL"});
        int total = 0;
        while (c.moveToNext()) {
            String json = c.getString(0);
            if (json == null || json.isEmpty()) continue;
            for (TransactionItem it : TransactionItem.listFromJson(json)) {
                if (it.hargaPerGalon > 0 && productName.equals(it.productName)) {
                    total += it.jumlah;
                }
            }
        }
        c.close();
        return total;
    }
}
