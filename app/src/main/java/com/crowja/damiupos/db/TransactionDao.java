package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.util.Ts;
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
        String effectiveTanggal = (trx.getTanggal() != null && !trx.getTanggal().isEmpty())
                ? trx.getTanggal() : DatabaseHelper.nowIso();
        if (trx.getTanggal() != null && !trx.getTanggal().isEmpty()) {
            values.put(DatabaseHelper.COL_TANGGAL, trx.getTanggal());
        }
        // ID transaksi unik struk (<KODE>-DDMMYY-<COUNTER>) — cermin App\Support\ReceiptNumber
        // (web). Hanya JUAL (struk pembelian; KEMBALI tak punya). Dihitung SENDIRI di sini, offline,
        // lewat counter LOKAL (SyncSettings.nextLocalReceiptSeq) — BUKAN dari COUNT baris lokal
        // transactions, karena tabel itu juga memuat order asal LAIN yang dirutekan ke perangkat ini
        // untuk pengiriman (lihat komentar K_RECEIPT_SEQ), yang akan membuat urutan meleset.
        if (Transaction.TYPE_JUAL.equals(trx.getType())) {
            com.crowja.damiupos.sync.SyncSettings receiptCfg =
                    new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(dbHelper));
            String code = receiptCfg.getReceiptCode();
            if (code == null || code.isEmpty()) code = "DEV";
            String dayKey = receiptDayKey(effectiveTanggal);
            String receiptNo = code + "-" + dayKey + "-" + receiptCfg.nextLocalReceiptSeq(dayKey);
            values.put(DatabaseHelper.COL_RECEIPT_NO, receiptNo);
            trx.setReceiptNo(receiptNo);
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
        // "Perangkat yang ditugaskan" (marketing/SPV): transaksi ini ditugaskan ke perangkat LAIN.
        // NIAT di-push (assigned_device_uuid) → server merutekan (delivery_device_uuid) + memindah
        // kredit galon/komisi ke staf yang clock-in di perangkat tujuan. Activity mengirim null saat
        // "Perangkat ini" terpilih, jadi di sini non-kosong pasti berarti perangkat lain.
        String assigned = trx.getAssignedDeviceUuid();
        boolean assignedElsewhere = Transaction.TYPE_JUAL.equals(trx.getType())
                && assigned != null && !assigned.trim().isEmpty();
        if (assignedElsewhere) {
            values.put(DatabaseHelper.COL_ASSIGNED_DEVICE_UUID, assigned.trim());
        }
        // Pesanan Tertunda: staf memilih menjadwalkan lanjut nanti (tombol ⏸, cermin web) — trx sudah
        // membawa delivery_status=TERTUNDA + jadwal lanjut sebelum insert() dipanggil (lihat
        // TransactionActivity). Berlaku APA PUN peran (termasuk marketing), sama seperti web.
        boolean tertundaRequested = Transaction.DELIVERY_TERTUNDA.equals(trx.getDeliveryStatus());
        // JUAL diantrikan sebagai pengiriman bila: Pesanan Tertunda diminta, ATAU bukan akuisisi
        // marketing di lokasi (perilaku lama), ATAU ditugaskan ke perangkat lain (agar perangkat
        // tujuan melihatnya di antrean — termasuk untuk marketing yang menugaskan ke kurir).
        if (Transaction.TYPE_JUAL.equals(trx.getType()) && (tertundaRequested || !marketing || assignedElsewhere)) {
            if (tertundaRequested) {
                // Diparkir: keluar dari antrian aktif (web & HP memfilter 'PENDING' persis), jadwal
                // lanjut otomatis WAJIB — cermin App\Support\TertundaSchedule::apply di web.
                values.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_TERTUNDA);
                values.put(DatabaseHelper.COL_DELIVERY_TERTUNDA_AT, DatabaseHelper.nowIso());
                values.put(DatabaseHelper.COL_DELIVERY_TERTUNDA_RESUME_AT, trx.getDeliveryTertundaResumeAt());
            } else {
                values.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_PENDING);
            }
            values.put(DatabaseHelper.COL_DELIVERY_QUEUED_AT, DatabaseHelper.nowIso());
            // Token link lacak publik (32 hex) → {base}/track/{token}, dikirim ke
            // pelanggan agar bisa memantau progres + lokasi kurir saat diantar.
            values.put(DatabaseHelper.COL_DELIVERY_TOKEN,
                    java.util.UUID.randomUUID().toString().replace("-", ""));
            if (assignedElsewhere) {
                // Route lokal ke perangkat tujuan → langsung hilang dari antrean perangkat ini; setelah
                // perangkat tujuan menarik (server men-set delivery_device_uuid = sama), muncul di sana.
                // JANGAN kreditkan operator perangkat ini: server menurunkan staff_uuid dari staf yang
                // clock-in di perangkat tujuan (kredit galon/komisi pindah).
                values.put(DatabaseHelper.COL_DELIVERY_DEVICE_UUID, assigned.trim());
            } else if (operatorUid > 0) {
                // Operator (kurir) = user yang sedang clock-in; disinkron sebagai staff_uuid
                // supaya halaman lacak menampilkan GPS kurir yang benar.
                values.put(DatabaseHelper.COL_DELIVERY_STAFF_ID, operatorUid);
            }
            // Lokasi tujuan pengiriman terpilih (multi-lokasi pelanggan). Hanya ditulis
            // bila di-set — 0/NULL = pakai koordinat pelanggan saat navigasi.
            if (trx.getDeliveryDestName() != null && !trx.getDeliveryDestName().isEmpty()) {
                values.put(DatabaseHelper.COL_DELIVERY_DEST_NAME, trx.getDeliveryDestName());
            }
            if (trx.getDeliveryDestLat() != 0 || trx.getDeliveryDestLng() != 0) {
                values.put(DatabaseHelper.COL_DELIVERY_DEST_LAT, trx.getDeliveryDestLat());
                values.put(DatabaseHelper.COL_DELIVERY_DEST_LNG, trx.getDeliveryDestLng());
            }
            // ⚡ Prioritas pengiriman diminta saat order DIBUAT (toggle "Tandai Prioritas" di
            // Transaksi Baru, cermin DeliveryController::prioritize di web) — ditulis SEKALI di sini;
            // perubahan/pembatalan setelahnya hanya boleh dari web (lihat SyncController::fillColumns
            // yang men-skip kolom ini pada UPDATE, bukan pada baris baru).
            if (trx.isPriorityRequested()) {
                values.put(DatabaseHelper.COL_DELIVERY_PRIORITY_AT, DatabaseHelper.nowIso());
                if (trx.getOrderPriorityReason() != null && !trx.getOrderPriorityReason().trim().isEmpty()) {
                    values.put(DatabaseHelper.COL_DELIVERY_PRIORITY_REASON, trx.getOrderPriorityReason().trim());
                }
                values.put(DatabaseHelper.COL_DELIVERY_PRIORITY_BY,
                        operator != null && !operator.isEmpty() ? operator : "HP");
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
        // Gift pelanggan: JUAL untuk pelanggan tersimpan meng-klaim SEMUA gift pending-nya —
        // redeemed_at terisi + struk ditautkan (redeemed_transaction_uuid = sync_uuid transaksi ini)
        // → di-push balik ke server & tampil di struk. Non-JUAL / walk-in (customerId≤0) = no-op.
        if (Transaction.TYPE_JUAL.equals(trx.getType()) && trx.getCustomerId() > 0) {
            String trxUuid = null;
            try (Cursor uc = db.query(DatabaseHelper.TABLE_TRANSACTIONS,
                    new String[]{DatabaseHelper.COL_SYNC_UUID},
                    DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
                if (uc.moveToFirst()) trxUuid = uc.getString(0);
            }
            new CustomerGiftDao(dbHelper).redeemForTransaction(db, trx.getCustomerId(), trxUuid, operator);
        }
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

    /** "ddMMyy" dari stempel "yyyy-MM-dd..." (lokal maupun ISO — 10 karakter awal sama bentuknya).
     *  Cermin Carbon::parse($t->tanggal)->format('dmy') di web ({@see App\Support\ReceiptNumber}). */
    private static String receiptDayKey(String tanggal) {
        if (tanggal == null || tanggal.length() < 10) return "000000";
        return tanggal.substring(8, 10) + tanggal.substring(5, 7) + tanggal.substring(2, 4);
    }

    /** sync_uuid transaksi (untuk menautkan struk ke gift yang di-klaim); null bila tak ada. */
    public String getSyncUuidById(long id) {
        if (id <= 0) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.TABLE_TRANSACTIONS,
                new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    // ----------------------------------------------------------- Antrian Delivery

    /**
     * True bila pelanggan ini DULU diakuisisi lewat promosi galon GRATIS (ada JUAL Rp0 pada hari
     * pendaftarannya) DAN transaksi sekarang adalah ORDER ULANG (hari lebih baru dari hari daftar).
     * Dipakai untuk popup "pelanggan promosi kembali order" setelah transaksi dibuat — sinyal
     * konversi promo (selaras kolom "Order Ulang" di web Promosi Marketing). Murni data lokal
     * (offline-friendly); bila transaksi akuisisinya ada di perangkat lain, popup tidak muncul.
     *
     * @param currentTanggalIso tanggal transaksi yang baru dibuat (ISO/"yyyy-MM-dd…"); null = hari ini
     */
    public boolean isRepeatFromFreePromo(long customerId, String currentTanggalIso) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String regDay = null;
        try (Cursor cc = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                new String[]{DatabaseHelper.COL_CREATED_AT},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)},
                null, null, null)) {
            if (cc.moveToFirst()) {
                String ca = cc.getString(0);
                if (ca != null && ca.length() >= 10) regDay = ca.substring(0, 10);
            }
        }
        if (regDay == null) return false;
        String today = (currentTanggalIso != null && currentTanggalIso.length() >= 10)
                ? currentTanggalIso.substring(0, 10)
                : DatabaseHelper.nowIso().substring(0, 10);
        if (today.compareTo(regDay) <= 0) return false;   // hari daftar / sebelumnya = bukan order ulang
        // Akuisisi = ada JUAL GRATIS (total 0) pada hari daftar pelanggan.
        boolean acquiredFree;
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + "=?"
                + " AND " + DatabaseHelper.COL_TYPE + "='" + Transaction.TYPE_JUAL + "'"
                + " AND substr(" + DatabaseHelper.COL_TANGGAL + ",1,10)=?"
                + " AND " + DatabaseHelper.COL_TOTAL_HARGA + "=0",
                new String[]{String.valueOf(customerId), regDay})) {
            acquiredFree = c.moveToFirst() && c.getInt(0) > 0;
        }
        if (!acquiredFree) return false;
        // Tampilkan HANYA saat ORDER ULANG PERTAMA (hari repeat paling awal = hari ini) supaya popup
        // tidak muncul berulang tiap kali pelanggan yang sudah dikonversi order lagi ke depannya.
        try (Cursor c2 = db.rawQuery(
                "SELECT MIN(substr(" + DatabaseHelper.COL_TANGGAL + ",1,10)) FROM "
                + DatabaseHelper.TABLE_TRANSACTIONS
                + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + "=?"
                + " AND " + DatabaseHelper.COL_TYPE + "='" + Transaction.TYPE_JUAL + "'"
                + " AND substr(" + DatabaseHelper.COL_TANGGAL + ",1,10) > ?",
                new String[]{String.valueOf(customerId), regDay})) {
            String minRepeat = c2.moveToFirst() ? c2.getString(0) : null;
            return today.equals(minRepeat);
        }
    }

    /**
     * Total galon promosi GRATIS yang pernah diberikan ke pelanggan ini: SUM galon JUAL Rp 0
     * pada HARI DAFTAR-nya — bentuk kohort yang sama dengan layar Pelanggan Promosi
     * ({@code CustomerDao.getPromoCustomers}); [PENCAIRAN KOMISI] (JUAL Rp0 sistem) dikecualikan.
     * MURNI DATA LOKAL per satu baris pelanggan — buta terhadap akuisisi yang tercatat di
     * perangkat lain. Untuk angka lintas perangkat (Tarik Galon Promosi) pakai
     * {@code CustomerDao.mergedPromoGalon}, yang menggabungkan ini dengan srv_promo_galon
     * (agg server) se-grup dedup.
     */
    public int getFreePromoGalon(long customerId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(t." + DatabaseHelper.COL_JUMLAH_GALON + "),0)"
                + " FROM " + DatabaseHelper.TABLE_TRANSACTIONS + " t"
                + " JOIN " + DatabaseHelper.TABLE_CUSTOMERS + " c ON c." + DatabaseHelper.COL_ID
                + "=t." + DatabaseHelper.COL_CUSTOMER_ID
                + " WHERE t." + DatabaseHelper.COL_CUSTOMER_ID + "=?"
                + " AND t." + DatabaseHelper.COL_TYPE + "='" + Transaction.TYPE_JUAL + "'"
                + " AND t." + DatabaseHelper.COL_TOTAL_HARGA + "=0"
                + " AND substr(t." + DatabaseHelper.COL_TANGGAL + ",1,10)=substr(c." + DatabaseHelper.COL_CREATED_AT + ",1,10)"
                + " AND COALESCE(t." + DatabaseHelper.COL_CATATAN + ",'') NOT LIKE '%[PENCAIRAN KOMISI]%'",
                new String[]{String.valueOf(customerId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Marker catatan KEMBALI hasil penarikan galon promosi (ditulis TarikGalon untuk bagian yang membebani jatah promo). Aman untuk
     *  SQLite LIKE: '[' bukan wildcard (hanya % dan _), dan marker tak mengandung keduanya. */
    public static final String PROMO_PULL_MARKER = "[TARIK GALON PROMOSI]";

    /** Total galon promosi yang SUDAH DITARIK kembali dari pelanggan ini
     *  (KEMBALI bermarker {@link #PROMO_PULL_MARKER}). */
    public int getPromoPulledGalon(long customerId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT COALESCE(SUM(" + DatabaseHelper.COL_JUMLAH_GALON + "),0)"
                + " FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + "=?"
                + " AND " + DatabaseHelper.COL_TYPE + "='" + Transaction.TYPE_KEMBALI + "'"
                + " AND COALESCE(" + DatabaseHelper.COL_CATATAN + ",'') LIKE '%" + PROMO_PULL_MARKER + "%'",
                new String[]{String.valueOf(customerId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Jumlah order yang masih di antrian (PENDING) — untuk badge dashboard. */
    /** Transaksi yang BELUM terkonfirmasi tersinkron ke server (synced=0) dan lebih tua dari
     *  {@code minAgeMinutes} menit — dipakai untuk peringatan "N transaksi belum tersinkron" di
     *  layar utama. Ambang usia menghindari alarm palsu untuk transaksi yang baru dibuat dan wajar
     *  masih antre sync. Hanya transaksi (bukan tombstone) — yang penting muncul di dashboard. */
    public int countUnsynced(int minAgeMinutes) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                        + " WHERE " + DatabaseHelper.COL_SYNCED + "=0"
                        + " AND " + DatabaseHelper.COL_EDITED_AT + " <= datetime('now','-" + minAgeMinutes + " minutes')",
                null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

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
                + "c." + DatabaseHelper.COL_LONGITUDE + " AS cust_lng, "
                // Foto rumah (lokal ATAU terunggah) — untuk tanda "data belum lengkap" di kartu antrian.
                + "c." + DatabaseHelper.COL_PHOTO_URL + " AS cust_photo, "
                + "c." + DatabaseHelper.COL_PHOTO_PATH + " AS cust_photo_path, "
                // Pelanggan PRIORITAS aktif (cermin Customer.isPriority): ditandai & belum dibatalkan.
                // Dinormalkan ke waktu lokal dulu: priority_at kerap ISO-UTC dari server sedangkan
                // priority_cleared_at ditulis HP dalam waktu lokal ({@link Ts#localExpr}).
                + "(CASE WHEN c." + DatabaseHelper.COL_PRIORITY_AT + " IS NOT NULL AND (c."
                + DatabaseHelper.COL_PRIORITY_CLEARED_AT + " IS NULL OR "
                + Ts.localExpr("c." + DatabaseHelper.COL_PRIORITY_CLEARED_AT) + " < "
                + Ts.localExpr("c." + DatabaseHelper.COL_PRIORITY_AT)
                + ") THEN 1 ELSE 0 END) AS cust_priority, "
                // ⚡ Prioritas PENGIRIMAN ini saja (ditandai operator web) — turunan untuk ORDER BY.
                + "(CASE WHEN t." + DatabaseHelper.COL_DELIVERY_PRIORITY_AT + " IS NOT NULL AND t."
                + DatabaseHelper.COL_DELIVERY_PRIORITY_AT + " <> '' THEN 1 ELSE 0 END) AS ord_priority "
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
                // Urutan antrian, dari kunci terkuat (cermin Reports::deliveryList di web):
                //  1) order "AMBIL GALON SAJA" (transaksi KEMBALI dijemput kurir) SELALU paling bawah —
                //     tak ada barang yang diantar, jadi ia tak boleh menyerobot pelanggan yang menunggu
                //     air; ini mengalahkan KEDUA jenis prioritas;
                //  2) ⭐ pelanggan prioritas — sengaja DI ATAS ⚡ (keputusan owner 2026-07-27);
                //  3) ⚡ prioritas pengiriman yang ditandai operator di web;
                //  4) di dalam tiap grup, urutan antrian (FIFO) dijaga.
                + "ORDER BY (CASE WHEN t." + DatabaseHelper.COL_TYPE + "='" + Transaction.TYPE_KEMBALI
                + "' THEN 1 ELSE 0 END) ASC, cust_priority DESC, ord_priority DESC, t."
                + DatabaseHelper.COL_DELIVERY_QUEUED_AT + " ASC";
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
                t.setDeliveryTertundaAt(getStr(c, DatabaseHelper.COL_DELIVERY_TERTUNDA_AT));
                t.setDeliveryTertundaResumeAt(getStr(c, DatabaseHelper.COL_DELIVERY_TERTUNDA_RESUME_AT));
                // "Pesanan Terbuka" MASIH terbuka = kolom terisi DAN belum diklaim (delivery_device_uuid
                // masih kosong) — kolom mentahnya sendiri PERMANEN (server tak pernah meng-null-kannya
                // lagi setelah diklaim, lihat Reports::resumeDueTertunda di web), jadi cek dua-duanya di
                // sini persis seperti shapeQueueRow di web (kolom mentah ADA di cursor via SELECT t.*,
                // tapi TIDAK dipetakan ke field Java tersendiri — hanya dipakai sesaat di sini).
                String openAt = getStr(c, DatabaseHelper.COL_DELIVERY_OPEN_DISPATCH_AT);
                String routedTo = getStr(c, DatabaseHelper.COL_DELIVERY_DEVICE_UUID);
                t.setDeliveryOpenDispatchAt((openAt != null && routedTo == null) ? openAt : null);
                // Badge ✏️/🗑️ "sudah pernah diubah" — server-authoritative, dibaca apa adanya.
                t.setLastManualEditAt(getStr(c, DatabaseHelper.COL_LAST_MANUAL_EDIT_AT));
                t.setVoidRequestPendingAt(getStr(c, DatabaseHelper.COL_VOID_REQUEST_PENDING_AT));
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
                t.setCustomerPriority(getLong(c, "cust_priority") == 1);   // badge ⭐ di kartu antrian
                // ⚡ Prioritas pengiriman ini saja (ditandai operator di web) + alasannya.
                // Urutan manual dari Strategi Pengiriman (disusun di web) — dipakai DeliveryPlanner.
                t.setDeliverySeq((int) getLong(c, DatabaseHelper.COL_DELIVERY_SEQ));
                t.setOrderPriorityAt(getStr(c, DatabaseHelper.COL_DELIVERY_PRIORITY_AT));
                t.setOrderPriorityReason(getStr(c, DatabaseHelper.COL_DELIVERY_PRIORITY_REASON));
                t.setOrderPriorityBy(getStr(c, DatabaseHelper.COL_DELIVERY_PRIORITY_BY));
                // Data pelanggan tujuan belum lengkap (foto ATAU koordinat) → kartu antrian ditandai ❗
                // + berkedip. Umum / tanpa customer_id dikecualikan (memang tak berkoordinat/foto).
                String custPhoto = getStr(c, "cust_photo");
                String custPhotoPath = getStr(c, "cust_photo_path");
                boolean hasPhoto = (custPhoto != null && !custPhoto.isEmpty())
                        || (custPhotoPath != null && !custPhotoPath.isEmpty());
                boolean hasCoord = t.getCustomerLat() != 0 || t.getCustomerLng() != 0;
                boolean realCust = t.getCustomerId() > 0
                        && !CustomerDao.UMUM_NAME.equalsIgnoreCase(t.getCustomerName());
                t.setCustomerNoPhoto(realCust && !hasPhoto);
                t.setCustomerNoCoord(realCust && !hasCoord);
                list.add(t);
            }
        }
        return list;
    }

    /** Tandai order Selesai: status DONE + waktu selesai = sekarang (durasi terhitung). */
    public void markDelivered(long trxId) {
        markDelivered(trxId, false);
    }

    /**
     * Seperti {@link #markDelivered(long)}, tapi bila {@code revokeCredit} true (setting cabang aktif
     * & data pelanggan tak lengkap saat Selesaikan) juga menstempel credit_grace_until =
     * sekarang + 24 jam. Disinkron ke server; server yang mengeluarkan galonnya dari kredit staf
     * setelah tenggang lewat & data masih belum lengkap.
     */
    public void markDelivered(long trxId, boolean revokeCredit) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_DONE);
        v.put(DatabaseHelper.COL_DELIVERY_DONE_AT, DatabaseHelper.nowIso());
        // Atribusi penyelesai: kurir yang menandai Selesai (bisa ≠ pembuat order, mis.
        // order disusun admin di web lalu diantar staf ini). Disinkron ke dashboard.
        SettingsDao sdao = new SettingsDao(dbHelper);
        String courier = sdao.getCurrentUserName();
        if (courier != null && !courier.isEmpty()) {
            v.put(DatabaseHelper.COL_COMPLETED_BY_NAME, courier);
        }
        // KREDIT GALON: bila order belum ber-kurir (delivery_staff_id=0, mis. disusun di web saat tak
        // ada yang absen di perangkat) & JUAL, stempel KURIR yang menyelesaikan sebagai delivery_staff_id
        // → disinkron jadi staff_uuid (Ref SyncEngine). Poin penjualan galon jatuh ke staf yang benar-
        // benar mengantar (identitas login HP = kebenaran, walau lupa absen), bukan operator web.
        // Cermin aturan PEMBUATAN: hanya JUAL, non-marketing, user tersinkron; TAK menimpa kredit
        // yang sudah ada (delivery_staff_id>0 = penjual asli). Server tak menimpa (staff_uuid sudah terisi).
        long uid = sdao.getCurrentUserId();
        if (uid > 0) {
            long existingStaff = -1;
            String type = null;
            Cursor c = db.query(DatabaseHelper.TABLE_TRANSACTIONS,
                    new String[]{DatabaseHelper.COL_DELIVERY_STAFF_ID, DatabaseHelper.COL_TYPE},
                    DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)}, null, null, null, "1");
            if (c.moveToFirst()) {
                existingStaff = c.getLong(0);
                type = c.getString(1);
            }
            c.close();
            if (existingStaff <= 0 && Transaction.TYPE_JUAL.equals(type)) {
                UserDao userDao = new UserDao(dbHelper);
                User op = userDao.getById(uid);
                String staffUuid = userDao.getSyncUuidById(uid);   // null = belum tersinkron → jangan atribusi
                if ((op == null || !op.isMarketing()) && staffUuid != null && !staffUuid.isEmpty()) {
                    v.put(DatabaseHelper.COL_DELIVERY_STAFF_ID, uid);
                }
            }
        }
        if (revokeCredit) {
            v.put(DatabaseHelper.COL_CREDIT_GRACE_UNTIL,
                    DatabaseHelper.isoFrom(System.currentTimeMillis() + 24L * 60 * 60 * 1000));
        }
        // Order selesai → stempel "sedang dikerjakan" ikut dimatikan, supaya dashboard tak menyorot
        // order yang sudah kelar (dan tak menyisakan stempel basi bila nanti dibuka lagi).
        v.put(DatabaseHelper.COL_DELIVERY_STARTED_CLEARED_AT, DatabaseHelper.nowIso());
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /**
     * ▶ JALANKAN pengiriman ini: stempel "sedang dikerjakan" (dashboard menyorot kartunya). Ditulis
     * lewat syncUpdate → baris jadi kotor & terdorong ke server pada sinkron berikutnya.
     *
     * <p>Berhenti ditulis sebagai stempel TERSENDIRI ({@link #stopDelivery}), bukan meng-NULL-kan
     * kolom ini: push HP MEMBUANG kolom bernilai null dari payload, jadi pembatalan yang ditulis
     * sebagai NULL tak akan pernah sampai ke server. Cermin priority_at/priority_cleared_at.</p>
     */
    public void startDelivery(long trxId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DELIVERY_STARTED_AT, DatabaseHelper.nowIso());
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /**
     * Simpan path lokal foto BUKTI SELESAI (delivery_proof_required). Update POLOS — tanpa bump
     * edited_at/synced: photo_path lokal-saja (tak pernah di-push; MediaUploader membacanya untuk
     * unggah, lalu menstempel photo_url + synced=0 sendiri). markDelivered yang menyusul sudah
     * men-dirty baris utk push status Selesai.
     */
    public void setDeliveryProofPath(long trxId, String path) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_PHOTO_PATH, path);
        db.update(DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /** Berhenti mengerjakan order ini (tombol Kembali) — stempel penutup; lihat {@link #startDelivery}. */
    public void stopDelivery(long trxId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DELIVERY_STARTED_CLEARED_AT, DatabaseHelper.nowIso());
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /**
     * Cerminkan klaim "Pesanan Terbuka" (POST /api/delivery/claim) yang BARU SAJA sukses ke baris
     * LOKAL, SEGERA — server yang menyimpan klaim itu sendiri, jadi ini murni cache optimistik supaya
     * order langsung terlihat "milik perangkat ini" (isOpenDispatch()==false, ikut getDeliveryQueue()
     * lewat cabang "= perangkat ini") tanpa menunggu siklus sinkron berikutnya. delivery_device_uuid
     * SERVER-AUTHORITATIVE (di-skip dari push HP) jadi menulisnya lokal di sini aman — pull berikutnya
     * cuma menegaskan ulang nilai yang sama (atau mengoreksi bila klaim ternyata gagal di sela waktu).
     */
    public void markClaimedLocally(long trxId, String deviceUuid) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DELIVERY_DEVICE_UUID, deviceUuid);
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
        // Penjualannya batal → piutang yang lahir dari penjualan itu ikut batal. Tombstone (bukan
        // hard delete) supaya penghapusannya menyebar ke server & perangkat lain; baris PEMBAYARAN
        // tak disentuh (cicilan yang terlanjur diterima tetap fakta, sisa hutang jatuh ke 0 sendiri).
        new CustomerDebtDao(dbHelper).voidForTransaction(getSyncUuidById(id));
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_TRANSACTIONS, "transactions",
                DatabaseHelper.COL_TRX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Timpa catatan transaksi (dipakai untuk menempelkan penanda seperti "[BAYAR HUTANG Rp ...]"
     * SESUDAH barisnya tersimpan — nominal yang benar-benar tercatat baru diketahui setelah DAO
     * memagarinya terhadap sisa hutang). syncUpdate bump edited_at + synced=0 -> LWW ke server.
     */
    public int updateCatatan(long trxId, String catatan) {
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_CATATAN, catatan != null ? catatan : "");

        return dbHelper.syncUpdate(dbHelper.getWritableDatabase(), DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
    }

    /** Edit terbatas (staf operator): ubah metode pembayaran transaksi JUAL. syncUpdate bump edited_at
     *  + synced=0 → LWW ke server & perangkat lain. Nominal/total tidak berubah. */
    /**
     * KOREKSI LAPANGAN saat menyelesaikan order ("Ubah" pada popup konfirmasi Selesai): produk &
     * jumlah aktual, galon kembali, metode bayar, dan Cash Bon. Cermin
     * {@code App\Support\DeliveryFinalize} di web.
     *
     * <p>Total DIHITUNG ULANG dari baris item + ongkir yang sudah tersimpan — angka dari form tak
     * dipercaya. Transaksi KEMBALI berpasangan diselaraskan ke {@code returnQty} memakai aturan
     * pencocokan yang sama dengan {@link #getReturnedGalonForSale} (pelanggan + tanggal PERSIS +
     * penanda catatan), jadi tanggalnya sengaja TIDAK digeser.</p>
     *
     * <p>CASH BON = pintasan "Jumlah Dibayar" = 0 (uang belum diterima sama sekali).</p>
     *
     * <p><b>Jumlah Dibayar (bayar sebagian).</b> {@code paidAmount} boleh {@code null} (jalur lama:
     * HUTANG eksplisit di dropdown = piutang penuh) atau berupa nominal yang BENAR-BENAR diterima
     * sekarang. Bila kurang dari total tagihan — sedikit ATAU nol — selisihnya otomatis dihitung
     * ulang jadi piutang lewat {@link CustomerDebtDao}, dengan nominal SELISIHNYA saja (bukan selalu
     * total penuh). Bagian yang benar-benar diterima tetap tercatat pada metode bayar pilihan
     * operator; total_harga TIDAK dikurangi (piutang adalah cara catat, bukan diskon). Cermin
     * {@code App\Support\DeliveryFinalize} di server.</p>
     *
     * @return jumlah baris transaksi yang berubah (0 = transaksi tak ditemukan)
     */
    public int applyDeliveryAdjustment(long trxId, java.util.List<TransactionItem> items, int returnQty,
                                       String paymentMethod, boolean cashBon, Double paidAmount,
                                       String cashBonReason, String byName) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        Transaction t = getById(trxId);
        if (t == null) return 0;

        if (items == null || items.isEmpty()) items = t.getItems();
        int galon = 0;
        double itemTotal = 0;
        if (items != null) {
            for (TransactionItem it : items) {
                if (it == null || it.jumlah <= 0) continue;
                galon += it.jumlah;
                itemTotal += it.getSubtotal();
            }
        }
        // Ongkir Per Galon dikalikan jumlah galon; borongan datar. Aturannya sama dgn saat transaksi
        // dibuat — kalau tidak, mengoreksi jumlah galon diam-diam salah menagih ongkirnya.
        double ongkir = Transaction.ONGKIR_PER_GALON.equals(t.getOngkirType())
                ? t.getOngkir() * galon : t.getOngkir();
        double total = itemTotal + ongkir;

        // CASH BON = pintasan "diterima Rp0". Bila paidAmount tak dikirim sama sekali (jalur lama),
        // jatuh ke perilaku HUTANG-eksplisit: piutang penuh hanya bila dropdown pembayarannya HUTANG.
        Double received = cashBon ? Double.valueOf(0.0) : paidAmount;
        String payment = paymentMethod;
        // HUTANG SEBELUMNYA — tagihan di pintu = penjualan ini + piutang lama, persis seperti yang
        // dicetak di struk. Baris milik transaksi INI dikecualikan supaya tak terhitung dua kali &
        // supaya penekanan "Selesai" berulang menghasilkan angka yang sama.
        CustomerDebtDao debtDao = new CustomerDebtDao(dbHelper);
        String trxUuid = getSyncUuidById(trxId);
        double priorDebt = debtDao.balanceExcludingTransaction(t.getCustomerId(), trxUuid);
        double expected = Math.round((total + priorDebt) * 100d) / 100d;
        double owed;
        double toOldDebt = 0;
        if (received != null) {
            double r = Math.max(0, received);
            // ALOKASI: penjualan HARI INI dilunasi lebih dulu, sisanya baru menutup hutang lama —
            // urutan yang sama dengan cara orang membayar di pintu.
            double toSale = Math.min(r, total);
            owed = Math.max(0, Math.round((total - toSale) * 100d) / 100d);
            toOldDebt = Math.min(Math.round(Math.max(0, r - toSale) * 100d) / 100d, priorDebt);
            if (r <= 0) payment = Transaction.PAY_HUTANG;
        } else {
            owed = Transaction.PAY_HUTANG.equals(payment) ? total : 0.0;
        }

        String note = t.getCatatan() != null ? t.getCatatan() : "";
        // Penanda ditulis bila yang diterima KURANG dari tagihan di pintu (termasuk hutang lama).
        if (received != null && received < expected
                && !note.contains(CASH_BON_MARKER) && !note.contains(PARTIAL_PAYMENT_MARKER)) {
            if (received <= 0) {
                String reason = cashBonReason != null ? cashBonReason.trim() : "";
                note = (note.isEmpty() ? "" : note + "\n") + CASH_BON_MARKER
                        + (reason.isEmpty() ? "" : " " + reason);
            } else {
                note = (note.isEmpty() ? "" : note + "\n") + PARTIAL_PAYMENT_MARKER
                        + java.text.NumberFormat.getNumberInstance(new java.util.Locale("in", "ID"))
                                .format(Math.round(received)) + "]";
            }
        }

        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_ITEMS_JSON, TransactionItem.listToJson(items));
        v.put(DatabaseHelper.COL_JUMLAH_GALON, galon);
        v.put(DatabaseHelper.COL_TOTAL_HARGA, total);
        if (payment != null && !payment.isEmpty()) v.put(DatabaseHelper.COL_PAYMENT_METHOD, payment);
        v.put(DatabaseHelper.COL_CATATAN, note);
        // Penanda "pernah diubah manual" → badge di antrian web & HP, sama seperti Edit Transaksi.
        v.put(DatabaseHelper.COL_LAST_MANUAL_EDIT_AT, DatabaseHelper.nowIso());
        int n = dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});

        syncPairedReturn(db, t, returnQty);

        if (received != null) {
            // Nominal yang BENAR — bisa lebih kecil dari total (bayar sebagian), bukan selalu penuh.
            debtDao.syncForTransaction(t.getCustomerId(), trxUuid,
                    owed > 0 ? Transaction.PAY_HUTANG : Transaction.PAY_TUNAI, owed, byName);
            // Kelebihan uang di atas tagihan hari ini = pelunasan HUTANG LAMA. Disetel (bukan
            // ditambah) supaya Selesai yang ditekan berulang tidak menumpuk pembayaran.
            debtDao.syncPaymentForTransaction(t.getCustomerId(), trxUuid, toOldDebt, byName,
                    "Pelunasan hutang lama saat Selesai");
        } else {
            // Jalur lama: `payment` sudah dipaksa HUTANG di atas bila dropdown-nya HUTANG, jadi satu
            // panggilan cukup — syncForTransaction sendiri yang membuat/memperbarui/membuang baris.
            debtDao.syncForTransaction(t.getCustomerId(), trxUuid, payment, total, byName);
        }
        return n;
    }

    /** Penanda catatan Cash Bon — harus sama persis dgn App\Support\DeliveryFinalize di server. */
    public static final String CASH_BON_MARKER = "[CASH BON]";

    /**
     * Alasan Cash Bon "pelanggan sedang tidak di tempat" — galon ditinggal, uang tak bisa diterima
     * karena orangnya tak ada. Beda tajam dari "tidak memberikan uang" (orangnya ADA tapi menolak/
     * menunda bayar), jadi penanganannya beda: alasan ini MEWAJIBKAN foto bukti pengiriman lalu
     * memberitahu pelanggan lewat WA. Cermin {@code App\Support\DeliveryFinalize::CASH_BON_REASON_AWAY};
     * string-nya disimpan apa adanya di ekor {@link #CASH_BON_MARKER} pada catatan dan dibaca ulang
     * dari sana untuk mengenali kasus ini.
     */
    public static final String CASH_BON_REASON_AWAY = "Konsumen tidak ada di tempat";

    /** Orangnya ADA, uangnya belum diserahkan (dulu "Konsumen tidak memberikan uang" — diperhalus:
     *  yang dicatat adalah FAKTA uangnya belum masuk, bukan tuduhan ke pelanggan). */
    public static final String CASH_BON_REASON_UNPAID = "Uang belum diterima";

    /** Alasan di luar dua di atas — WAJIB disertai penjelasan bebas yang ikut ditulis ke catatan. */
    public static final String CASH_BON_REASON_OTHER = "Lainnya";

    /** Penanda catatan bayar sebagian — harus sama persis dgn App\Support\DeliveryFinalize di server. */
    public static final String PARTIAL_PAYMENT_MARKER = "[BAYAR SEBAGIAN Rp ";

    /**
     * Selaraskan transaksi KEMBALI berpasangan sebuah JUAL ke {@code qty}: ubah baris pertama yang
     * cocok, buat bila belum ada, atau hapus (tombstone) bila qty jadi 0. Baris berpasangan LAIN
     * tak diutak-atik — tanpa kunci JUAL→KEMBALI eksplisit, menghapusnya bisa menghilangkan
     * pengembalian penjualan lain yang kebetulan setanggal & sepelanggan (cermin alasan yang sama
     * di TransactionController::reconcilePairedReturn pada web).
     */
    private void syncPairedReturn(SQLiteDatabase db, Transaction jual, int qty) {
        if (!Transaction.TYPE_JUAL.equals(jual.getType()) || jual.getCustomerId() <= 0) return;
        String tanggal = jual.getTanggal();
        if (tanggal == null || tanggal.isEmpty()) return;
        qty = Math.max(0, qty);

        String where = DatabaseHelper.COL_CUSTOMER_ID + "=? AND " + DatabaseHelper.COL_TYPE + "=?"
                + " AND " + DatabaseHelper.COL_TANGGAL + "=?"
                + " AND (" + DatabaseHelper.COL_CATATAN + " = 'Tukar botol galon'"
                + "      OR " + DatabaseHelper.COL_CATATAN + " LIKE 'Galon kembali dari penjualan%')";
        String[] args = new String[]{String.valueOf(jual.getCustomerId()), Transaction.TYPE_KEMBALI, tanggal};

        long existingId = -1;
        Cursor c = db.query(DatabaseHelper.TABLE_TRANSACTIONS, new String[]{DatabaseHelper.COL_TRX_ID},
                where, args, null, null, DatabaseHelper.COL_TRX_ID + " ASC", "1");
        try {
            if (c.moveToFirst()) existingId = c.getLong(0);
        } finally {
            c.close();
        }

        if (qty <= 0) {
            if (existingId > 0) delete(existingId);
            return;
        }
        if (existingId > 0) {
            ContentValues kv = new ContentValues();
            kv.put(DatabaseHelper.COL_JUMLAH_GALON, qty);
            dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, kv,
                    DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(existingId)});
            return;
        }
        Transaction kembali = new Transaction();
        kembali.setCustomerId(jual.getCustomerId());
        kembali.setType(Transaction.TYPE_KEMBALI);
        kembali.setJumlahGalon(qty);
        kembali.setCatatan("Tukar botol galon");
        kembali.setTanggal(tanggal);
        insert(kembali);
    }

    public int updatePaymentMethod(long trxId, String method) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_PAYMENT_METHOD, method);
        int n = dbHelper.syncUpdate(db, DatabaseHelper.TABLE_TRANSACTIONS, v,
                DatabaseHelper.COL_TRX_ID + "=?", new String[]{String.valueOf(trxId)});
        // Ganti metode bayar ke/dari "HUTANG" mengubah piutang → selaraskan buku besarnya, kalau
        // tidak edit akan meninggalkan piutang hantu (atau menagih penjualan yang sudah dibayar).
        syncDebtForTransaction(db, trxId, method);
        return n;
    }

    /** Baca pelanggan + total transaksi lalu serahkan penyelarasan ke CustomerDebtDao. */
    private void syncDebtForTransaction(SQLiteDatabase db, long trxId, String method) {
        long customerId = -1;
        double total = 0;
        Cursor c = db.rawQuery("SELECT " + DatabaseHelper.COL_CUSTOMER_ID + ", "
                        + DatabaseHelper.COL_TOTAL_HARGA + " FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                        + " WHERE " + DatabaseHelper.COL_TRX_ID + "=?",
                new String[]{String.valueOf(trxId)});
        try {
            if (c.moveToFirst()) {
                customerId = c.getLong(0);
                total = c.getDouble(1);
            }
        } finally {
            c.close();
        }
        new CustomerDebtDao(dbHelper).syncForTransaction(customerId, getSyncUuidById(trxId),
                method, total, null);
    }

    /** Edit terbatas (staf operator): PINDAHKAN transaksi ke pelanggan lain (ubah "kolom nama
     *  pelanggan"). Cukup ganti customer_id lokal — SyncEngine punya Ref(customer_id → customer_uuid)
     *  yang otomatis me-resolve uuid pelanggan baru saat push, jadi dashboard & perangkat lain ikut
     *  ter-update. syncUpdate bump edited_at + synced=0 → LWW. Nominal/total tidak berubah. */
    public int updateCustomerId(long trxId, long newCustomerId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_CUSTOMER_ID, newCustomerId);
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

    /**
     * Terapkan gift PRODUK ke transaksi JUAL yang baru dibuat (dipilih staf di popup struk):
     * simpan ulang daftar item, total, dan jumlah galon sekaligus. syncUpdate bump edited_at +
     * synced=0 → menang LWW dan menyusul ke dashboard. Cermin Gifts::applyProductGift di server.
     */
    public int applyGiftItems(long trxId, java.util.List<com.crowja.damiupos.model.TransactionItem> items,
                              double totalHarga, int jumlahGalon) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_ITEMS_JSON,
                com.crowja.damiupos.model.TransactionItem.listToJson(items));
        v.put(DatabaseHelper.COL_TOTAL_HARGA, totalHarga);
        v.put(DatabaseHelper.COL_JUMLAH_GALON, jumlahGalon);
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

    /** Info transaksi JUAL terbaru pelanggan pada hari kalender LOKAL ini (untuk gerbang
     *  konfirmasi anti-order-ganda). {@link #deliveryStatus} null/kosong = tidak ada
     *  status delivery (transaksi tunai langsung, dsb). */
    public static class TodayOrderInfo {
        public final String tanggal;
        public final String deliveryStatus;
        public TodayOrderInfo(String tanggal, String deliveryStatus) {
            this.tanggal = tanggal;
            this.deliveryStatus = deliveryStatus;
        }
    }

    /**
     * Transaksi JUAL pelanggan ini yang jatuh pada hari kalender LOKAL ini (lihat {@link #localDate}
     * untuk normalisasi campuran UTC-ISO/lokal), diambil yang PALING BARU. Null bila belum ada JUAL
     * untuk pelanggan ini hari ini. Dipakai gerbang "sudah order hari ini?" sebelum Transaksi Baru.
     */
    public TodayOrderInfo getTodayOrderInfo(long customerId) {
        if (customerId <= 0) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT " + DatabaseHelper.COL_TANGGAL + ", " + DatabaseHelper.COL_DELIVERY_STATUS +
                " FROM " + DatabaseHelper.TABLE_TRANSACTIONS +
                " WHERE type='JUAL' AND " + DatabaseHelper.COL_CUSTOMER_ID + "=?" +
                " AND " + localDate(DatabaseHelper.COL_TANGGAL) + " = date('now','localtime')" +
                " ORDER BY " + localDt(DatabaseHelper.COL_TANGGAL) + " DESC LIMIT 1";
        try (Cursor c = db.rawQuery(sql, new String[]{String.valueOf(customerId)})) {
            if (!c.moveToFirst()) return null;
            return new TodayOrderInfo(getStr(c, DatabaseHelper.COL_TANGGAL), getStr(c, DatabaseHelper.COL_DELIVERY_STATUS));
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

    /**
     * Nama produk (jumlah &gt; 0) dari pembelian (JUAL) TERAKHIR pelanggan ini — dipakai Transaksi
     * Baru untuk mengedipkan baris produk yang sama begitu pelanggan dipilih (cermin fitur web
     * "last item blink"; lihat CustomerController::lastPurchasedProductUuids). Transaksi Tertunda
     * dilewati: kalau transaksi terakhirnya Tertunda, yang relevan tetap pembelian terakhir yang
     * SUDAH terjadi. Dicocokkan lewat NAMA (bukan pid) oleh pemanggil — pid device-local, produk
     * bisa direname sejak transaksi lama disimpan.
     */
    public List<String> getLastJualProductNames(long customerId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT items_json FROM transactions " +
                "WHERE type='JUAL' AND customer_id=? " +
                "AND (delivery_status IS NULL OR delivery_status != ?) " +
                "ORDER BY tanggal DESC, _id DESC LIMIT 1";
        Cursor cursor = db.rawQuery(query, new String[]{
                String.valueOf(customerId), Transaction.DELIVERY_TERTUNDA});
        List<String> names = new ArrayList<>();
        if (cursor.moveToFirst()) {
            String itemsJson = cursor.getString(0);
            if (itemsJson != null) {
                for (TransactionItem it : TransactionItem.listFromJson(itemsJson)) {
                    if (it.jumlah > 0 && it.productName != null && !it.productName.isEmpty()) {
                        names.add(it.productName);
                    }
                }
            }
        }
        cursor.close();
        return names;
    }

    /**
     * Peta nama lokasi tujuan (delivery_dest_name persis) → jumlah transaksi JUAL yang dikirim ke
     * sana, untuk badge "berapa kali" pada kartu/picker "Kirim ke" di Transaksi Baru. Transaksi
     * tanpa nama tujuan tersimpan (transaksi lama sebelum multi-lokasi, atau non-antar) dihitung
     * TERPISAH lewat kunci {@link #UNNAMED_DEST_KEY} — caller yang menambahkannya ke hitungan
     * lokasi UTAMA (indeks 0), karena sebelum multi-lokasi ada, semua pengiriman memang ke sana.
     */
    public static final String UNNAMED_DEST_KEY = "";

    public java.util.Map<String, Integer> countJualByDeliveryDestName(long customerId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        String query = "SELECT COALESCE(delivery_dest_name,''), COUNT(*) FROM transactions " +
                "WHERE type='JUAL' AND customer_id=? GROUP BY COALESCE(delivery_dest_name,'')";
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(customerId)});
        while (cursor.moveToNext()) {
            map.put(cursor.getString(0), cursor.getInt(1));
        }
        cursor.close();
        return map;
    }

    /** JUAL yang SUDAH SELESAI (bukan masih menunggu diantar/PENDING atau diparkir/TERTUNDA) —
     *  penjualan tunai di tempat (delivery_status NULL) atau pengiriman yang sudah DONE. */
    private static final String WHERE_COMPLETED_JUAL =
            "(delivery_status IS NULL OR delivery_status = 'DONE')";

    /** Total pendapatan hari ini — hanya transaksi yang SUDAH SELESAI (belum diantar belum terjual). */
    public double getPendapatanHariIni() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COALESCE(SUM(total_harga),0) FROM transactions " +
                "WHERE type='JUAL' AND " + WHERE_COMPLETED_JUAL + " AND "
                + localDate("tanggal") + " = date('now','localtime')";
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

    /** Total galon terjual hari ini — hanya transaksi yang SUDAH SELESAI (belum diantar belum terjual). */
    public int getGalonTerjualHariIni() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COALESCE(SUM(jumlah_galon),0) FROM transactions " +
                "WHERE type='JUAL' AND COALESCE(catatan,'') NOT LIKE '%[PENCAIRAN KOMISI]%' AND "
                + WHERE_COMPLETED_JUAL + " AND "
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
            // Belum diantar (PENDING) atau ditunda (TERTUNDA) → belum benar-benar "terjual".
            String ds = t.getDeliveryStatus();
            if (ds != null && !Transaction.DELIVERY_DONE.equals(ds)) continue;
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

    /** Summary for date range: [total_trx, total_galon_jual, total_galon_kembali, total_pendapatan]
     *  — hanya transaksi yang SUDAH SELESAI (KEMBALI tak berdelivery_status jadi tak terpengaruh). */
    public double[] getSummaryByDateRange(String startDate, String endDate) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT COUNT(*), " +
                "COALESCE(SUM(CASE WHEN type='JUAL' THEN jumlah_galon ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN type='KEMBALI' THEN jumlah_galon ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN type='JUAL' THEN total_harga ELSE 0 END),0) " +
                "FROM transactions " +
                "WHERE " + WHERE_COMPLETED_JUAL + " AND "
                + localDate("tanggal") + " >= ? AND " + localDate("tanggal") + " <= ?";
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
                + WHERE_COMPLETED_JUAL + " AND "
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
                + WHERE_COMPLETED_JUAL + " AND "
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
        int receiptNoIdx = cursor.getColumnIndex(DatabaseHelper.COL_RECEIPT_NO);
        if (receiptNoIdx >= 0) t.setReceiptNo(cursor.getString(receiptNoIdx));
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
