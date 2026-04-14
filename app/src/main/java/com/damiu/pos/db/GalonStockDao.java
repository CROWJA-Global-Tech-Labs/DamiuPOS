package com.damiu.pos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

public class GalonStockDao {

    private final DatabaseHelper dbHelper;

    public GalonStockDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long addStock(int jumlah, String catatan) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_STOCK_JUMLAH, jumlah);
        values.put(DatabaseHelper.COL_STOCK_CATATAN, catatan);
        return db.insert(DatabaseHelper.TABLE_GALON_STOCK, null, values);
    }

    public int deleteStock(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(DatabaseHelper.TABLE_GALON_STOCK,
                DatabaseHelper.COL_STOCK_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Total galon yang ditambahkan ke stok */
    public int getTotalStokMasuk() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(" + DatabaseHelper.COL_STOCK_JUMLAH + "),0) FROM " +
                        DatabaseHelper.TABLE_GALON_STOCK, null);
        int total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0);
        }
        cursor.close();
        return total;
    }

    /** Total galon keluar (terjual) */
    public int getTotalGalonKeluar() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(jumlah_galon),0) FROM " +
                        DatabaseHelper.TABLE_TRANSACTIONS +
                        " WHERE type='JUAL'", null);
        int total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0);
        }
        cursor.close();
        return total;
    }

    /** Total galon kembali dari pelanggan */
    public int getTotalGalonKembali() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(jumlah_galon),0) FROM " +
                        DatabaseHelper.TABLE_TRANSACTIONS +
                        " WHERE type='KEMBALI'", null);
        int total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0);
        }
        cursor.close();
        return total;
    }

    /** Stok galon tersedia = stok_masuk - galon_keluar + galon_kembali */
    public int getStokTersedia() {
        return getTotalStokMasuk() - getTotalGalonKeluar() + getTotalGalonKembali();
    }

    /** Get stock addition history, newest first */
    public List<String[]> getStockHistory() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT _id, jumlah, catatan, tanggal FROM " +
                        DatabaseHelper.TABLE_GALON_STOCK +
                        " ORDER BY tanggal DESC", null);
        while (cursor.moveToNext()) {
            list.add(new String[]{
                    String.valueOf(cursor.getLong(0)),  // id
                    String.valueOf(cursor.getInt(1)),    // jumlah
                    cursor.getString(2),                 // catatan
                    cursor.getString(3)                  // tanggal
            });
        }
        cursor.close();
        return list;
    }
}
