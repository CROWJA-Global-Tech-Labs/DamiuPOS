package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Expense;

import java.util.ArrayList;
import java.util.List;

public class ExpenseDao {

    private final DatabaseHelper dbHelper;

    public ExpenseDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(Expense e) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_EXPENSE_NAME, e.getName());
        v.put(DatabaseHelper.COL_EXPENSE_AMOUNT, e.getAmount());
        v.put(DatabaseHelper.COL_EXPENSE_PHOTO_PATH, e.getPhotoPath());
        v.put(DatabaseHelper.COL_EXPENSE_NOTE, e.getNote());
        return db.insert(DatabaseHelper.TABLE_EXPENSES, null, v);
    }

    public int update(Expense e) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_EXPENSE_NAME, e.getName());
        v.put(DatabaseHelper.COL_EXPENSE_AMOUNT, e.getAmount());
        v.put(DatabaseHelper.COL_EXPENSE_PHOTO_PATH, e.getPhotoPath());
        v.put(DatabaseHelper.COL_EXPENSE_NOTE, e.getNote());
        return db.update(DatabaseHelper.TABLE_EXPENSES, v,
                DatabaseHelper.COL_EXPENSE_ID + "=?",
                new String[]{String.valueOf(e.getId())});
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(DatabaseHelper.TABLE_EXPENSES,
                DatabaseHelper.COL_EXPENSE_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Get one by id; returns null if not found. */
    public Expense getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_EXPENSES, null,
                DatabaseHelper.COL_EXPENSE_ID + "=?",
                new String[]{String.valueOf(id)},
                null, null, null);
        Expense out = null;
        if (c.moveToFirst()) out = fromCursor(c);
        c.close();
        return out;
    }

    /** All expenses, newest first. */
    public List<Expense> getAll() {
        return query(null, null);
    }

    /** Search by name (case-insensitive contains). */
    public List<Expense> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return getAll();
        return query(DatabaseHelper.COL_EXPENSE_NAME + " LIKE ?",
                new String[]{"%" + keyword.trim() + "%"});
    }

    /** All expenses within a date range (inclusive), newest first. Used by reports. */
    public List<Expense> getByDateRange(String startDate, String endDate) {
        return query("date(" + DatabaseHelper.COL_EXPENSE_CREATED_AT + ") BETWEEN date(?) AND date(?)",
                new String[]{startDate, endDate});
    }

    /** Total pengeluaran hari ini (untuk dashboard). */
    public double getTotalToday() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String q = "SELECT COALESCE(SUM(" + DatabaseHelper.COL_EXPENSE_AMOUNT + "),0) " +
                "FROM " + DatabaseHelper.TABLE_EXPENSES + " " +
                "WHERE date(" + DatabaseHelper.COL_EXPENSE_CREATED_AT + ") = date('now','localtime')";
        Cursor c = db.rawQuery(q, null);
        double total = 0;
        if (c.moveToFirst()) total = c.getDouble(0);
        c.close();
        return total;
    }

    /** Total pengeluaran bulan kalender saat ini. */
    public double getTotalThisMonth() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String q = "SELECT COALESCE(SUM(" + DatabaseHelper.COL_EXPENSE_AMOUNT + "),0) " +
                "FROM " + DatabaseHelper.TABLE_EXPENSES + " " +
                "WHERE strftime('%Y-%m', " + DatabaseHelper.COL_EXPENSE_CREATED_AT + ") " +
                "    = strftime('%Y-%m','now','localtime')";
        Cursor c = db.rawQuery(q, null);
        double total = 0;
        if (c.moveToFirst()) total = c.getDouble(0);
        c.close();
        return total;
    }

    private List<Expense> query(String where, String[] args) {
        List<Expense> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_EXPENSES, null,
                where, args, null, null,
                DatabaseHelper.COL_EXPENSE_CREATED_AT + " DESC");
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    private static Expense fromCursor(Cursor c) {
        Expense e = new Expense();
        e.setId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_EXPENSE_ID)));
        e.setName(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_EXPENSE_NAME)));
        e.setAmount(c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COL_EXPENSE_AMOUNT)));
        int photoIdx = c.getColumnIndex(DatabaseHelper.COL_EXPENSE_PHOTO_PATH);
        if (photoIdx >= 0 && !c.isNull(photoIdx)) e.setPhotoPath(c.getString(photoIdx));
        int noteIdx = c.getColumnIndex(DatabaseHelper.COL_EXPENSE_NOTE);
        if (noteIdx >= 0 && !c.isNull(noteIdx)) e.setNote(c.getString(noteIdx));
        e.setCreatedAt(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_EXPENSE_CREATED_AT)));
        return e;
    }
}
