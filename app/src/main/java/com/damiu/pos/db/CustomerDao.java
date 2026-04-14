package com.damiu.pos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.damiu.pos.model.Customer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class CustomerDao {

    private final DatabaseHelper dbHelper;

    public CustomerDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(Customer customer) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_NAME, customer.getName());
        values.put(DatabaseHelper.COL_PHONE, customer.getPhone());
        values.put(DatabaseHelper.COL_ADDRESS, customer.getAddress());
        values.put(DatabaseHelper.COL_PHOTO_PATH, customer.getPhotoPath());
        values.put(DatabaseHelper.COL_LATITUDE, customer.getLatitude());
        values.put(DatabaseHelper.COL_LONGITUDE, customer.getLongitude());
        return db.insert(DatabaseHelper.TABLE_CUSTOMERS, null, values);
    }

    public int update(Customer customer) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_NAME, customer.getName());
        values.put(DatabaseHelper.COL_PHONE, customer.getPhone());
        values.put(DatabaseHelper.COL_ADDRESS, customer.getAddress());
        values.put(DatabaseHelper.COL_PHOTO_PATH, customer.getPhotoPath());
        values.put(DatabaseHelper.COL_LATITUDE, customer.getLatitude());
        values.put(DatabaseHelper.COL_LONGITUDE, customer.getLongitude());
        return db.update(DatabaseHelper.TABLE_CUSTOMERS, values,
                DatabaseHelper.COL_ID + "=?",
                new String[]{String.valueOf(customer.getId())});
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(DatabaseHelper.TABLE_CUSTOMERS,
                DatabaseHelper.COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    public Customer getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(t._id) AS total_trx " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c._id = ? " +
                "GROUP BY c._id";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(id)});
        Customer customer = null;
        if (cursor.moveToFirst()) {
            customer = cursorToCustomer(cursor);
        }
        cursor.close();
        return customer;
    }

    public static final int SORT_NAME = 0;
    public static final int SORT_GALON_DESC = 1;

    public List<Customer> getAll() {
        return getAll(SORT_NAME);
    }

    public List<Customer> getAll(int sortMode) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String orderBy = sortMode == SORT_GALON_DESC
                ? "ORDER BY galon_keluar DESC, c.name ASC"
                : "ORDER BY c.name ASC";
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(t._id) AS total_trx " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "GROUP BY c._id " +
                orderBy;
        Cursor cursor = db.rawQuery(query, null);
        while (cursor.moveToNext()) {
            list.add(cursorToCustomer(cursor));
        }
        cursor.close();
        return list;
    }

    public List<Customer> search(String keyword) {
        return search(keyword, SORT_NAME);
    }

    public List<Customer> search(String keyword, int sortMode) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String orderBy = sortMode == SORT_GALON_DESC
                ? "ORDER BY galon_keluar DESC, c.name ASC"
                : "ORDER BY c.name ASC";
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(t._id) AS total_trx " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c.name LIKE ? OR c.phone LIKE ? OR c.address LIKE ? " +
                "GROUP BY c._id " +
                orderBy;
        String like = "%" + keyword + "%";
        Cursor cursor = db.rawQuery(query, new String[]{like, like, like});
        while (cursor.moveToNext()) {
            list.add(cursorToCustomer(cursor));
        }
        cursor.close();
        return list;
    }

    /** Total galon yang masih beredar di semua pelanggan */
    public int getTotalGalonBeredar() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT " +
                "COALESCE(SUM(CASE WHEN type='JUAL' THEN jumlah_galon ELSE 0 END),0) - " +
                "COALESCE(SUM(CASE WHEN type='KEMBALI' THEN jumlah_galon ELSE 0 END),0) " +
                "FROM transactions";
        Cursor cursor = db.rawQuery(query, null);
        int total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getInt(0);
        }
        cursor.close();
        return total;
    }

    /** Top customers by purchase count, for reports */
    public List<Customer> getTopCustomers(int limit) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(CASE WHEN t.type='JUAL' THEN 1 END) AS total_trx " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "GROUP BY c._id " +
                "HAVING total_trx > 0 " +
                "ORDER BY galon_keluar DESC " +
                "LIMIT ?";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(limit)});
        while (cursor.moveToNext()) {
            list.add(cursorToCustomer(cursor));
        }
        cursor.close();
        return list;
    }

    /** Check if customer with this phone number exists */
    public boolean existsByPhone(String phone) {
        if (phone == null || phone.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        // Normalize: match last 8+ digits to handle prefix variations
        String normalized = phone.replaceAll("[^0-9]", "");
        if (normalized.length() < 4) return false;
        String suffix = normalized.substring(normalized.length() - Math.min(normalized.length(), 8));
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM customers WHERE REPLACE(REPLACE(REPLACE(phone,' ',''),'-',''),'+','') LIKE ?",
                new String[]{"%" + suffix});
        boolean exists = false;
        if (cursor.moveToFirst()) {
            exists = cursor.getInt(0) > 0;
        }
        cursor.close();
        return exists;
    }

    /**
     * Return customers whose last JUAL transaction is older than `days` days ago,
     * ordered by longest-inactive first. Each Customer carries galonKeluar/galonKembali
     * and createdAt is overloaded with the last purchase date for convenience.
     */
    public List<Customer> getFollowUpCandidates(int days) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String cutoff = cutoffTimestamp(days);
        // Filter by last_jual < cutoff (string compare works because tanggal is
        // stored in ISO "yyyy-MM-dd HH:mm:ss" format which is lexicographically
        // sortable). Avoids SQLite julianday() quirks across devices.
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(t._id) AS total_trx, " +
                "MAX(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS last_jual " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "GROUP BY c._id " +
                "HAVING last_jual IS NOT NULL AND last_jual < ? " +
                "ORDER BY last_jual ASC";
        Cursor cursor = db.rawQuery(query, new String[]{cutoff});
        while (cursor.moveToNext()) {
            Customer c = cursorToCustomer(cursor);
            int idx = cursor.getColumnIndex("last_jual");
            if (idx >= 0) {
                c.setCreatedAt(cursor.getString(idx)); // overloaded: last purchase ts
            }
            list.add(c);
        }
        cursor.close();
        return list;
    }

    /** Count of customers needing follow-up (last JUAL > days ago). */
    public int countFollowUpCandidates(int days) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String cutoff = cutoffTimestamp(days);
        String query = "SELECT COUNT(*) FROM (" +
                "SELECT c._id, MAX(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS last_jual " +
                "FROM customers c LEFT JOIN transactions t ON c._id = t.customer_id " +
                "GROUP BY c._id " +
                "HAVING last_jual IS NOT NULL AND last_jual < ?)";
        Cursor cursor = db.rawQuery(query, new String[]{cutoff});
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    /** Return "now minus N days" as "yyyy-MM-dd HH:mm:ss" in device local time. */
    private String cutoffTimestamp(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(cal.getTime());
    }

    public int getTotalCustomers() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM customers", null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    private Customer cursorToCustomer(Cursor cursor) {
        Customer c = new Customer();
        c.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_ID)));
        c.setName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_NAME)));
        c.setPhone(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PHONE)));
        c.setAddress(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_ADDRESS)));
        c.setPhotoPath(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_PHOTO_PATH)));
        c.setLatitude(cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_LATITUDE)));
        c.setLongitude(cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_LONGITUDE)));
        c.setCreatedAt(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_CREATED_AT)));
        c.setGalonKeluar(cursor.getInt(cursor.getColumnIndexOrThrow("galon_keluar")));
        c.setGalonKembali(cursor.getInt(cursor.getColumnIndexOrThrow("galon_kembali")));
        c.setTotalTransaksi(cursor.getInt(cursor.getColumnIndexOrThrow("total_trx")));
        return c;
    }
}
