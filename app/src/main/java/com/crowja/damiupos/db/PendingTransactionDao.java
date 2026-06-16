package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.PendingTransaction;

import java.util.ArrayList;
import java.util.List;

/** Akses tabel {@code pending_transactions} ("Transaksi Pending"). */
public class PendingTransactionDao {

    private final DatabaseHelper dbHelper;

    public PendingTransactionDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(PendingTransaction p) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_PT_CUSTOMER_ID, p.getCustomerId());
        v.put(DatabaseHelper.COL_PT_CUSTOMER_NAME, p.getCustomerName());
        v.put(DatabaseHelper.COL_PT_CUSTOMER_PHONE, p.getCustomerPhone());
        v.put(DatabaseHelper.COL_PT_NOTE, p.getNote());
        v.put(DatabaseHelper.COL_PT_CREATED_BY, p.getCreatedBy());
        v.put(DatabaseHelper.COL_PT_CREATED_BY_NAME, p.getCreatedByName());
        v.put(DatabaseHelper.COL_PT_STATUS, p.getStatus());
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_PENDING_TRX, v);
    }

    /** Jumlah pending yang masih aktif (status PENDING). */
    public int countPending() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_PENDING_TRX
                + " WHERE " + DatabaseHelper.COL_PT_STATUS + "=?",
                new String[]{String.valueOf(PendingTransaction.STATUS_PENDING)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** Semua pending aktif, terbaru di atas. */
    public List<PendingTransaction> getAllPending() {
        List<PendingTransaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_PENDING_TRX, null,
                DatabaseHelper.COL_PT_STATUS + "=?",
                new String[]{String.valueOf(PendingTransaction.STATUS_PENDING)},
                null, null,
                "datetime(" + DatabaseHelper.COL_PT_CREATED_AT + ") DESC, "
                        + DatabaseHelper.COL_PT_ID + " DESC");
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    public PendingTransaction getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_PENDING_TRX, null,
                DatabaseHelper.COL_PT_ID + "=?", new String[]{String.valueOf(id)},
                null, null, null);
        PendingTransaction p = null;
        if (c.moveToFirst()) p = fromCursor(c);
        c.close();
        return p;
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_PENDING_TRX, "pending_transactions",
                DatabaseHelper.COL_PT_ID + "=?", new String[]{String.valueOf(id)});
    }

    private static PendingTransaction fromCursor(Cursor c) {
        PendingTransaction p = new PendingTransaction();
        p.setId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_ID)));
        p.setCustomerId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_CUSTOMER_ID)));
        p.setCustomerName(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_CUSTOMER_NAME)));
        p.setCustomerPhone(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_CUSTOMER_PHONE)));
        p.setNote(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_NOTE)));
        p.setCreatedBy(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_CREATED_BY)));
        p.setCreatedByName(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_CREATED_BY_NAME)));
        p.setStatus(c.getInt(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_STATUS)));
        p.setCreatedAt(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_PT_CREATED_AT)));
        return p;
    }
}
