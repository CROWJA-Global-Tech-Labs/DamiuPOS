package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.model.User;

import java.util.ArrayList;
import java.util.List;

public class TransactionDao {

    private final DatabaseHelper dbHelper;

    public TransactionDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(Transaction trx) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_CUSTOMER_ID, trx.getCustomerId());
        values.put(DatabaseHelper.COL_TRX_PRODUCT_ID, trx.getProductId());
        values.put(DatabaseHelper.COL_TYPE, trx.getType());
        values.put(DatabaseHelper.COL_JUMLAH_GALON, trx.getJumlahGalon());
        values.put(DatabaseHelper.COL_HARGA_PER_GALON, trx.getHargaPerGalon());
        values.put(DatabaseHelper.COL_TOTAL_HARGA, trx.getTotalHarga());
        values.put(DatabaseHelper.COL_ONGKIR, trx.getOngkir());
        values.put(DatabaseHelper.COL_ONGKIR_TYPE,
                trx.getOngkirType() != null ? trx.getOngkirType() : Transaction.ONGKIR_PER_GALON);
        values.put(DatabaseHelper.COL_GALON_OWNERSHIP,
                trx.getGalonOwnership() != null ? trx.getGalonOwnership() : Transaction.OWNERSHIP_PINJAM);
        values.put(DatabaseHelper.COL_HARGA_BOTOL, trx.getHargaBotolGalon());
        if (trx.getPaymentMethod() != null) {
            values.put(DatabaseHelper.COL_PAYMENT_METHOD, trx.getPaymentMethod());
        }
        values.put(DatabaseHelper.COL_TRX_RESELLER_ID, trx.getResellerId());
        String itemsJson = TransactionItem.listToJson(trx.getItems());
        if (itemsJson != null) {
            values.put(DatabaseHelper.COL_ITEMS_JSON, itemsJson);
        }
        if (trx.getCatatan() != null) {
            values.put(DatabaseHelper.COL_CATATAN, trx.getCatatan());
        }
        // Tanggal custom (default DB = datetime now kalau tidak di-set).
        if (trx.getTanggal() != null && !trx.getTanggal().isEmpty()) {
            values.put(DatabaseHelper.COL_TANGGAL, trx.getTanggal());
        }
        // Resolve operator SEKALI (sebelumnya id/nama/role di-query 5-6× per transaksi).
        SettingsDao sdao = new SettingsDao(dbHelper);
        long operatorUid = sdao.getCurrentUserId();
        User op = operatorUid > 0 ? new UserDao(dbHelper).getById(operatorUid) : null;
        // Atribusi pembuat: operator yang sedang clock-in di perangkat ini (didenormalisasi
        // namanya). created_via dibiarkan NULL = perangkat asal (web view jatuh ke nama
        // perangkat). Disinkron agar dashboard menampilkan "dibuat oleh …".
        String operator = sdao.getCurrentUserName();
        if (operator != null && !operator.isEmpty()) {
            values.put(DatabaseHelper.COL_CREATED_BY_NAME, operator);
        }
        // Antrian Delivery: JUAL masuk antrian PENDING dengan waktu antri = sekarang
        // (real, bukan `tanggal` yang bisa di-backdate). KEMBALI tidak diantrikan.
        // Penjualan oleh MARKETING tidak diantrikan sama sekali: akuisisi di lokasi,
        // air langsung diserahkan (bukan kiriman kurir) → tanpa delivery_status/token,
        // jadi tidak muncul di antrean HP maupun halaman Delivery dashboard web.
        boolean marketing = op != null && op.isMarketing();
        if (Transaction.TYPE_JUAL.equals(trx.getType()) && !marketing) {
            values.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_PENDING);
            values.put(DatabaseHelper.COL_DELIVERY_QUEUED_AT, DatabaseHelper.nowIso());
            // Token link lacak publik (32 hex) → {base}/track/{token}, dikirim ke
            // pelanggan agar bisa memantau progres + lokasi kurir saat diantar.
            values.put(DatabaseHelper.COL_DELIVERY_TOKEN,
                    java.util.UUID.randomUUID().toString().replace("-", ""));
            // Operator (kurir) = user yang sedang clock-in; disinkron sebagai staff_uuid
            // supaya halaman lacak menampilkan GPS kurir yang benar.
            if (operatorUid > 0) values.put(DatabaseHelper.COL_DELIVERY_STAFF_ID, operatorUid);
            // Lokasi tujuan pengiriman terpilih (multi-lokasi pelanggan). Hanya ditulis
            // bila di-set — 0/NULL = pakai koordinat pelanggan saat navigasi.
            if (trx.getDeliveryDestName() != null && !trx.getDeliveryDestName().isEmpty()) {
                values.put(DatabaseHelper.COL_DELIVERY_DEST_NAME, trx.getDeliveryDestName());
            }
            if (trx.getDeliveryDestLat() != 0 || trx.getDeliveryDestLng() != 0) {
                values.put(DatabaseHelper.COL_DELIVERY_DEST_LAT, trx.getDeliveryDestLat());
                values.put(DatabaseHelper.COL_DELIVERY_DEST_LNG, trx.getDeliveryDestLng());
            }
        }
        // Jaring absensi otomatis: kalau operator (staf yang sedang clock-in) BELUM punya event
        // MASUK hari ini — mis. shift kebawa lewat tengah malam, atau sesi tersambung dari sync
        // tanpa clock-in — catat MASUK otomatis saat transaksi pertamanya. Tanpa ini, staf yang
        // jelas bekerja (punya transaksi + ping GPS) bisa tercatat "tidak absen" di dashboard.
        // Idempoten (sekali per hari) & hanya untuk role ber-absensi (staf/SPV/marketing).
        if (op != null && op.tracksAttendance()) {
            AttendanceDao attDao = new AttendanceDao(dbHelper);
            if (!attDao.hasInToday(operatorUid)) {
                attDao.log(operatorUid, Attendance.EVENT_IN);
            }
        }
        long id = dbHelper.syncInsert(db, DatabaseHelper.TABLE_TRANSACTIONS, values);
        // Jaring pengaman integritas: kalau pelanggan ini belum ada di web dashboard
        // (synced=0 → belum terkonfirmasi terkirim), dorong SELURUH riwayat transaksi
        // miliknya ikut naik bersama datanya — supaya tidak ada transaksi "yatim"
        // (customer_uuid tanpa padanan pelanggan) di dashboard.
        requeueHistoryIfCustomerNotOnDashboard(db, trx.getCustomerId());
        return id;
    }

    /**
     * Bila pelanggan belum terkonfirmasi ada di dashboard (synced=0), tandai ulang
     * semua transaksinya sebagai dirty (synced=0) TANPA mengubah edited_at — sehingga
     * pada sinkron berikutnya pelanggan + seluruh riwayatnya terkirim, dan
     * last-write-wins server tetap akurat (baris yang sudah ada tidak ditimpa basi).
     */
    private void requeueHistoryIfCustomerNotOnDashboard(SQLiteDatabase db, long customerId) {
        if (customerId <= 0) return;
        boolean onDashboard;
        try (Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                new String[]{DatabaseHelper.COL_SYNCED},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)},
                null, null, null)) {
            onDashboard = c.moveToFirst() && c.getInt(0) == 1;
        }
        if (onDashboard) return;   // sudah ada → alur dirty normal sudah cukup
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SYNCED, 0);   // edited_at sengaja tidak diubah
        db.update(DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_CUSTOMER_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    // ----------------------------------------------------------- Antrian Delivery

    /** Jumlah order yang masih di antrian (PENDING) — untuk badge dashboard. */
    public int countDeliveryQueue() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                // Hitung identitas unik (sync_uuid/delivery_token/_id) supaya badge tidak
                // menggelembung karena salinan lokal duplikat dari satu order tersinkron.
                "SELECT COUNT(DISTINCT COALESCE(" + DatabaseHelper.COL_SYNC_UUID + ", "
                        + DatabaseHelper.COL_DELIVERY_TOKEN + ", CAST(" + DatabaseHelper.COL_TRX_ID
                        + " AS TEXT))) FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                        + " WHERE " + DatabaseHelper.COL_DELIVERY_STATUS + "=?"
                        // Antrian HP ini = order belum di-route (NULL) ATAU di-route ke HP ini.
                        + " AND (" + DatabaseHelper.COL_DELIVERY_DEVICE_UUID + " IS NULL OR "
                        + DatabaseHelper.COL_DELIVERY_DEVICE_UUID + " = COALESCE((SELECT "
                        + DatabaseHelper.COL_SETTING_VALUE + " FROM " + DatabaseHelper.TABLE_SETTINGS
                        + " WHERE " + DatabaseHelper.COL_SETTING_KEY + "='sync_device_uuid'), ''))",
                new String[]{Transaction.DELIVERY_PENDING})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /**
     * Antrian delivery (JUAL PENDING) terlama dulu, lengkap dengan data pelanggan
     * (nama/telepon/koordinat) untuk navigasi. Item terisi dari items_json.
     */
    public List<Transaction> getDeliveryQueue() {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT t.*, c." + DatabaseHelper.COL_NAME + " AS cust_name, "
                + "c." + DatabaseHelper.COL_PHONE + " AS cust_phone, "
                + "c." + DatabaseHelper.COL_ADDRESS + " AS cust_addr, "
                + "c." + DatabaseHelper.COL_LATITUDE + " AS cust_lat, "
                + "c." + DatabaseHelper.COL_LONGITUDE + " AS cust_lng "
                + "FROM " + DatabaseHelper.TABLE_TRANSACTIONS + " t "
                + "LEFT JOIN " + DatabaseHelper.TABLE_CUSTOMERS + " c ON c."
                + DatabaseHelper.COL_ID + " = t." + DatabaseHelper.COL_CUSTOMER_ID + " "
                + "WHERE t." + DatabaseHelper.COL_DELIVERY_STATUS + "=? "
                // Antrian HP ini = order belum di-route (NULL) ATAU di-route ke HP ini (Efisiensikan
                // Delivery). Order yang di-route ke HP lain tetap ada lokal (asal) tapi disembunyikan.
                + "AND (t." + DatabaseHelper.COL_DELIVERY_DEVICE_UUID + " IS NULL OR t."
                + DatabaseHelper.COL_DELIVERY_DEVICE_UUID + " = COALESCE((SELECT "
                + DatabaseHelper.COL_SETTING_VALUE + " FROM " + DatabaseHelper.TABLE_SETTINGS
                + " WHERE " + DatabaseHelper.COL_SETTING_KEY + "='sync_device_uuid'), '')) "
                // Dedup salinan lokal dari SATU order tersinkron: identitas = sync_uuid, fallback
                // delivery_token, fallback _id. Tampilkan hanya baris _id terkecil per identitas.
                // Dua order yang benar-benar berbeda punya uuid+token beda → tetap tampil dua-duanya.
                + "AND t." + DatabaseHelper.COL_TRX_ID + " = (SELECT MIN(t2." + DatabaseHelper.COL_TRX_ID
                + ") FROM " + DatabaseHelper.TABLE_TRANSACTIONS + " t2 WHERE t2."
                + DatabaseHelper.COL_DELIVERY_STATUS + " = t." + DatabaseHelper.COL_DELIVERY_STATUS
                + " AND COALESCE(t2." + DatabaseHelper.COL_SYNC_UUID + ", t2." + DatabaseHelper.COL_DELIVERY_TOKEN
                + ", CAST(t2." + DatabaseHelper.COL_TRX_ID + " AS TEXT)) = COALESCE(t."
                + DatabaseHelper.COL_SYNC_UUID + ", t." + DatabaseHelper.COL_DELIVERY_TOKEN
                + ", CAST(t." + DatabaseHelper.COL_TRX_ID + " AS TEXT))) "
                + "ORDER BY t." + DatabaseHelper.COL_DELIVERY_QUEUED_AT + " ASC";
        try (Cursor c = db.rawQuery(sql, new String[]{Transaction.DELIVERY_PENDING})) {
            while (c.moveToNext()) {
                Transaction t = new Transaction();
                t.setId(getLong(c, DatabaseHelper.COL_TRX_ID));
                t.setCustomerId(getLong(c, DatabaseHelper.COL_CUSTOMER_ID));
                t.setType(getStr(c, DatabaseHelper.COL_TYPE));
                t.setJumlahGalon((int) getLong(c, DatabaseHelper.COL_JUMLAH_GALON));
                t.setTotalHarga(getDouble(c, DatabaseHelper.COL_TOTAL_HARGA));
                t.setOngkir(getDouble(c, DatabaseHelper.COL_ONGKIR));
                t.setPaymentMethod(getStr(c, DatabaseHelper.COL_PAYMENT_METHOD));
                t.setGalonOwnership(getStr(c, DatabaseHelper.COL_GALON_OWNERSHIP));
                t.setCatatan(getStr(c, DatabaseHelper.COL_CATATAN));
                t.setDeliveryStatus(getStr(c, DatabaseHelper.COL_DELIVERY_STATUS));
                t.setDeliveryQueuedAt(getStr(c, DatabaseHelper.COL_DELIVERY_QUEUED_AT));
                t.setDeliveryToken(getStr(c, DatabaseHelper.COL_DELIVERY_TOKEN));
                // Lokasi tujuan terpilih (multi-lokasi) — SELECT t.* sudah memuatnya.
                t.setDeliveryDestName(getStr(c, DatabaseHelper.COL_DELIVERY_DEST_NAME));
                t.setDeliveryDestLat(getDouble(c, DatabaseHelper.COL_DELIVERY_DEST_LAT));
                t.setDeliveryDestLng(getDouble(c, DatabaseHelper.COL_DELIVERY_DEST_LNG));
                String itemsJson = getStr(c, DatabaseHelper.COL_ITEMS_JSON);
                if (itemsJson != null) t.setItems(TransactionItem.listFromJson(itemsJson));
                t.setCustomerName(getStr(c, "cust_name"));
                t.setCustomerPhone(getStr(c, "cust_phone"));
                // Koordinat pelanggan disisipkan ke item lewat field display.
                t.setCustomerLat(getDouble(c, "cust_lat"));
                t.setCustomerLng(getDouble(c, "cust_lng"));
                t.setCustomerAddress(getStr(c, "cust_addr"));
                list.add(t);
            }
        }
        return list;
    }

    /** Tandai order Selesai: status DONE + waktu selesai = sekarang (durasi terhitung). */
    public void markDelivered(long trxId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_DONE);
        v.put(DatabaseHelper.COL_DELIVERY_DONE_AT, DatabaseHelper.nowIso());
        // Atribusi penyelesai: kurir yang menandai Selesai (bisa ≠ pembuat order, mis.
        // order disusun admin di web lalu diantar staf ini). Disinkron ke dashboard.
        String courier = new SettingsDao(dbHelper).getCurrentUserName();
        if (courier != null && !courier.isEmpty()) {
            v.put(DatabaseHelper.COL_COMPLETED_BY_NAME, courier);
        }
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    private static long getLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0;
    }

    private static double getDouble(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getDouble(i) : 0;
    }

    private static String getStr(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 ? c.getString(i) : null;
    }

    // -- Tanggal: campuran 2 format --
    // tanggal bisa tersimpan sebagai UTC ISO ("…Z", hasil sinkron dari server) ATAU
    // wall-clock LOKAL "yyyy-MM-dd HH:mm:ss" (dibuat langsung di perangkat). Untuk
    // pengelompokan "hari ini"/rentang tanggal, baris UTC HARUS dikonversi ke waktu
    // lokal dulu — kalau tidak, transaksi sore (yang di UTC jatuh ke tanggal kemarin)
    // hilang dari filter "Hari Ini". Baris lokal dibiarkan apa adanya.
    private static String localDt(String col) {
        return "(CASE WHEN " + col + " LIKE '%Z' THEN datetime(" + col + ",'localtime') ELSE " + col + " END)";
    }
    /** date() atas wall-clock lokal kolom tanggal (lihat {@link #localDt}). */
    private static String localDate(String col) { return "date(" + localDt(col) + ")"; }
    /** strftime('%Y-%m') atas wall-clock lokal kolom tanggal. */
    private static String localMonth(String col) { return "strftime('%Y-%m'," + localDt(col) + ")"; }

    /**
     * Nomor order harian per cabang (reset tiap hari): urutan transaksi JUAL ini di antara
     * JUAL pada hari kalender LOKAL yang sama, diurut waktu lokal (tanggal). Aturan yang
     * SAMA dipakai di web dashboard agar nomornya identik. 0 = bukan JUAL / tidak ada.
     */
    public int dailyOrderNo(long trxId) {
        if (trxId <= 0) return 0;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String ld = localDt(DatabaseHelper.COL_TANGGAL);
        String id = DatabaseHelper.COL_TRX_ID;
        String tbl = DatabaseHelper.TABLE_TRANSACTIONS;
        String arg = String.valueOf(trxId);
        String sql = "SELECT COUNT(*) FROM " + tbl + " WHERE type='JUAL'"
                + " AND (SELECT type FROM " + tbl + " WHERE " + id + "=?)='JUAL'"
                + " AND date(" + ld + ") = (SELECT date(" + ld + ") FROM " + tbl + " WHERE " + id + "=?)"
                + " AND " + ld + " <= (SELECT " + ld + " FROM " + tbl + " WHERE " + id + "=?)";
        try (Cursor c = db.rawQuery(sql, new String[]{arg, arg, arg})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_TRANSACTIONS, "transactions",
                DatabaseHelper.COL_TRX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Edit terbatas (staf operator): ubah metode pembayaran transaksi JUAL. syncUpdate bump edited_at
     *  + synced=0 → LWW ke server & perangkat lain. Nominal/total tidak berubah. */
    public int updatePaymentMethod(long trxId, String method) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_PAYMENT_METHOD, method);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /** Edit terbatas (staf operator): ubah jumlah galon pada transaksi KEMBALI (galon dikembalikan).
     *  Saldo galon pelanggan dihitung dari transaksi, jadi otomatis ikut ter-recompute di server &
     *  perangkat lain setelah sinkron. syncUpdate bump edited_at + synced=0 → LWW. */
    public int updateKembaliGalon(long trxId, int galon) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_JUMLAH_GALON, galon);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /** Get a single transaction by id, or null */
    public Transaction getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT t.*, c.name AS customer_name, c.phone AS customer_phone, " +
                "p.name AS product_name " +
                "FROM transactions t " +
                "JOIN customers c ON t.customer_id = c._id " +
                "LEFT JOIN products p ON t.product_id = p._id " +
                "WHERE t._id = ? LIMIT 1";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(id)});
        Transaction t = null;
        if (cursor.moveToFirst()) {
            t = cursorToTransaction(cursor);
            int phoneIdx = cursor.getColumnIndex("customer_phone");
            if (phoneIdx >= 0) {
                // We'll stash phone into productName isn't a thing; skip — caller can lookup via CustomerDao
            }
        }
        cursor.close();
        return t;
    }

    /**
     * True when the customer was REGISTERED on the same local day as this transaction → "pelanggan
     * baru / walk-in baru" di struk. Membandingkan {@code customers.created_at} dengan {@code tanggal}
     * transaksi setelah keduanya dinormalkan ke tanggal kalender LOKAL (lihat {@link #localDt}), jadi
     * perilakunya identik untuk baris buatan perangkat (waktu lokal) maupun sinkron web (UTC ISO "…Z").
     *
     * Sengaja TIDAK memakai "transaksi pertama": pelanggan yang didaftarkan minggu lalu lalu baru
     * pertama kali beli hari ini BUKAN pelanggan baru. Aturan berbasis created_at juga konsisten
     * lintas-platform (mirror Transaction::isCustomerNew di web) dan tak bergantung pada apakah seluruh
     * riwayat transaksi pelanggan tersedia di perangkat ini. customerId ≤ 0 (walk-in tanpa simpan) → false.
     */
    public boolean isNewCustomerOnDay(long customerId, String tanggal) {
        if (customerId <= 0 || tanggal == null || tanggal.isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String custDay = localDate("created_at");   // date(localtime(customers.created_at))
        String txDay = "date(CASE WHEN ? LIKE '%Z' THEN datetime(?,'localtime') ELSE ? END)";
        try (Cursor c = db.rawQuery(
                "SELECT " + custDay + " = " + txDay + " FROM customers WHERE _id = ?",
                new String[]{tanggal, tanggal, tanggal, String.valueOf(customerId)})) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    /**
     * Empty gallons returned alongside a JUAL — summed from the paired KEMBALI row(s) the sale
     * recorded for the same customer at the same tanggal. 0 if none. Matches both markers:
     *   • "Tukar botol galon"            → recorded on the device (TransactionActivity).
     *   • "Galon kembali dari penjualan…" → recorded by the web dashboard (storePending), so a
     *     web-created sale's struk also shows its returned gallons on the phone.
     */
    public int getReturnedGalonForSale(long customerId, String tanggal) {
        if (tanggal == null || tanggal.isEmpty()) return 0;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(" + DatabaseHelper.COL_JUMLAH_GALON + "), 0) FROM "
                        + DatabaseHelper.TABLE_TRANSACTIONS
                        + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + " = ?"
                        + " AND " + DatabaseHelper.COL_TYPE + " = ?"
                        + " AND " + DatabaseHelper.COL_TANGGAL + " = ?"
                        + " AND (" + DatabaseHelper.COL_CATATAN + " = 'Tukar botol galon'"
                        + "      OR " + DatabaseHelper.COL_CATATAN + " LIKE 'Galon kembali dari penjualan%')",
                new String[]{String.valueOf(customerId), Transaction.TYPE_KEMBALI, tanggal});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** Get all transactions for a specific customer, newest first */
    public List<Transaction> getByCustomerId(long customerId) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT t.*, c.name AS customer_name, c.phone AS customer_phone, p.name AS product_name " +
                "FROM transactions t " +
                "JOIN customers c ON t.customer_id = c._id " +
                "LEFT JOIN products p ON t.product_id = p._id " +
                "WHERE t.customer_id = ? " +
                "ORDER BY t.tanggal DESC";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(customerId)});
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor));
        }
        cursor.close();
        return list;
    }

    /** Get all transactions, newest first */
    public List<Transaction> getAll() {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT t.*, c.name AS customer_name, c.phone AS customer_phone, p.name AS product_name " +
                "FROM transactions t " +
                "JOIN customers c ON t.customer_id = c._id " +
                "LEFT JOIN products p ON t.product_id = p._id " +
                // Urut kronologis konsisten: edited_at (stempel sinkron UTC, tak ter-skew tz),
                // jatuh ke tanggal kalau kosong. tanggal lama hasil sinkron bisa ter-skew.
                "ORDER BY COALESCE(NULLIF(t.edited_at,''), t.tanggal) DESC";
        Cursor cursor = db.rawQuery(query, null);
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor));
        }
        cursor.close();
        return list;
    }

    /** Get recent transactions (limit) */
    public List<Transaction> getRecent(int limit) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT t.*, c.name AS customer_name, c.phone AS customer_phone, p.name AS product_name " +
                "FROM transactions t " +
                "JOIN customers c ON t.customer_id = c._id " +
                "LEFT JOIN products p ON t.product_id = p._id " +
                // Urut kronologis konsisten — lihat getAll().
                "ORDER BY COALESCE(NULLIF(t.edited_at,''), t.tanggal) DESC " +
                "LIMIT ?";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(limit)});
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor));
        }
        cursor.close();
        return list;
    }

    /** Total nilai penjualan (JUAL) untuk customer, sepanjang waktu */
    public double getTotalJualByCustomer(long customerId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COALESCE(SUM(total_harga),0) FROM transactions " +
                "WHERE type='JUAL' AND customer_id=?";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(customerId)});
        double total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getDouble(0);
        }
        cursor.close();
        return total;
    }

    /**
     * Total nilai penjualan (JUAL) untuk customer yang berkontribusi pada program
     * loyalitas. Biaya beli botol galon (harga_botol * jumlah_galon) dikurangi
     * karena menurut ketentuan, pembelian botol dan ganti rugi tidak menambah poin.
     * Transaksi KEMBALI (ganti rugi) secara otomatis sudah tidak terhitung karena
     * filter type='JUAL'.
     */
    public double getTotalJualPointsBasisByCustomer(long customerId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COALESCE(SUM(" +
                "total_harga - COALESCE(harga_botol,0) * jumlah_galon" +
                "),0) FROM transactions " +
                "WHERE type='JUAL' AND customer_id=?";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(customerId)});
        double total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getDouble(0);
        }
        cursor.close();
        return Math.max(0, total);
    }

    /** Total pendapatan hari ini */
    public double getPendapatanHariIni() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COALESCE(SUM(total_harga),0) FROM transactions " +
                "WHERE type='JUAL' AND " + localDate("tanggal") + " = date('now','localtime')";
        Cursor cursor = db.rawQuery(query, null);
        double total = 0;
        if (cursor.moveToFirst()) {
            total = cursor.getDouble(0);
        }
        cursor.close();
        return total;
    }

    /** Total transaksi hari ini */
    public int getTransaksiHariIni() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COUNT(*) FROM transactions " +
                "WHERE COALESCE(catatan,'') NOT LIKE '%[PENCAIRAN KOMISI]%' AND "
                + localDate("tanggal") + " = date('now','localtime')";
        Cursor cursor = db.rawQuery(query, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    /**
     * Semua transaksi JUAL milik satu pelanggan sejak tanggal tertentu
     * (untuk perhitungan komisi reseller). Transaksi botol-kosong
     * ([JUAL BOTOL KOSONG]) ikut ter-return — caller yang memfilter.
     */
    public List<Transaction> getJualByCustomerSince(long customerId, String since) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String where = "customer_id=? AND type='JUAL'" +
                (since != null && !since.isEmpty() ? " AND tanggal >= ?" : "");
        String[] args = since != null && !since.isEmpty()
                ? new String[]{String.valueOf(customerId), since}
                : new String[]{String.valueOf(customerId)};
        Cursor cursor = db.query(DatabaseHelper.TABLE_TRANSACTIONS, null,
                where, args, null, null, "tanggal DESC");
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor));
        }
        cursor.close();
        return list;
    }

    /**
     * Transaksi JUAL yang menjadi basis komisi seorang reseller, sejak
     * {@code since}. Mencakup dua sumber:
     * <ol>
     *   <li>Transaksi yang di-afiliasikan eksplisit ke reseller ini
     *       ({@code reseller_id = ?}) — penjualan atas rujukan reseller,
     *       walau pembelinya pelanggan lain.</li>
     *   <li>Pembelian langsung oleh reseller sendiri ({@code customer_id = ?})
     *       yang TIDAK punya afiliasi eksplisit — kompatibilitas perilaku lama.</li>
     * </ol>
     * Satu baris tidak akan dihitung dua kali (OR pada level row).
     */
    public List<Transaction> getJualForResellerSince(long resellerId, String since) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String rid = String.valueOf(resellerId);
        StringBuilder where = new StringBuilder(
                "type='JUAL' AND (" + DatabaseHelper.COL_TRX_RESELLER_ID + "=? " +
                "OR (customer_id=? AND COALESCE(" + DatabaseHelper.COL_TRX_RESELLER_ID + ",0)=0))");
        List<String> args = new ArrayList<>();
        args.add(rid);
        args.add(rid);
        if (since != null && !since.isEmpty()) {
            where.append(" AND tanggal >= ?");
            args.add(since);
        }
        Cursor cursor = db.query(DatabaseHelper.TABLE_TRANSACTIONS, null,
                where.toString(), args.toArray(new String[0]), null, null, "tanggal DESC");
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor));
        }
        cursor.close();
        return list;
    }

    /**
     * Total transaksi di bulan kalender saat ini (lokal). Dipakai untuk
     * Free tier limit (BuildConfig.FREE_MAX_TRX_PER_MONTH).
     */
    public int countThisMonth() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COUNT(*) FROM transactions " +
                "WHERE " + localMonth("tanggal") + " = strftime('%Y-%m','now','localtime')";
        Cursor cursor = db.rawQuery(query, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    /** Total galon terjual hari ini */
    public int getGalonTerjualHariIni() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COALESCE(SUM(jumlah_galon),0) FROM transactions " +
                "WHERE type='JUAL' AND COALESCE(catatan,'') NOT LIKE '%[PENCAIRAN KOMISI]%' AND "
                + localDate("tanggal") + " = date('now','localtime')";
        Cursor cursor = db.rawQuery(query, null);
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    /** Get transactions within a date range (inclusive), for export */
    public List<Transaction> getByDateRange(String startDate, String endDate) {
        List<Transaction> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT t.*, c.name AS customer_name, c.phone AS customer_phone, p.name AS product_name " +
                "FROM transactions t " +
                "JOIN customers c ON t.customer_id = c._id " +
                "LEFT JOIN products p ON t.product_id = p._id " +
                "WHERE " + localDate("t.tanggal") + " >= ? AND " + localDate("t.tanggal") + " <= ? " +
                "ORDER BY t.tanggal ASC";
        Cursor cursor = db.rawQuery(query, new String[]{startDate, endDate});
        while (cursor.moveToNext()) {
            list.add(cursorToTransaction(cursor));
        }
        cursor.close();
        return list;
    }

    /**
     * Ringkasan penjualan AIR MINUM saja dalam rentang tanggal:
     * {total_galon_air, total_harga_air} — dijumlahkan per item air minum
     * (subtotal = jumlah × harga/galon), TANPA ongkir dan TANPA jual botol galon
     * kosong. Dipakai menghitung "harga rata-rata per galon" lintas jenis air.
     */
    public double[] getWaterSalesByDateRange(String startDate, String endDate) {
        int galon = 0;
        double revenue = 0;
        for (Transaction t : getByDateRange(startDate, endDate)) {
            if (!Transaction.TYPE_JUAL.equals(t.getType())) continue;
            if (t.getCatatan() != null && (t.getCatatan().contains("[JUAL BOTOL KOSONG]")
                    || t.getCatatan().contains("[PENCAIRAN KOMISI]"))) continue;
            java.util.List<com.crowja.damiupos.model.TransactionItem> items = t.getItems();
            if (items != null && !items.isEmpty()) {
                for (com.crowja.damiupos.model.TransactionItem it : items) {
                    galon += it.jumlah;
                    revenue += it.getSubtotal();
                }
            } else {
                galon += t.getJumlahGalon();
                revenue += t.getJumlahGalon() * t.getHargaPerGalon();
            }
        }
        return new double[]{galon, revenue};
    }

    /** Summary for date range: [total_trx, total_galon_jual, total_galon_kembali, total_pendapatan] */
    public double[] getSummaryByDateRange(String startDate, String endDate) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN type='JUAL' THEN jumlah_galon ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN type='KEMBALI' THEN jumlah_galon ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN type='JUAL' THEN total_harga ELSE 0 END),0) " +
                "FROM transactions " +
                "WHERE " + localDate("tanggal") + " >= ? AND " + localDate("tanggal") + " <= ?";
        Cursor cursor = db.rawQuery(query, new String[]{startDate, endDate});
        double[] result = new double[4];
        if (cursor.moveToFirst()) {
            result[0] = cursor.getDouble(0);
            result[1] = cursor.getDouble(1);
            result[2] = cursor.getDouble(2);
            result[3] = cursor.getDouble(3);
        }
        cursor.close();
        return result;
    }

    /**
     * Get monthly sales data (last N months).
     * Returns array of [month_label, total_galon, total_pendapatan]
     */
    public List<String[]> getMonthlySales(int months) {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT " + localMonth("tanggal") + " AS bulan, " +
                "COALESCE(SUM(jumlah_galon),0) AS total_galon, " +
                "COALESCE(SUM(total_harga),0) AS total_pendapatan " +
                "FROM transactions " +
                "WHERE type='JUAL' AND COALESCE(catatan,'') NOT LIKE '%[PENCAIRAN KOMISI]%' AND "
                + localDate("tanggal") + " >= date('now','localtime','-" + months + " months') " +
                "GROUP BY bulan " +
                "ORDER BY bulan ASC";
        Cursor cursor = db.rawQuery(query, null);
        while (cursor.moveToNext()) {
            list.add(new String[]{
                    cursor.getString(0),
                    String.valueOf(cursor.getInt(1)),
                    String.valueOf(cursor.getDouble(2))
            });
        }
        cursor.close();
        return list;
    }

    /** Get daily sales for current month */
    public List<String[]> getDailySalesThisMonth() {
        List<String[]> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT strftime('%d', " + localDt("tanggal") + ") AS hari, " +
                "COALESCE(SUM(jumlah_galon),0) AS total_galon, " +
                "COALESCE(SUM(total_harga),0) AS total_pendapatan " +
                "FROM transactions " +
                "WHERE type='JUAL' AND COALESCE(catatan,'') NOT LIKE '%[PENCAIRAN KOMISI]%' AND "
                + localMonth("tanggal") + " = strftime('%Y-%m', 'now','localtime') " +
                "GROUP BY hari " +
                "ORDER BY hari ASC";
        Cursor cursor = db.rawQuery(query, null);
        while (cursor.moveToNext()) {
            list.add(new String[]{
                    cursor.getString(0),
                    String.valueOf(cursor.getInt(1)),
                    String.valueOf(cursor.getDouble(2))
            });
        }
        cursor.close();
        return list;
    }

    private Transaction cursorToTransaction(Cursor cursor) {
        Transaction t = new Transaction();
        t.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_TRX_ID)));
        t.setCustomerId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_CUSTOMER_ID)));
        int pidIdx = cursor.getColumnIndex(DatabaseHelper.COL_TRX_PRODUCT_ID);
        if (pidIdx >= 0) {
            t.setProductId(cursor.getLong(pidIdx));
        }
        t.setType(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_TYPE)));
        t.setJumlahGalon(cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_JUMLAH_GALON)));
        t.setHargaPerGalon(cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_HARGA_PER_GALON)));
        t.setTotalHarga(cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_TOTAL_HARGA)));
        int ongkirIdx = cursor.getColumnIndex(DatabaseHelper.COL_ONGKIR);
        if (ongkirIdx >= 0) {
            t.setOngkir(cursor.getDouble(ongkirIdx));
        }
        int ongkirTypeIdx = cursor.getColumnIndex(DatabaseHelper.COL_ONGKIR_TYPE);
        if (ongkirTypeIdx >= 0) {
            String ot = cursor.getString(ongkirTypeIdx);
            if (ot != null && !ot.isEmpty()) t.setOngkirType(ot);
        }
        int ownIdx = cursor.getColumnIndex(DatabaseHelper.COL_GALON_OWNERSHIP);
        if (ownIdx >= 0) {
            String own = cursor.getString(ownIdx);
            if (own != null && !own.isEmpty()) t.setGalonOwnership(own);
        }
        int hbIdx = cursor.getColumnIndex(DatabaseHelper.COL_HARGA_BOTOL);
        if (hbIdx >= 0) {
            t.setHargaBotolGalon(cursor.getDouble(hbIdx));
        }
        int payIdx = cursor.getColumnIndex(DatabaseHelper.COL_PAYMENT_METHOD);
        if (payIdx >= 0) {
            t.setPaymentMethod(cursor.getString(payIdx));
        }
        int resIdx = cursor.getColumnIndex(DatabaseHelper.COL_TRX_RESELLER_ID);
        if (resIdx >= 0) {
            t.setResellerId(cursor.getLong(resIdx));
        }
        int itemsIdx = cursor.getColumnIndex(DatabaseHelper.COL_ITEMS_JSON);
        if (itemsIdx >= 0) {
            String itemsJson = cursor.getString(itemsIdx);
            t.setItems(TransactionItem.listFromJson(itemsJson));
        }
        t.setTanggal(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_TANGGAL)));
        int editedIdx = cursor.getColumnIndex(DatabaseHelper.COL_EDITED_AT);
        if (editedIdx >= 0) t.setEditedAt(cursor.getString(editedIdx));
        t.setCatatan(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_CATATAN)));
        int nameIdx = cursor.getColumnIndex("customer_name");
        if (nameIdx >= 0) {
            t.setCustomerName(cursor.getString(nameIdx));
        }
        int phoneIdx = cursor.getColumnIndex("customer_phone");
        if (phoneIdx >= 0) {
            t.setCustomerPhone(cursor.getString(phoneIdx));
        }
        int prodNameIdx = cursor.getColumnIndex("product_name");
        if (prodNameIdx >= 0) {
            t.setProductName(cursor.getString(prodNameIdx));
        }
        int dtokIdx = cursor.getColumnIndex(DatabaseHelper.COL_DELIVERY_TOKEN);
        if (dtokIdx >= 0) {
            t.setDeliveryToken(cursor.getString(dtokIdx));
        }
        return t;
    }
}
