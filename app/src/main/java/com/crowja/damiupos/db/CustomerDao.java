package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Customer;

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
        values.put(DatabaseHelper.COL_IS_RESELLER, customer.isReseller() ? 1 : 0);
        values.put(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, customer.isKomisiAddToPrice() ? 1 : 0);
        if (customer.getResellerSince() != null) {
            values.put(DatabaseHelper.COL_RESELLER_SINCE, customer.getResellerSince());
        }
        // Tanggal daftar custom (default DB = now kalau tidak di-set).
        if (customer.getCreatedAt() != null && !customer.getCreatedAt().isEmpty()) {
            values.put(DatabaseHelper.COL_CREATED_AT, customer.getCreatedAt());
        }
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_CUSTOMERS, values);
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
        values.put(DatabaseHelper.COL_IS_RESELLER, customer.isReseller() ? 1 : 0);
        values.put(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, customer.isKomisiAddToPrice() ? 1 : 0);
        if (customer.getResellerSince() != null) {
            values.put(DatabaseHelper.COL_RESELLER_SINCE, customer.getResellerSince());
        }
        if (customer.getCreatedAt() != null && !customer.getCreatedAt().isEmpty()) {
            values.put(DatabaseHelper.COL_CREATED_AT, customer.getCreatedAt());
        }
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, values,
                DatabaseHelper.COL_ID + "=?",
                new String[]{String.valueOf(customer.getId())});
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_CUSTOMERS, "customers",
                DatabaseHelper.COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Hapus banyak pelanggan dalam satu transaksi SQLite. ON DELETE
     * CASCADE di FK transactions.customer_id akan menghapus transaksi
     * terkait juga.
     *
     * @return jumlah row yang terhapus
     */
    public int deleteMany(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[ids.size()];
        int i = 0;
        for (Long id : ids) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            args[i++] = String.valueOf(id);
        }
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_CUSTOMERS, "customers",
                DatabaseHelper.COL_ID + " IN (" + placeholders + ")",
                args);
    }

    public Customer getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
                "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS first_jual, " +
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
    public static final int SORT_TOTAL_ORDERED = 2; // total galon pernah di-order (semua JUAL)
    public static final int SORT_KONSUMSI = 3;      // konsumsi gl/hr (di-sort di Java)
    public static final int SORT_PINJAM = 4;        // galon sedang dipinjam (saldo)

    /** Bangun klausa ORDER BY dari sortMode. SORT_KONSUMSI di-sort di Java
     *  (butuh pembagian hari) jadi di SQL fallback ke nama. */
    private String orderByClause(int sortMode) {
        switch (sortMode) {
            case SORT_TOTAL_ORDERED:
                return "ORDER BY galon_total_ordered DESC, c.name ASC";
            case SORT_PINJAM:
                return "ORDER BY (galon_keluar - galon_kembali) DESC, c.name ASC";
            case SORT_GALON_DESC:
                return "ORDER BY galon_keluar DESC, c.name ASC";
            case SORT_KONSUMSI:
            case SORT_NAME:
            default:
                return "ORDER BY c.name ASC";
        }
    }

    public List<Customer> getAll() {
        return getAll(SORT_NAME);
    }

    public List<Customer> getAll(int sortMode) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String orderBy = orderByClause(sortMode);
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
                "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS first_jual, " +
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
        String orderBy = orderByClause(sortMode);
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
                "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS first_jual, " +
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
        // Exclude the special "Umum" walk-in customer — they have no phone
        // and shouldn't be followed up.
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(t._id) AS total_trx, " +
                "MAX(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS last_jual " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c.name <> ? " +
                "GROUP BY c._id " +
                "HAVING last_jual IS NOT NULL AND date(last_jual) < date(?) " +
                // Kecualikan pelanggan yang di-"Remove" dari follow-up, KECUALI
                // mereka beli lagi setelah dikeluarkan (last_jual lebih baru).
                "AND (c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + " IS NULL " +
                "     OR last_jual > c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + ") " +
                "ORDER BY last_jual ASC";
        Cursor cursor = db.rawQuery(query, new String[]{UMUM_NAME, cutoff});
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

    /**
     * "Remove" pelanggan dari daftar Follow Up dengan alasan (wajib). Disimpan
     * sebagai timestamp + alasan; pelanggan otomatis muncul lagi kalau beli
     * setelah ini ({@link #getFollowUpCandidates}).
     */
    public void excludeFromFollowUp(long customerId, String reason) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()));
        v.put(DatabaseHelper.COL_FOLLOWUP_EXCLUDE_REASON, reason);
        // syncUpdate: bumps edited_at + dirty so the exclusion shares across the branch.
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    /**
     * Kosongkan photo_url (URL foto di server) supaya foto baru di-upload ulang.
     * Dipakai saat foto pelanggan diganti; MediaUploader jalan sebelum push, jadi
     * URL di dashboard tetap mutakhir. Baris sudah dirty dari update() sebelumnya.
     */
    public void clearPhotoUrl(long customerId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(DatabaseHelper.COL_PHOTO_URL, "");
        db.update(DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    /** Update HANYA flag "komisi ke harga jual" (tanpa menyentuh field lain). */
    public void setKomisiAddToPrice(long customerId, boolean value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, value ? 1 : 0);
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    /** Tandai pelanggan baru saja di-follow-up (kirim pesan WA). */
    public void markFollowedUp(long customerId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(DatabaseHelper.COL_LAST_FOLLOWUP_AT,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()));
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    /** Pelanggan yang di-follow-up pada tanggal {@code date} ("yyyy-MM-dd"). */
    public List<Customer> getFollowedUpOn(String date) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_CUSTOMERS, null,
                "date(" + DatabaseHelper.COL_LAST_FOLLOWUP_AT + ")=?",
                new String[]{date}, null, null,
                DatabaseHelper.COL_NAME + " COLLATE NOCASE ASC");
        while (cursor.moveToNext()) list.add(cursorToCustomer(cursor));
        cursor.close();
        return list;
    }

    /** Count of customers needing follow-up (last JUAL > days ago). */
    public int countFollowUpCandidates(int days) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String cutoff = cutoffTimestamp(days);
        // Exclude the "Umum" walk-in customer — they have no phone.
        String query = "SELECT COUNT(*) FROM (" +
                "SELECT c._id, MAX(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS last_jual " +
                "FROM customers c LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c.name <> ? " +
                "GROUP BY c._id " +
                "HAVING last_jual IS NOT NULL AND date(last_jual) < date(?) " +
                "AND (c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + " IS NULL " +
                "     OR last_jual > c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + "))";
        Cursor cursor = db.rawQuery(query, new String[]{UMUM_NAME, cutoff});
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    /**
     * Return the cutoff as a DATE-only string "yyyy-MM-dd" (device local).
     * Follow-up uses {@code date(last_jual) < date(cutoff)} so a customer who
     * re-orders today (last_jual = today) is ALWAYS delisted — even if the
     * stored timestamp has a time component that would otherwise sort after a
     * datetime cutoff. Comparing dates avoids the edge case where last_jual and
     * cutoff fall on the same calendar day but differ by hours.
     */
    private String cutoffTimestamp(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
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

    /** Jumlah pelanggan baru yang dibuat dalam rentang tanggal (inklusif). */
    public int getCountCreatedBetween(String startDate, String endDate) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM customers WHERE date(" + DatabaseHelper.COL_CREATED_AT
                        + ") >= ? AND date(" + DatabaseHelper.COL_CREATED_AT + ") <= ?",
                new String[]{startDate, endDate});
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    /** Reserved name for walk-in / unnamed customers. */
    public static final String UMUM_NAME = "Umum";

    /**
     * Returns the special "Umum" walk-in customer, creating one if it
     * doesn't exist yet. Used when the cashier doesn't want to record
     * a specific customer (drop-in / no-name sale).
     */
    public Customer getOrCreateUmum() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT _id FROM customers WHERE name = ? LIMIT 1",
                new String[]{UMUM_NAME});
        long umumId = -1;
        if (cursor.moveToFirst()) {
            umumId = cursor.getLong(0);
        }
        cursor.close();
        if (umumId == -1) {
            Customer u = new Customer();
            u.setName(UMUM_NAME);
            u.setPhone("");
            u.setAddress("");
            u.setPhotoPath("");
            u.setLatitude(0);
            u.setLongitude(0);
            umumId = insert(u);
        }
        return getById(umumId);
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
        // Kolom agregat (galon_keluar/kembali/total_trx) hanya ada di query yang
        // menghitungnya (getAll/getResellers). Query polos seperti getFollowedUpOn
        // (SELECT *) tidak punya — baca dengan guard supaya tidak crash.
        int idxKeluar = cursor.getColumnIndex("galon_keluar");
        if (idxKeluar >= 0) c.setGalonKeluar(cursor.getInt(idxKeluar));
        int idxKembali = cursor.getColumnIndex("galon_kembali");
        if (idxKembali >= 0) c.setGalonKembali(cursor.getInt(idxKembali));
        int idxTotalTrx = cursor.getColumnIndex("total_trx");
        if (idxTotalTrx >= 0) c.setTotalTransaksi(cursor.getInt(idxTotalTrx));
        int idxTotalOrdered = cursor.getColumnIndex("galon_total_ordered");
        if (idxTotalOrdered >= 0) c.setGalonTotalOrdered(cursor.getInt(idxTotalOrdered));
        int idxFirstJual = cursor.getColumnIndex("first_jual");
        if (idxFirstJual >= 0 && !cursor.isNull(idxFirstJual)) {
            c.setFirstOrderDate(cursor.getString(idxFirstJual));
        }
        int idxReseller = cursor.getColumnIndex(DatabaseHelper.COL_IS_RESELLER);
        if (idxReseller >= 0) c.setReseller(cursor.getInt(idxReseller) == 1);
        int idxSince = cursor.getColumnIndex(DatabaseHelper.COL_RESELLER_SINCE);
        if (idxSince >= 0 && !cursor.isNull(idxSince)) {
            c.setResellerSince(cursor.getString(idxSince));
        }
        int idxAddPrice = cursor.getColumnIndex(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE);
        if (idxAddPrice >= 0) c.setKomisiAddToPrice(cursor.getInt(idxAddPrice) == 1);
        int idxKomisi = cursor.getColumnIndex("komisi_galon");
        if (idxKomisi >= 0) c.setKomisiGalon(cursor.getInt(idxKomisi));
        return c;
    }

    /**
     * Semua reseller beserta {@code komisi_galon} = total galon JUAL sejak
     * {@code reseller_since}. Saldo komisi rupiah dihitung caller:
     * komisi_galon × rate − total pencairan (lihat ResellerWithdrawalDao).
     */
    public List<Customer> getResellers() {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COUNT(t._id) AS total_trx, " +
                // Galon basis komisi: transaksi JUAL yg di-afiliasikan ke reseller
                // ini (reseller_id) maupun pembelian langsung tanpa afiliasi
                // (customer_id). Subquery agar tidak terikat join customer_id.
                "COALESCE((SELECT SUM(t2." + DatabaseHelper.COL_JUMLAH_GALON + ") " +
                "    FROM " + DatabaseHelper.TABLE_TRANSACTIONS + " t2 " +
                "    WHERE t2." + DatabaseHelper.COL_TYPE + "='JUAL' " +
                "      AND (t2." + DatabaseHelper.COL_TRX_RESELLER_ID + " = c._id " +
                "           OR (t2." + DatabaseHelper.COL_CUSTOMER_ID + " = c._id " +
                "               AND COALESCE(t2." + DatabaseHelper.COL_TRX_RESELLER_ID + ",0)=0)) " +
                "      AND (c." + DatabaseHelper.COL_RESELLER_SINCE + " IS NULL " +
                "           OR t2." + DatabaseHelper.COL_TANGGAL + " >= c." + DatabaseHelper.COL_RESELLER_SINCE + ") " +
                "      AND COALESCE(t2." + DatabaseHelper.COL_CATATAN + ",'') NOT LIKE '%[JUAL BOTOL KOSONG]%'" +
                "),0) AS komisi_galon " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c." + DatabaseHelper.COL_IS_RESELLER + " = 1 " +
                "GROUP BY c._id " +
                "ORDER BY komisi_galon DESC, c.name ASC";
        Cursor cursor = db.rawQuery(query, null);
        while (cursor.moveToNext()) {
            list.add(cursorToCustomer(cursor));
        }
        cursor.close();
        return list;
    }
}
