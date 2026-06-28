package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Customer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        putProductPrices(values, customer);
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
        putProductPrices(values, customer);
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
                // Masuk daftar bila: (a) ditandai MANUAL dari web (terlepas riwayat beli), ATAU
                // (b) belum beli ≥ N hari — kecuali sudah di-"Remove", KECUALI beli lagi setelahnya.
                "HAVING c." + DatabaseHelper.COL_FOLLOWUP_MANUAL_AT + " IS NOT NULL " +
                "   OR (last_jual IS NOT NULL AND date(last_jual) < date(?) " +
                "       AND (c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + " IS NULL " +
                "            OR last_jual > c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + ")) " +
                // Entri manual di atas (terbaru dulu), lalu yang paling lama tidak beli.
                "ORDER BY (c." + DatabaseHelper.COL_FOLLOWUP_MANUAL_AT + " IS NULL), " +
                "         c." + DatabaseHelper.COL_FOLLOWUP_MANUAL_AT + " DESC, last_jual ASC";
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
        // Follow-up MANUAL (ditandai dari dashboard) TIDAK dikontrol followup_excluded_at —
        // getFollowUpCandidates memasukkannya selama followup_manual_at terisi. Jadi saat
        // "Hapus", kosongkan juga flag manual + catatannya supaya benar-benar keluar dari
        // daftar, dan perubahan ini ter-sinkron balik ke dashboard (LWW edited_at).
        v.putNull(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT);
        v.putNull(DatabaseHelper.COL_FOLLOWUP_NOTE);
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
        // Harus konsisten dengan getFollowUpCandidates: masuk hitungan bila ditandai
        // MANUAL dari dashboard (followup_manual_at, terlepas riwayat beli) ATAU belum
        // beli ≥ N hari (kecuali sudah di-"Remove", kecuali beli lagi setelahnya).
        String query = "SELECT COUNT(*) FROM (" +
                "SELECT c._id, MAX(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS last_jual " +
                "FROM customers c LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c.name <> ? " +
                "GROUP BY c._id " +
                "HAVING c." + DatabaseHelper.COL_FOLLOWUP_MANUAL_AT + " IS NOT NULL " +
                "   OR (last_jual IS NOT NULL AND date(last_jual) < date(?) " +
                "       AND (c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + " IS NULL " +
                "            OR last_jual > c." + DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT + ")))";
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

    // ==========================================================================
    // Hapus Duplikat — gabung pelanggan dengan nomor HP sama
    // ==========================================================================

    /** Hasil dedupe: berapa grup nomor duplikat & berapa pelanggan digabung/dihapus. */
    public static final class DedupeResult {
        public int groups;    // jumlah grup nomor HP yang punya >1 pelanggan
        public int deleted;   // pelanggan duplikat yang digabung & dihapus
    }

    /** Baris ringkas pelanggan untuk perbandingan dedupe. */
    private static final class DupRow {
        long id;
        double lat, lng;
        String photo;
        int trx;
    }

    private static boolean dupHasGeo(DupRow r) { return r.lat != 0 || r.lng != 0; }

    private static boolean dupHasPhoto(String p) { return p != null && !p.trim().isEmpty(); }

    /** {@code a} lebih layak dipertahankan daripada {@code b}? Prioritas: punya
     *  transaksi (terbanyak) → ada koordinat → ada foto → paling lama (id terkecil). */
    private static boolean dupBetter(DupRow a, DupRow b) {
        if (a.trx != b.trx) return a.trx > b.trx;
        boolean ag = dupHasGeo(a), bg = dupHasGeo(b);
        if (ag != bg) return ag;
        boolean ap = dupHasPhoto(a.photo), bp = dupHasPhoto(b.photo);
        if (ap != bp) return ap;
        return a.id < b.id;
    }

    /** Kunci grup dari nomor HP: 9 digit terakhir (samakan variasi awalan 0/62/+62).
     *  null = nomor kosong/terlalu pendek (tidak bisa di-dedupe). */
    private static String phoneKey(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("[^0-9]", "");
        if (d.length() < 7) return null;
        return d.length() > 9 ? d.substring(d.length() - 9) : d;
    }

    private void repointAll(SQLiteDatabase db, long from, long to) {
        String[] a = {String.valueOf(from)};
        repoint(db, DatabaseHelper.TABLE_TRANSACTIONS, DatabaseHelper.COL_CUSTOMER_ID, to, a);
        repoint(db, DatabaseHelper.TABLE_TRANSACTIONS, DatabaseHelper.COL_TRX_RESELLER_ID, to, a);
        repoint(db, DatabaseHelper.TABLE_RESELLER_RATES, DatabaseHelper.COL_RR_CUSTOMER_ID, to, a);
        repoint(db, DatabaseHelper.TABLE_RESELLER_WD, DatabaseHelper.COL_WD_CUSTOMER_ID, to, a);
        repoint(db, DatabaseHelper.TABLE_ORDER_INBOX, DatabaseHelper.COL_INBOX_CUSTOMER_ID, to, a);
    }

    private void repoint(SQLiteDatabase db, String table, String col, long to, String[] fromArg) {
        try {
            ContentValues v = new ContentValues();
            v.put(col, to);
            // syncUpdate: bump edited_at + dirty so the re-pointing reaches the dashboard.
            dbHelper.syncUpdate(db, table, v, col + "=?", fromArg);
        } catch (Exception ignored) { /* tabel/relasi opsional atau bentrok unik → lewati */ }
    }

    /**
     * Hapus pelanggan duplikat (nomor HP sama). Tiap grup nomor: SATU dipertahankan
     * (prioritas punya transaksi → koordinat → foto), sisanya digabung ke yang
     * dipertahankan — semua transaksi & data terkait dialihkan, koordinat/foto yang
     * kosong di pemenang diisi dari duplikat, lalu duplikat dihapus (tombstone
     * tersinkron). Pelanggan tanpa nomor & "Umum" dilewati.
     */
    public DedupeResult mergeDuplicatesByPhone() {
        DedupeResult res = new DedupeResult();
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        Map<String, List<DupRow>> groups = new LinkedHashMap<>();
        String q = "SELECT c." + DatabaseHelper.COL_ID + ", c." + DatabaseHelper.COL_PHONE
                + ", c." + DatabaseHelper.COL_LATITUDE + ", c." + DatabaseHelper.COL_LONGITUDE
                + ", c." + DatabaseHelper.COL_PHOTO_PATH + ", c." + DatabaseHelper.COL_NAME
                + ", COUNT(t." + DatabaseHelper.COL_TRX_ID + ") AS trx "
                + "FROM " + DatabaseHelper.TABLE_CUSTOMERS + " c "
                + "LEFT JOIN " + DatabaseHelper.TABLE_TRANSACTIONS + " t ON t."
                + DatabaseHelper.COL_CUSTOMER_ID + " = c." + DatabaseHelper.COL_ID + " "
                + "GROUP BY c." + DatabaseHelper.COL_ID;
        Cursor c = db.rawQuery(q, null);
        try {
            while (c.moveToNext()) {
                String name = c.getString(5);
                if (UMUM_NAME.equalsIgnoreCase(name == null ? "" : name.trim())) continue;
                String key = phoneKey(c.getString(1));
                if (key == null) continue;
                DupRow r = new DupRow();
                r.id = c.getLong(0);
                r.lat = c.isNull(2) ? 0 : c.getDouble(2);
                r.lng = c.isNull(3) ? 0 : c.getDouble(3);
                r.photo = c.getString(4);
                r.trx = c.getInt(6);
                List<DupRow> g = groups.get(key);
                if (g == null) { g = new ArrayList<>(); groups.put(key, g); }
                g.add(r);
            }
        } finally { c.close(); }

        db.beginTransaction();
        try {
            for (List<DupRow> grp : groups.values()) {
                if (grp.size() < 2) continue;

                DupRow winner = grp.get(0);
                for (DupRow r : grp) if (dupBetter(r, winner)) winner = r;

                // Lengkapi pemenang dengan koordinat/foto yang hilang dari duplikat.
                boolean winGeo = dupHasGeo(winner), winPhoto = dupHasPhoto(winner.photo);
                ContentValues enrich = new ContentValues();
                for (DupRow r : grp) {
                    if (r.id == winner.id) continue;
                    if (!winGeo && dupHasGeo(r)) {
                        enrich.put(DatabaseHelper.COL_LATITUDE, r.lat);
                        enrich.put(DatabaseHelper.COL_LONGITUDE, r.lng);
                        winGeo = true;
                    }
                    if (!winPhoto && dupHasPhoto(r.photo)) {
                        enrich.put(DatabaseHelper.COL_PHOTO_PATH, r.photo);
                        enrich.put(DatabaseHelper.COL_PHOTO_URL, "");   // re-upload foto baru
                        winPhoto = true;
                    }
                }
                if (enrich.size() > 0) {
                    dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, enrich,
                            DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(winner.id)});
                }

                // Alihkan data tiap duplikat ke pemenang, lalu hapus duplikatnya.
                for (DupRow r : grp) {
                    if (r.id == winner.id) continue;
                    repointAll(db, r.id, winner.id);
                    dbHelper.syncDelete(db, DatabaseHelper.TABLE_CUSTOMERS, "customers",
                            DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(r.id)});
                    res.deleted++;
                }
                res.groups++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return res;
    }

    /** Lepas sufiks " #N" di akhir nama (kalau ada) + rapikan spasi → nama dasar.
     *  Mis. "AYU  LAUNDRY #2" dan "AYU LAUNDRY" sama-sama jadi "AYU LAUNDRY". */
    private static String stripNameSuffix(String name) {
        if (name == null) return "";
        return name.replaceAll("\\s*#\\d+\\s*$", "").trim().replaceAll("\\s+", " ");
    }

    /**
     * Beri nomor pada pelanggan yang BERBAGI nama sama (mis. satu usaha dengan
     * beberapa nomor HP berbeda) menjadi "Nama #1", "Nama #2", … berurutan (urut id).
     * Nama unik dibiarkan apa adanya; "Umum" dilewati. Membandingkan nama dasar
     * (tanpa sufiks " #N") sehingga aman dijalankan berulang. Perubahan tersinkron
     * ke dashboard.
     *
     * @return jumlah pelanggan yang namanya diubah.
     */
    public int numberDuplicateNames() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        Map<String, List<Long>> idsByKey = new LinkedHashMap<>();
        Map<String, String> baseByKey = new HashMap<>();    // casing kanonik (id terkecil)
        Map<Long, String> currentName = new HashMap<>();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_NAME},
                null, null, null, null, DatabaseHelper.COL_ID + " ASC");
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                if (name == null || UMUM_NAME.equalsIgnoreCase(name.trim())) continue;
                String base = stripNameSuffix(name);
                if (base.isEmpty()) continue;
                String key = base.toLowerCase(Locale.ROOT);
                List<Long> ids = idsByKey.get(key);
                if (ids == null) { ids = new ArrayList<>(); idsByKey.put(key, ids); }
                ids.add(id);
                if (!baseByKey.containsKey(key)) baseByKey.put(key, base);
                currentName.put(id, name);
            }
        } finally { c.close(); }

        int renamed = 0;
        db.beginTransaction();
        try {
            for (Map.Entry<String, List<Long>> e : idsByKey.entrySet()) {
                List<Long> ids = e.getValue();
                if (ids.size() < 2) continue;
                String base = baseByKey.get(e.getKey());
                for (int i = 0; i < ids.size(); i++) {
                    long id = ids.get(i);
                    String target = base + " #" + (i + 1);
                    if (!target.equals(currentName.get(id))) {
                        ContentValues v = new ContentValues();
                        v.put(DatabaseHelper.COL_NAME, target);
                        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)});
                        renamed++;
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return renamed;
    }

    /** Tulis harga khusus per produk sebagai JSON { product_uuid: harga }. Kosong/null = clear. */
    private void putProductPrices(ContentValues values, Customer customer) {
        java.util.Map<String, Double> map = customer.getProductPrices();
        if (map == null || map.isEmpty()) {
            values.putNull(DatabaseHelper.COL_PRODUCT_PRICES);
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, Double> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    o.put(e.getKey(), e.getValue().doubleValue());
                }
            }
            if (o.length() > 0) values.put(DatabaseHelper.COL_PRODUCT_PRICES, o.toString());
            else values.putNull(DatabaseHelper.COL_PRODUCT_PRICES);
        } catch (org.json.JSONException ex) {
            values.putNull(DatabaseHelper.COL_PRODUCT_PRICES);
        }
    }

    /** Parse JSON { product_uuid: harga } → Map. Null/kosong/invalid → null. */
    private static java.util.Map<String, Double> parseProductPrices(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Map<String, Double> map = new java.util.HashMap<>();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!o.isNull(k)) map.put(k, o.optDouble(k));
            }
            return map.isEmpty() ? null : map;
        } catch (org.json.JSONException e) {
            return null;
        }
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
        int idxPrices = cursor.getColumnIndex(DatabaseHelper.COL_PRODUCT_PRICES);
        if (idxPrices >= 0 && !cursor.isNull(idxPrices)) {
            c.setProductPrices(parseProductPrices(cursor.getString(idxPrices)));
        }
        int idxKomisi = cursor.getColumnIndex("komisi_galon");
        if (idxKomisi >= 0) c.setKomisiGalon(cursor.getInt(idxKomisi));
        int idxNote = cursor.getColumnIndex(DatabaseHelper.COL_FOLLOWUP_NOTE);
        if (idxNote >= 0 && !cursor.isNull(idxNote)) c.setFollowupNote(cursor.getString(idxNote));
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
