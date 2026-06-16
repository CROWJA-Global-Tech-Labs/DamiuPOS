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
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_EXPENSES, toValues(e));
    }

    public int update(Expense e) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_EXPENSES, toValues(e),
                DatabaseHelper.COL_EXPENSE_ID + "=?",
                new String[]{String.valueOf(e.getId())});
    }

    private static ContentValues toValues(Expense e) {
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_EXPENSE_NAME, e.getName());
        v.put(DatabaseHelper.COL_EXPENSE_AMOUNT, e.getAmount());
        v.put(DatabaseHelper.COL_EXPENSE_PHOTO_PATH, e.getPhotoPath());
        v.put(DatabaseHelper.COL_EXPENSE_NOTE, e.getNote());
        v.put(DatabaseHelper.COL_EXPENSE_CATEGORY, e.getCategory());
        v.put(DatabaseHelper.COL_EXPENSE_LITERS, e.getLiters());
        v.put(DatabaseHelper.COL_EXPENSE_PCS, e.getPcs());
        return v;
    }

    /** Total nominal (Rp) untuk satu kategori, sepanjang waktu. */
    public double getTotalAmountByCategory(String category) {
        return scalar("SELECT COALESCE(SUM(" + DatabaseHelper.COL_EXPENSE_AMOUNT + "),0) FROM "
                + DatabaseHelper.TABLE_EXPENSES + " WHERE " + DatabaseHelper.COL_EXPENSE_CATEGORY
                + "=?", category);
    }

    /** Total liter untuk satu kategori (mis. AIR_BAKU), sepanjang waktu. */
    public double getTotalLitersByCategory(String category) {
        return scalar("SELECT COALESCE(SUM(" + DatabaseHelper.COL_EXPENSE_LITERS + "),0) FROM "
                + DatabaseHelper.TABLE_EXPENSES + " WHERE " + DatabaseHelper.COL_EXPENSE_CATEGORY
                + "=?", category);
    }

    /** Total pcs untuk satu kategori (mis. SEGEL / TUTUP), sepanjang waktu. */
    public double getTotalPcsByCategory(String category) {
        return scalar("SELECT COALESCE(SUM(" + DatabaseHelper.COL_EXPENSE_PCS + "),0) FROM "
                + DatabaseHelper.TABLE_EXPENSES + " WHERE " + DatabaseHelper.COL_EXPENSE_CATEGORY
                + "=?", category);
    }

    /**
     * Harga satuan TERBARU untuk satu kategori: amount ÷ {@code divisorCol} dari
     * entry paling baru (yang {@code divisorCol}&gt;0). Dipakai prediksi laba
     * supaya ikut menyesuaikan kalau harga beli berubah. 0 kalau belum ada data.
     * {@code divisorCol} = COL_EXPENSE_LITERS (air baku, → Rp/liter) atau
     * COL_EXPENSE_PCS (segel/tutup, → Rp/pcs).
     */
    public double getLatestUnitPrice(String category, String divisorCol) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT " + DatabaseHelper.COL_EXPENSE_AMOUNT + ", " + divisorCol
                + " FROM " + DatabaseHelper.TABLE_EXPENSES
                + " WHERE " + DatabaseHelper.COL_EXPENSE_CATEGORY + "=? AND " + divisorCol + ">0"
                + " ORDER BY datetime(" + DatabaseHelper.COL_EXPENSE_CREATED_AT + ") DESC, "
                + DatabaseHelper.COL_EXPENSE_ID + " DESC LIMIT 1", new String[]{category});
        double price = 0;
        if (c.moveToFirst()) {
            double amount = c.getDouble(0);
            double divisor = c.getDouble(1);
            if (divisor > 0) price = amount / divisor;
        }
        c.close();
        return price;
    }

    /** Tanggal ("yyyy-MM-dd") pembelian air baku PALING AWAL (liter>0), null kalau belum ada. */
    public String getEarliestDateByCategory(String category) {
        return earliestDateWhere(category, DatabaseHelper.COL_EXPENSE_LITERS);
    }

    /** Tanggal pembelian PALING AWAL untuk kategori pcs (pcs>0), null kalau belum ada. */
    public String getEarliestDateByCategoryPcs(String category) {
        return earliestDateWhere(category, DatabaseHelper.COL_EXPENSE_PCS);
    }

    private String earliestDateWhere(String category, String positiveCol) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT MIN(date(" + DatabaseHelper.COL_EXPENSE_CREATED_AT + ")) FROM "
                + DatabaseHelper.TABLE_EXPENSES + " WHERE " + DatabaseHelper.COL_EXPENSE_CATEGORY
                + "=? AND " + positiveCol + ">0", new String[]{category});
        String d = null;
        if (c.moveToFirst()) d = c.getString(0);
        c.close();
        return d;
    }

    private double scalar(String sql, String arg) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(sql, new String[]{arg});
        double v = 0;
        if (c.moveToFirst()) v = c.getDouble(0);
        c.close();
        return v;
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_EXPENSES, "expenses",
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
        int catIdx = c.getColumnIndex(DatabaseHelper.COL_EXPENSE_CATEGORY);
        if (catIdx >= 0 && !c.isNull(catIdx)) e.setCategory(c.getString(catIdx));
        int litIdx = c.getColumnIndex(DatabaseHelper.COL_EXPENSE_LITERS);
        if (litIdx >= 0) e.setLiters(c.getDouble(litIdx));
        int pcsIdx = c.getColumnIndex(DatabaseHelper.COL_EXPENSE_PCS);
        if (pcsIdx >= 0) e.setPcs(c.getInt(pcsIdx));
        e.setCreatedAt(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_EXPENSE_CREATED_AT)));
        return e;
    }
}
