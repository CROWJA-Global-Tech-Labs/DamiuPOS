package com.crowja.damiupos.db;

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
        return addStock(jumlah, catatan, null);
    }

    public long addStock(int jumlah, String catatan, String photoPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_STOCK_JUMLAH, jumlah);
        values.put(DatabaseHelper.COL_STOCK_CATATAN, catatan);
        values.put(DatabaseHelper.COL_STOCK_PHOTO_PATH, photoPath);
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_GALON_STOCK, values);
    }

    public int deleteStock(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_GALON_STOCK, "galon_stock",
                DatabaseHelper.COL_STOCK_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Update entry stok yang sudah ada. Returns rows updated (0 = id tidak ditemukan). */
    public int updateStock(long id, int jumlah, String catatan, String photoPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_STOCK_JUMLAH, jumlah);
        values.put(DatabaseHelper.COL_STOCK_CATATAN, catatan);
        values.put(DatabaseHelper.COL_STOCK_PHOTO_PATH, photoPath);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_GALON_STOCK, values,
                DatabaseHelper.COL_STOCK_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Ambil 1 entry stok by id. Returns {id, jumlah, catatan, tanggal, photo_path}
     * atau null kalau tidak ditemukan.
     */
    public String[] getStockById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT _id, jumlah, catatan, tanggal, photo_path FROM " +
                        DatabaseHelper.TABLE_GALON_STOCK +
                        " WHERE " + DatabaseHelper.COL_STOCK_ID + "=?",
                new String[]{String.valueOf(id)});
        String[] row = null;
        if (cursor.moveToFirst()) {
            row = new String[]{
                    String.valueOf(cursor.getLong(0)),
                    String.valueOf(cursor.getInt(1)),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4)
            };
        }
        cursor.close();
        return row;
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

    /** Stok galon tersedia = stok_masuk - galon_keluar + galon_kembali (semua LOKAL). */
    public int getStokTersedia() {
        return getTotalStokMasuk() - getTotalGalonKeluar() + getTotalGalonKembali();
    }

    /**
     * Stok tersedia "resmi" — SATU sumber kebenaran yang dipakai badge dashboard maupun layar
     * Stok Galon, supaya keduanya selalu sama. Memakai galon keluar/kembali OTORITATIF dari server
     * (branch-wide) bila perangkat ter-enroll & sudah sync; per-device isolation membuat transaksi
     * lokal bisa tertinggal sehingga {@link #getStokTersedia()} (murni lokal) berbeda dari dashboard.
     * Stok masuk tetap dari galon_stock lokal (sudah branch-wide, dan langsung mencerminkan koreksi
     * yang baru dibuat di HP ini).
     */
    public int getStokTersediaResmi(com.crowja.damiupos.sync.SyncSettings sync) {
        boolean useServer = sync != null && sync.isEnrolled() && sync.hasServerStok();
        int keluar  = useServer ? sync.getStokKeluar()  : getTotalGalonKeluar();
        int kembali = useServer ? sync.getStokKembali() : getTotalGalonKembali();
        return getTotalStokMasuk() - keluar + kembali;
    }

    /** Get stock addition history, newest first */
    public List<String[]> getStockHistory() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT _id, jumlah, catatan, tanggal, photo_path FROM " +
                        DatabaseHelper.TABLE_GALON_STOCK +
                        " ORDER BY tanggal DESC", null);
        while (cursor.moveToNext()) {
            list.add(new String[]{
                    String.valueOf(cursor.getLong(0)),  // id
                    String.valueOf(cursor.getInt(1)),    // jumlah
                    cursor.getString(2),                 // catatan
                    cursor.getString(3),                 // tanggal
                    cursor.getString(4)                  // photo_path
            });
        }
        cursor.close();
        return list;
    }
}
