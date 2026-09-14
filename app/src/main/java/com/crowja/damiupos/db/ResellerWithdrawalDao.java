package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Pencairan komisi reseller. Tiap row = satu pencairan, berupa:
 * <ul>
 *   <li>{@code AIR}  — reseller ambil air minum; {@code galon_qty} galon,
 *       {@code amount} = nilai rupiah yang dipotong dari saldo komisi.</li>
 *   <li>{@code UANG} — reseller ambil uang tunai; {@code amount} rupiah.</li>
 * </ul>
 * Saldo komisi = (komisi_galon × rate) − SUM(amount semua pencairan).
 */
public class ResellerWithdrawalDao {

    public static final String TYPE_AIR = "AIR";
    public static final String TYPE_UANG = "UANG";

    /** Row hasil query: {id, type, galonQty, amount, note, createdAt, expenseId}. */
    public static class Withdrawal {
        public long id;
        public String type;
        public int galonQty;
        public double amount;
        public String note;
        public String createdAt;
        public long expenseId; // expense terkait (0 = tidak ada)
    }

    private final DatabaseHelper dbHelper;

    public ResellerWithdrawalDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(long customerId, String type, int galonQty,
                       double amount, String note) {
        return insert(customerId, type, galonQty, amount, note, 0);
    }

    /** Insert pencairan + link ke expense (pengeluaran) terkait. */
    public long insert(long customerId, String type, int galonQty,
                       double amount, String note, long expenseId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_WD_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_WD_TYPE, type);
        v.put(DatabaseHelper.COL_WD_GALON_QTY, galonQty);
        v.put(DatabaseHelper.COL_WD_AMOUNT, amount);
        if (note != null && !note.trim().isEmpty()) {
            v.put(DatabaseHelper.COL_WD_NOTE, note.trim());
        }
        v.put(DatabaseHelper.COL_WD_EXPENSE_ID, expenseId);
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_RESELLER_WD, v);
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_RESELLER_WD, "reseller_withdrawals",
                DatabaseHelper.COL_WD_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Net rupiah keluar = pencairan − tambah-saldo. "Tambah saldo" (top-up dari web
     * dashboard) disimpan sebagai pencairan ber-amount negatif, jadi otomatis menambah
     * saldo lewat rumus {@code saldo = komisi − net}.
     */
    public double getTotalWithdrawn(long customerId) {
        return sumAmount(customerId, null);
    }

    /** Total pencairan nyata (amount &gt; 0), tanpa memperhitungkan tambah-saldo. */
    public double getTotalCashedOut(long customerId) {
        return sumAmount(customerId, true);
    }

    /** Total "tambah saldo" (kredit) untuk reseller ini — disimpan sebagai amount negatif. */
    public double getTotalDeposits(long customerId) {
        return -sumAmount(customerId, false);
    }

    /** SUM(amount) untuk reseller; positiveOnly=null semua, true amount&gt;0, false amount&lt;0. */
    private double sumAmount(long customerId, Boolean positiveOnly) {
        String where = DatabaseHelper.COL_WD_CUSTOMER_ID + "=?";
        if (positiveOnly != null) {
            where += " AND " + DatabaseHelper.COL_WD_AMOUNT + (positiveOnly ? ">0" : "<0");
        }
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(" + DatabaseHelper.COL_WD_AMOUNT + "),0) FROM " +
                        DatabaseHelper.TABLE_RESELLER_WD + " WHERE " + where,
                new String[]{String.valueOf(customerId)});
        double total = 0;
        if (c.moveToFirst()) total = c.getDouble(0);
        c.close();
        return total;
    }

    /** Riwayat pencairan reseller, terbaru dulu. */
    public List<Withdrawal> getByCustomer(long customerId) {
        List<Withdrawal> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_RESELLER_WD, null,
                DatabaseHelper.COL_WD_CUSTOMER_ID + "=?",
                new String[]{String.valueOf(customerId)},
                null, null,
                DatabaseHelper.COL_WD_CREATED_AT + " DESC");
        while (c.moveToNext()) {
            Withdrawal w = new Withdrawal();
            w.id = c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_WD_ID));
            w.type = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_WD_TYPE));
            w.galonQty = c.getInt(c.getColumnIndexOrThrow(DatabaseHelper.COL_WD_GALON_QTY));
            w.amount = c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COL_WD_AMOUNT));
            int noteIdx = c.getColumnIndex(DatabaseHelper.COL_WD_NOTE);
            if (noteIdx >= 0 && !c.isNull(noteIdx)) w.note = c.getString(noteIdx);
            w.createdAt = c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_WD_CREATED_AT));
            int expIdx = c.getColumnIndex(DatabaseHelper.COL_WD_EXPENSE_ID);
            if (expIdx >= 0) w.expenseId = c.getLong(expIdx);
            list.add(w);
        }
        c.close();
        return list;
    }
}
