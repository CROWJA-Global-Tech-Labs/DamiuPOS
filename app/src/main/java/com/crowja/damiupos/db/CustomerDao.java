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

    /**
     * "Sudah Order Ulang" (serah-terima marketing): sembunyikan pelanggan yang ditandai oleh
     * PERANGKAT INI sendiri — {@code handed_over_by_device} == {@code sync_device_uuid} milik
     * perangkat (dibaca murni-SQL dari tabel settings, tanpa wiring context). Di perangkat lain
     * uuid-nya beda sehingga pelanggan tetap tampil; baris & transaksinya TIDAK dihapus (riwayat,
     * laporan, dan guard duplikat-HP tetap utuh) — hanya hilang dari daftar perangkat penanda.
     * Kolom tanpa alias supaya klausa ini bisa dipakai di query ber-join maupun polos.
     */
    private static final String NOT_HANDED_OVER_HERE =
            "(" + DatabaseHelper.COL_HANDED_OVER_BY + " IS NULL OR "
                    + DatabaseHelper.COL_HANDED_OVER_BY + " <> COALESCE((SELECT "
                    + DatabaseHelper.COL_SETTING_VALUE + " FROM " + DatabaseHelper.TABLE_SETTINGS
                    + " WHERE " + DatabaseHelper.COL_SETTING_KEY + "='sync_device_uuid'), ''))";

    private final DatabaseHelper dbHelper;

    public CustomerDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Tandai "Sudah Order Ulang" — serah-terima pelanggan dari marketing ke perangkat lain.
     * Mengisi handed_over_at + handed_over_by_device (uuid perangkat ini) lewat syncUpdate
     * (bump edited_at + dirty) sehingga ikut push berikutnya; server lalu memindahkan
     * kepemilikan baris ke 'web' → pelanggan tersinkron ke SEMUA perangkat cabang, sementara
     * daftar di perangkat ini menyembunyikannya ({@link #NOT_HANDED_OVER_HERE}). Offline-first:
     * aman ditandai tanpa jaringan, serah-terima terjadi saat sync berikutnya.
     */
    public void markHandedOver(long customerId, String deviceUuid) {
        markHandedOver(customerId, deviceUuid,
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()));
    }

    /** Tandai "Sudah Order Ulang" dengan waktu order terakhir yang dipilih user (default hari ini).
     *  {@code handedOverAt} = "yyyy-MM-dd HH:mm:ss" waktu lokal. */
    public void markHandedOver(long customerId, String deviceUuid, String handedOverAt) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_HANDED_OVER_AT, handedOverAt);
        v.put(DatabaseHelper.COL_HANDED_OVER_BY, deviceUuid);
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    /**
     * "Tandai Bermasalah": detail pelanggan keliru dilaporkan agar owner/admin memperbaiki.
     * Menyetel kolom issue_* + membuka kembali masalah (issue_resolved_at = null). Offline-first:
     * bump edited_at + synced=0 lewat {@link DatabaseHelper#syncUpdate} → terdorong ke server saat
     * sync berikutnya, tersinkron ke web & semua perangkat.
     *
     * @param flags    kategori dipisah koma (phone,coordinate,photo,address,other)
     * @param note     catatan bebas (boleh null/kosong)
     * @param reporter nama pelapor (staf clock-in / perangkat)
     */
    public void markProblematic(long customerId, String flags, String note, String reporter) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_ISSUE_FLAGS, flags);
        v.put(DatabaseHelper.COL_ISSUE_NOTE, note != null && !note.trim().isEmpty() ? note.trim() : null);
        v.put(DatabaseHelper.COL_ISSUE_REPORTED_AT, now);
        v.put(DatabaseHelper.COL_ISSUE_REPORTED_BY, reporter);
        v.putNull(DatabaseHelper.COL_ISSUE_RESOLVED_AT);   // laporan baru membuka kembali masalah
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
    }

    /** Tandai masalah pelanggan SUDAH DIPERBAIKI: setel issue_resolved_at (tidak meng-null-kan
     *  laporan — push HP menghapus kolom null → pemberesan takkan tersinkron). Tersinkron. */
    public void markProblemResolved(long customerId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_ISSUE_RESOLVED_AT, now);
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerId)});
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
        values.put(DatabaseHelper.COL_WAJIB_ONGKIR, customer.isWajibOngkir() ? 1 : 0);
        values.put(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, customer.isKomisiAddToPrice() ? 1 : 0);
        values.put(DatabaseHelper.COL_LINKED_RESELLER_UUID, customer.getLinkedResellerUuid());
        values.put(DatabaseHelper.COL_GALON_PINJAM_ADJUST, customer.getGalonPinjamAdjust());
        putProductPrices(values, customer);
        putLocations(values, customer);
        if (customer.getResellerSince() != null) {
            values.put(DatabaseHelper.COL_RESELLER_SINCE, customer.getResellerSince());
        }
        // "Didaftarkan oleh": atribusi pencatat = operator yang sedang clock-in di perangkat ini
        // (didenormalisasi namanya, cermin transactions.created_by_name). Disinkron agar dashboard
        // menampilkan pencatatnya walau pelanggan belum bertransaksi. HANYA saat insert (buat).
        String operator = new SettingsDao(dbHelper).getCurrentUserName();
        if (operator != null && !operator.isEmpty()) {
            values.put(DatabaseHelper.COL_CUST_CREATED_BY, operator);
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
        values.put(DatabaseHelper.COL_WAJIB_ONGKIR, customer.isWajibOngkir() ? 1 : 0);
        values.put(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, customer.isKomisiAddToPrice() ? 1 : 0);
        values.put(DatabaseHelper.COL_LINKED_RESELLER_UUID, customer.getLinkedResellerUuid());
        values.put(DatabaseHelper.COL_GALON_PINJAM_ADJUST, customer.getGalonPinjamAdjust());
        putProductPrices(values, customer);
        putLocations(values, customer);
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

    /**
     * Satu pelanggan DENGAN agregat gabungan lintas-perangkat (jumlah transaksi/galon/konsumsi
     * dijumlah dari SEMUA salinan orang ini di cabang) + label perangkat asal — supaya Detail
     * menampilkan angka yang SAMA PERSIS dengan dashboard web & antar perangkat. Untuk salinan
     * milik perangkat ini dipakai agregat lokal (lebih segar), salinan lain pakai agregat server.
     */
    public Customer getByIdMerged(long id) {
        Customer base = getById(id);
        if (base == null) return null;
        String key = dedupKey(base);
        java.util.List<Customer> group = new ArrayList<>();
        boolean hasBase = false;
        // Query BERTARGET (bukan scan semua pelanggan) + NOT_HANDED_OVER_HERE — sumber grup yang
        // SAMA dengan daftar, jadi total di Detail = total di kartu daftar (tak ada "info berbeda").
        for (Customer c : groupCandidates(base)) {
            if (dedupKey(c).equals(key)) {
                group.add(c);
                if (c.getId() == base.getId()) hasBase = true;
            }
        }
        if (!hasBase) group.add(base);   // base sendiri harus terhitung (mis. bila ter-handover di sini)
        applyMergedAggregates(base, group);
        return base;
    }

    /** Kolom agregat pelanggan dari LEFT JOIN transactions LOKAL — dipakai bersama beberapa query. */
    private static final String AGG_COLS =
            "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
            "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
            "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
            "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS first_jual, " +
            "COUNT(t._id) AS total_trx ";

    /** Kandidat grup dedup dari {@code base} — superset KECIL (kecocokan longgar via ekor nomor /
     *  nama) yang di-JOIN agregat + NOT_HANDED_OVER_HERE, lalu disaring tepat by dedupKey pemanggil.
     *  Menghindari scan seluruh pelanggan tiap buka Detail. */
    private java.util.List<Customer> groupCandidates(Customer base) {
        String digits = base.getPhone() == null ? "" : base.getPhone().replaceAll("\\D+", "");
        String where; String[] args;
        if (!digits.isEmpty()) {
            String tail = digits.length() > 7 ? digits.substring(digits.length() - 7) : digits;
            where = "c." + DatabaseHelper.COL_PHONE + " LIKE ?";
            args = new String[]{"%" + tail + "%"};
        } else {
            String name = base.getName() == null ? "" : base.getName().trim();
            where = "LOWER(TRIM(c." + DatabaseHelper.COL_NAME + ")) = LOWER(?)";
            args = new String[]{name};
        }
        java.util.List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT c.*, " + AGG_COLS
                + "FROM customers c LEFT JOIN transactions t ON c._id = t.customer_id "
                + "WHERE (" + where + ") AND " + NOT_HANDED_OVER_HERE + " GROUP BY c._id";
        Cursor cur = db.rawQuery(query, args);
        while (cur.moveToNext()) list.add(cursorToCustomer(cur));
        cur.close();
        return list;
    }

    /**
     * Satukan salinan lintas-perangkat jadi satu baris + JUMLAHKAN agregatnya (galon/transaksi/
     * konsumsi) & kumpulkan tag perangkat asal — dipakai daftar Pelanggan MAUPUN pemilih pelanggan
     * di Transaksi Baru, supaya kartunya identik. Wakil = salinan MILIK perangkat ini bila ada
     * (transaksi lokal menempel di situ), jika tidak yang muncul pertama. Urutan dipertahankan.
     */
    public static List<Customer> dedupeForDisplay(List<Customer> list) {
        LinkedHashMap<String, List<Customer>> groups = new LinkedHashMap<>();
        for (Customer c : list) {
            groups.computeIfAbsent(dedupKey(c), k -> new ArrayList<>()).add(c);
        }
        List<Customer> out = new ArrayList<>();
        for (List<Customer> group : groups.values()) {
            Customer rep = group.get(0);
            for (Customer m : group) {
                if (m.isMine()) { rep = m; break; }   // utamakan salinan milik perangkat ini
            }
            applyMergedAggregates(rep, group);
            out.add(rep);
        }
        return out;
    }

    /**
     * Saldo komisi EFEKTIF seorang reseller = Σ srv_saldo seluruh salinan dedup-nya (angka
     * ResellerSaldo server, lintas SEMUA perangkat — identik dashboard). Fallback rumus lokal
     * (komisi lokal − net pencairan) hanya saat belum provisioning/offline-only, karena
     * kalkulator lokal tak melihat transaksi perangkat lain.
     */
    public double mergedResellerSaldo(long customerId, boolean online) {
        Customer base = getById(customerId);
        if (base == null) return 0;
        String key = dedupKey(base);
        double srv = 0, earned = 0, net = 0;
        ResellerWithdrawalDao wd = online ? null : new ResellerWithdrawalDao(dbHelper);
        for (Customer c : groupCandidates(base)) {
            if (!dedupKey(c).equals(key)) continue;
            srv += c.getSrvSaldo();
            if (!online) {
                earned += ResellerKomisiCalculator.hitung(dbHelper, c).totalKomisi;
                net += wd.getTotalWithdrawn(c.getId());
            }
        }
        return online ? srv : (earned - net);
    }

    /**
     * Galon promosi GRATIS efektif seorang pelanggan LINTAS PERANGKAT = Σ per salinan dedup-nya
     * max(hitungan lokal, srv_promo_galon). Transaksi akuisisi promo tercatat di HP MARKETING —
     * grup dedup tanpa salinan lokal pemilik transaksi itu dihitung 0 oleh
     * {@link TransactionDao#getFreePromoGalon} dan "Tarik Galon Promosi" salah menolak.
     * srv_promo_galon (agg server, pull-only) menutupnya; max PER SALINAN — bukan lokal+srv —
     * karena angka server sudah mencakup transaksi lokal yang terpush; lokal hanya menang saat
     * ada akuisisi baru yang belum tersinkron.
     */
    public int mergedPromoGalon(long customerId) {
        Customer base = getById(customerId);
        if (base == null) return 0;
        String key = dedupKey(base);
        TransactionDao tdao = new TransactionDao(dbHelper);
        int total = 0;
        boolean hasBase = false;
        for (Customer c : groupCandidates(base)) {
            if (!dedupKey(c).equals(key)) continue;
            total += Math.max(tdao.getFreePromoGalon(c.getId()), c.getSrvPromoGalon());
            if (c.getId() == base.getId()) hasBase = true;
        }
        // Base sendiri harus terhitung walau tersaring NOT_HANDED_OVER_HERE (pola getByIdMerged).
        if (!hasBase) total += Math.max(tdao.getFreePromoGalon(base.getId()), base.getSrvPromoGalon());
        return total;
    }

    /**
     * Pasangan {@link #mergedPromoGalon}: galon promosi yang SUDAH DITARIK lintas perangkat =
     * Σ per salinan dedup max(KEMBALI bermarker LOKAL, srv_promo_pulled). Kedua sisi "remaining"
     * (diberikan − sudah ditarik) WAJIB se-altitude: promo lintas perangkat + pulled lokal-saja
     * membuat perangkat lain — atau perangkat ini sendiri saat wakil grupnya berganti salinan —
     * menawarkan tarik-ulang galon yang sudah ditarik (double-pull, stok Galon Kembali menggelembung
     * fiktif). Penarikan offline serentak di dua perangkat sebelum sync tetap mungkin (batas
     * inheren offline-first), tapi begitu KEMBALI-nya terpush semua perangkat melihat angkanya.
     */
    public int mergedPromoPulledGalon(long customerId) {
        Customer base = getById(customerId);
        if (base == null) return 0;
        String key = dedupKey(base);
        TransactionDao tdao = new TransactionDao(dbHelper);
        int total = 0;
        boolean hasBase = false;
        for (Customer c : groupCandidates(base)) {
            if (!dedupKey(c).equals(key)) continue;
            total += Math.max(tdao.getPromoPulledGalon(c.getId()), c.getSrvPromoPulled());
            if (c.getId() == base.getId()) hasBase = true;
        }
        if (!hasBase) total += Math.max(tdao.getPromoPulledGalon(base.getId()), base.getSrvPromoPulled());
        return total;
    }

    /**
     * Kandidat Daftar Kunjungan (marketing): SEMUA pelanggan cabang kecuali "Umum" & yang
     * diserahterimakan perangkat ini. Tiap baris membawa agregat lokal + {@code local_last_jual}
     * (MAX tanggal JUAL transaksi LOKAL) — pemanggil menggabungkannya dengan srv_last_jual
     * (lintas perangkat) + handed_over_at per grup dedup, lalu memfilter/mengurut di Java
     * (lihat VisitListActivity). Tidak dibatasi is_mine: kunjungan menyasar pelanggan cabang.
     */
    public List<Customer> getVisitCandidates() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
                "COUNT(t._id) AS total_trx, " +
                // Order terakhir LOKAL: pesanan TERTUNDA dikecualikan (cermin agregat server &
                // web lastJualSubquery); tanggal ISO-UTC ("…Z") dinormalkan ke waktu lokal dulu
                // supaya MAX string tak salah pilih antar format campuran (bug mixed-tz).
                "MAX(CASE WHEN t.type='JUAL' AND COALESCE(t.delivery_status,'')<>'TERTUNDA' " +
                "    THEN (CASE WHEN t.tanggal LIKE '%Z' " +
                "          THEN COALESCE(datetime(t.tanggal,'localtime'), t.tanggal) " +
                "          ELSE t.tanggal END) END) AS local_last_jual " +
                "FROM " + DatabaseHelper.TABLE_CUSTOMERS + " c " +
                "LEFT JOIN " + DatabaseHelper.TABLE_TRANSACTIONS + " t ON c._id = t.customer_id " +
                "WHERE c.name <> ? AND " + NOT_HANDED_OVER_HERE + " " +
                "GROUP BY c._id";
        Cursor cursor = db.rawQuery(query, new String[]{UMUM_NAME});
        List<Customer> out = new ArrayList<>();
        while (cursor.moveToNext()) out.add(cursorToCustomer(cursor));
        cursor.close();
        return out;
    }

    /** Tandai/batalkan "Sudah Dikunjungi" (Daftar Kunjungan). LOKAL perangkat ini saja —
     *  update polos TANPA syncUpdate (tidak menyentuh edited_at/synced → tak pernah di-push). */
    public int setVisited(long id, boolean visited) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        if (visited) {
            v.put(DatabaseHelper.COL_VISITED_AT, new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
        } else {
            v.putNull(DatabaseHelper.COL_VISITED_AT);
        }
        return db.update(DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Hapus SEMUA tanda "Sudah Dikunjungi" (mulai putaran kunjungan baru). Lokal saja. */
    public int clearAllVisited() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.putNull(DatabaseHelper.COL_VISITED_AT);
        return db.update(DatabaseHelper.TABLE_CUSTOMERS, v,
                DatabaseHelper.COL_VISITED_AT + " IS NOT NULL", null);
    }

    /** {@link #parseMillis} untuk mencari MAX (order TERAKHIR): kosong/gagal parse → Long.MIN_VALUE
     *  (parseMillis memakai MAX_VALUE karena didesain untuk MIN first-order). */
    public static long parseMillisOrMin(String s) {
        long v = parseMillis(s);
        return v == Long.MAX_VALUE ? Long.MIN_VALUE : v;
    }

    /** Nomor kanonik untuk banding duplikat — PEKA kode negara: digit saja, lalu
     *  "08xx" ≡ "+62 8xx" ≡ "62 8xx" ≡ "8xx" semuanya → "628xx". Kosong bila tanpa digit. */
    public static String canonicalPhone(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D+", "");
        if (d.isEmpty()) return "";
        if (d.startsWith("0")) return "62" + d.substring(1);
        if (d.startsWith("8")) return "62" + d;
        return d;
    }

    /**
     * Pelanggan pertama yang nomornya SAMA secara kanonik ({@link #canonicalPhone} — peka
     * +62/62/0). Memindai SEMUA salinan lokal (termasuk milik perangkat lain & yang sudah
     * di-handover) — dipakai guard anti-duplikat saat menambah pelanggan baru. Null bila tak ada.
     */
    public Customer findByPhoneCanonical(String phone) {
        String canon = canonicalPhone(phone);
        if (canon.isEmpty()) return null;
        String tail = canon.length() > 7 ? canon.substring(canon.length() - 7) : canon;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS, null,
                DatabaseHelper.COL_PHONE + " LIKE ?", new String[]{"%" + tail + "%"},
                null, null, null);
        Customer hit = null;
        while (c.moveToNext()) {
            Customer cand = cursorToCustomer(c);
            if (canonicalPhone(cand.getPhone()).equals(canon)) { hit = cand; break; }
        }
        c.close();
        return hit;
    }

    /** Kunci dedup identik server & daftar: nomor (digit, 08→62) → "ph:"; kosong → nama
     *  ternormalisasi "nm:"; dua-duanya kosong → "id:" (tak pernah digabung). */
    public static String dedupKey(Customer c) {
        String digits = c.getPhone() == null ? "" : c.getPhone().replaceAll("\\D+", "");
        if (!digits.isEmpty()) {
            if (digits.startsWith("0")) digits = "62" + digits.substring(1);
            return "ph:" + digits;
        }
        String name = c.getName() == null ? "" : c.getName().trim().toLowerCase().replaceAll("\\s+", " ");
        return name.isEmpty() ? "id:" + c.getId() : "nm:" + name;
    }

    /**
     * Runtuhkan salinan lintas-perangkat dengan identitas sama ({@link #dedupKey}: nomor ternormalisasi,
     * fallback nama) menjadi SATU entri per orang. Untuk picker (Tautkan ke Reseller, Reseller Afiliasi
     * di Transaksi Baru) supaya orang yang sama tak muncul dua kali. Wakil = salinan milik perangkat ini
     * ({@link Customer#isMine()}) bila ada; selain itu yang pertama. Urutan input (mis. komisi DESC) dijaga.
     */
    public static List<Customer> dedupeByIdentity(List<Customer> list) {
        java.util.LinkedHashMap<String, Customer> rep = new java.util.LinkedHashMap<>();
        for (Customer c : list) {
            String k = dedupKey(c);
            Customer cur = rep.get(k);
            if (cur == null || (!cur.isMine() && c.isMine())) rep.put(k, c);
        }
        return new ArrayList<>(rep.values());
    }

    /** Parse tanggal (lokal "yyyy-MM-dd HH:mm:ss" ATAU ISO UTC "…Z") ke epoch millis INSTAN yang
     *  benar — supaya MIN first-order antar salinan berformat campuran memilih instan yang benar-
     *  benar paling awal (bukan sekadar banding string, yang menggeser baris UTC ~7 jam lebih awal
     *  dan menggelembungkan konsumsi gl/hr). MAX_VALUE bila kosong/gagal parse. */
    /** SimpleDateFormat mahal dibuat & tak thread-safe → satu instance per thread, dipakai ulang
     *  (dedupeForDisplay memanggil parseMillis ~1× per pelanggan; tanpa ini ~1300 alokasi/load). */
    private static final ThreadLocal<java.text.SimpleDateFormat> DT_FMT =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                }
            };
    private static final java.util.TimeZone UTC_TZ = java.util.TimeZone.getTimeZone("UTC");

    private static long parseMillis(String s) {
        if (s == null || s.isEmpty()) return Long.MAX_VALUE;
        try {
            String x = s.trim();
            boolean utc = x.endsWith("Z");
            String core = (utc ? x.substring(0, x.length() - 1) : x).replace('T', ' ');
            int dot = core.indexOf('.');
            if (dot > 0) core = core.substring(0, dot);
            if (core.length() > 19) core = core.substring(0, 19);
            core = core.trim();
            java.text.SimpleDateFormat sdf = DT_FMT.get();
            sdf.setTimeZone(utc ? UTC_TZ : java.util.TimeZone.getDefault());
            java.util.Date d = sdf.parse(core);
            return d == null ? Long.MAX_VALUE : d.getTime();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Jumlahkan agregat EFEKTIF (lokal untuk salinan sendiri, server untuk salinan perangkat lain)
     * seluruh anggota grup ke {@code rep} + kumpulkan label perangkat asal (tag). Meniru dashboard
     * web ({@code collapseDuplicates}) yang menjumlah lintas salinan satu orang.
     */
    public static void applyMergedAggregates(Customer rep, java.util.List<Customer> group) {
        int ordered = 0, borrowed = 0, kembali = 0, trx = 0, adjust = 0;
        String firstJual = null;
        long firstMs = Long.MAX_VALUE;
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        for (Customer m : group) {
            ordered  += m.effectiveOrdered();
            borrowed += m.effectiveBorrowed();
            kembali  += m.effectiveKembali();
            trx      += m.effectiveTrx();
            adjust   += m.getGalonPinjamAdjust();
            String fj = m.effectiveFirstJual();
            long ms = parseMillis(fj);
            if (ms < firstMs) { firstMs = ms; firstJual = fj; }
            if (m.getOriginLabel() != null && !m.getOriginLabel().isEmpty()) labels.add(m.getOriginLabel());
        }
        rep.setGalonTotalOrdered(ordered);
        rep.setGalonKeluar(borrowed);
        rep.setGalonKembali(kembali);
        rep.setTotalTransaksi(trx);
        rep.setGalonPinjamAdjust(adjust);
        rep.setFirstOrderDate(firstJual);   // getKonsumsiPerHari() memakai ordered + firstJual gabungan
        rep.setOriginLabels(new ArrayList<>(labels));

        // Tautan reseller (auto-afiliasi) bisa tersimpan di SALINAN mana pun orang ini — mis.
        // di-set dari HP lain pada salinan perangkatnya. Angkat ke wakil supaya badge "TERAFILIASI"
        // di daftar & detail benar walau salinan yang tampil bukan yang memegang tautan.
        if (rep.getLinkedResellerUuid() == null || rep.getLinkedResellerUuid().isEmpty()) {
            for (Customer m : group) {
                if (m.getLinkedResellerUuid() != null && !m.getLinkedResellerUuid().isEmpty()) {
                    rep.setLinkedResellerUuid(m.getLinkedResellerUuid());
                    break;
                }
            }
        }

        // Nama gabungan lintas-perangkat: orang yang sama bisa bernama beda di tiap perangkat
        // (mis. "Hanny Taman Sentosa" vs "Bp Okky"). Tampilkan SEMUA nama unik "NAMA1 / NAMA2" —
        // nama wakil dulu, sisanya menyusul; pembandingan case-insensitive (beda huruf besar/kecil
        // bukan nama berbeda). Mirror dashboard web (CustomerController::distinctNames).
        java.util.LinkedHashMap<String, String> uniqNames = new java.util.LinkedHashMap<>();
        addDistinctName(uniqNames, rep.getName());
        for (Customer m : group) addDistinctName(uniqNames, m.getName());
        rep.setMergedNameCount(uniqNames.size());
        rep.setDisplayName(android.text.TextUtils.join(" / ", uniqNames.values()));
    }

    /** Tambahkan nama ke peta nama-unik (kunci = ternormalisasi lowercase+rapikan spasi), pertahankan
     *  kapitalisasi asli & urutan kemunculan pertama; abaikan null/kosong. */
    private static void addDistinctName(java.util.LinkedHashMap<String, String> map, String nm) {
        if (nm == null) return;
        String t = nm.trim();
        if (t.isEmpty()) return;
        String key = t.toLowerCase().replaceAll("\\s+", " ");
        if (!map.containsKey(key)) map.put(key, t);
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
        return getAll(sortMode, false);
    }

    /**
     * Varian untuk PENCOCOKAN IDENTITAS (mis. resolve pesanan WhatsApp masuk / lampirkan pesanan
     * ke pelanggan): TANPA filter serah-terima "Sudah Order Ulang". Pelanggan yang disembunyikan
     * dari daftar perangkat ini tetap harus bisa dicocokkan — kalau tidak, order WA-nya gagal
     * match dan operator membuat DUPLIKAT baru (merusak dedup lintas perangkat + guard promosi).
     */
    public List<Customer> getAllForMatching() {
        return getAll(SORT_NAME, true);
    }

    /**
     * Klausa "terdaftar HARI INI" (kalender lokal). Menormalkan created_at campuran —
     * UTC ISO "…Z" (hasil sinkron) vs wall-clock lokal (buatan perangkat) — persis
     * aturan ⭐ BARU di struk ({@code TransactionDao.isNewCustomerOnDay}), supaya
     * daftar "Hanya Pelanggan Hari Ini" marketing cocok dengan badge & poin promosi.
     */
    /** Tanggal DAFTAR pelanggan sebagai DATE lokal. Baris tersinkron menyimpan created_at UTC ISO
     *  ("…Z"), baris device menyimpan wall-clock lokal — normalkan dulu ke localtime supaya filter
     *  "Ditambahkan" (dan "Hari Ini") mengelompokkan keduanya per hari lokal yang benar. */
    private static final String CREATED_LOCAL_DATE =
            "date(CASE WHEN c.created_at LIKE '%Z' THEN datetime(c.created_at,'localtime') "
                    + "ELSE c.created_at END)";

    private static final String REGISTERED_TODAY_LOCAL =
            CREATED_LOCAL_DATE + " = date('now','localtime')";

    /** Pelanggan yang didaftarkan hari ini (lokal) — filter default karyawan marketing. */
    public List<Customer> getRegisteredToday(int sortMode) {
        return getAll(sortMode, false, true);
    }

    /** Pelanggan yang DITAMBAHKAN dalam rentang tanggal lokal [from, to] inklusif (yyyy-MM-dd). */
    public List<Customer> getAllCreatedBetween(int sortMode, String from, String to) {
        return getAll(sortMode, false, false, from, to);
    }

    /** Pencarian dibatasi pelanggan yang DITAMBAHKAN dalam rentang [from, to] inklusif. */
    public List<Customer> searchCreatedBetween(String keyword, int sortMode, String from, String to) {
        return search(keyword, sortMode, false, false, from, to);
    }

    /** Jumlah pelanggan terdaftar hari ini (lokal) — untuk label "Hanya Pelanggan Hari Ini (N)". */
    public int countRegisteredToday() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM customers c WHERE "
                + NOT_HANDED_OVER_HERE + " AND " + REGISTERED_TODAY_LOCAL, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private List<Customer> getAll(int sortMode, boolean includeHandedOver) {
        return getAll(sortMode, includeHandedOver, false);
    }

    private List<Customer> getAll(int sortMode, boolean includeHandedOver, boolean todayOnly) {
        return getAll(sortMode, includeHandedOver, todayOnly, null, null);
    }

    private List<Customer> getAll(int sortMode, boolean includeHandedOver, boolean todayOnly,
                                  String createdFrom, String createdTo) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String orderBy = orderByClause(sortMode);
        StringBuilder where = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (!includeHandedOver) where.append(NOT_HANDED_OVER_HERE);
        if (todayOnly) {
            if (where.length() > 0) where.append(" AND ");
            where.append(REGISTERED_TODAY_LOCAL);
        }
        if (createdFrom != null && createdTo != null) {
            if (where.length() > 0) where.append(" AND ");
            where.append(CREATED_LOCAL_DATE).append(" >= ? AND ")
                    .append(CREATED_LOCAL_DATE).append(" <= ?");
            args.add(createdFrom);
            args.add(createdTo);
        }
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
                "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS first_jual, " +
                "COUNT(t._id) AS total_trx " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                (where.length() > 0 ? "WHERE " + where + " " : "") +
                "GROUP BY c._id " +
                orderBy;
        Cursor cursor = db.rawQuery(query, args.isEmpty() ? null : args.toArray(new String[0]));
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
        return search(keyword, sortMode, false);
    }

    /** Pencarian untuk pencocokan identitas — lihat {@link #getAllForMatching()}. */
    public List<Customer> searchForMatching(String keyword) {
        return search(keyword, SORT_NAME, true);
    }

    /** Pencarian dibatasi pelanggan yang terdaftar hari ini (lokal) — mode marketing. */
    public List<Customer> searchRegisteredToday(String keyword, int sortMode) {
        return search(keyword, sortMode, false, true);
    }

    private List<Customer> search(String keyword, int sortMode, boolean includeHandedOver) {
        return search(keyword, sortMode, includeHandedOver, false);
    }

    private List<Customer> search(String keyword, int sortMode, boolean includeHandedOver,
                                  boolean todayOnly) {
        return search(keyword, sortMode, includeHandedOver, todayOnly, null, null);
    }

    private List<Customer> search(String keyword, int sortMode, boolean includeHandedOver,
                                  boolean todayOnly, String createdFrom, String createdTo) {
        List<Customer> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String orderBy = orderByClause(sortMode);
        String like = "%" + keyword + "%";
        List<String> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        args.add(like);
        String dateClause = "";
        if (createdFrom != null && createdTo != null) {
            dateClause = "AND " + CREATED_LOCAL_DATE + " >= ? AND " + CREATED_LOCAL_DATE + " <= ? ";
            args.add(createdFrom);
            args.add(createdTo);
        }
        String query = "SELECT c.*, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' AND COALESCE(t.galon_ownership,'PINJAM')='PINJAM' THEN t.jumlah_galon ELSE 0 END),0) AS galon_keluar, " +
                "COALESCE(SUM(CASE WHEN t.type='KEMBALI' THEN t.jumlah_galon ELSE 0 END),0) AS galon_kembali, " +
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS galon_total_ordered, " +
                "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS first_jual, " +
                "COUNT(t._id) AS total_trx " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE (c.name LIKE ? OR c.phone LIKE ? OR c.address LIKE ?) " +
                (includeHandedOver ? "" : "AND " + NOT_HANDED_OVER_HERE + " ") +
                (todayOnly ? "AND " + REGISTERED_TODAY_LOCAL + " " : "") +
                dateClause +
                "GROUP BY c._id " +
                orderBy;
        Cursor cursor = db.rawQuery(query, args.toArray(new String[0]));
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
                "WHERE " + NOT_HANDED_OVER_HERE + " " +
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
     *
     * <p>Isolated to customers ORIGINALLY REGISTERED on this device ({@code is_mine=1}) — Follow
     * Up is a per-perangkat task list (staf ini yang menindaklanjuti pelanggannya sendiri), tidak
     * seperti daftar Pelanggan yang branch-wide. Pelanggan dari perangkat/web lain tidak muncul di
     * sini walau tersinkron ke HP ini.
     */
    /** Kelonggaran setelah galon terakhir diperkirakan habis (prediksi order kembali). */
    public static final int FOLLOWUP_ALLOWANCE_DAYS = 1;
    /** Pagar atas prediksi — data aneh tak boleh menyembunyikan pelanggan berbulan-bulan. */
    public static final int FOLLOWUP_MAX_PREDICTED_DAYS = 90;

    /**
     * Ambang follow-up DINAMIS per pelanggan: masa PALING LAMA antara pengaturan fixed dan
     * prediksi order kembali dari rate konsumsinya + 1 hari kelonggaran:
     *   rate = total galon JUAL ÷ hari(first_jual → last_jual)  (rentang beli AKTIF — bukan
     *          first→sekarang, yang makin mengecil saat pelanggan absen → ambang memanjang terus),
     *   prediksi = galon HARI beli terakhir ÷ rate + 1 (dibulatkan ke atas, dipagari 90 hari).
     * Sekali beli (rentang 0) → belum ada rate → fixed. CERMIN App\Support\FollowUpDue di web —
     * jaga keduanya selaras.
     */
    public static int followUpThresholdDays(int fixedDays, double galonTotal,
                                            String firstJual, String lastJual, double lastQty) {
        if (galonTotal <= 0 || lastQty <= 0 || firstJual == null || lastJual == null) return fixedDays;
        long span = daysBetweenDates(firstJual, lastJual);
        if (span < 1) return fixedDays;
        double rate = galonTotal / span;
        if (rate <= 0) return fixedDays;
        int predicted = (int) Math.ceil(lastQty / rate) + FOLLOWUP_ALLOWANCE_DAYS;
        return Math.max(fixedDays, Math.min(predicted, FOLLOWUP_MAX_PREDICTED_DAYS));
    }

    /** Rate konsumsi galon/hari (total galon JUAL ÷ rentang beli aktif), 0 bila tak terukur. */
    public static double followUpDailyRate(double galonTotal, String firstJual, String lastJual) {
        if (galonTotal <= 0 || firstJual == null || lastJual == null) return 0;
        long span = daysBetweenDates(firstJual, lastJual);
        if (span < 1) return 0;
        return galonTotal / span;
    }

    /** Perkiraan tanggal ("yyyy-MM-dd") pelanggan seharusnya order lagi = hari beli terakhir +
     *  ⌈galon terakhir ÷ rate konsumsi⌉, atau null bila rate tak terukur. Cermin web (FollowUpDue). */
    public static String followUpReorderDay(double galonTotal, String firstJual, String lastJual, double lastQty) {
        double rate = followUpDailyRate(galonTotal, firstJual, lastJual);
        if (rate <= 0 || lastQty <= 0 || lastJual == null || lastJual.length() < 10) return null;
        int daysToEmpty = (int) Math.ceil(lastQty / rate);
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(f.parse(lastJual.substring(0, 10)));
            cal.add(java.util.Calendar.DAY_OF_MONTH, daysToEmpty);
            return f.format(cal.getTime());
        } catch (Exception e) {
            return null;
        }
    }

    /** Selisih hari (level tanggal) antara dua timestamp "yyyy-MM-dd…". 0 bila tak terparse. */
    private static long daysBetweenDates(String a, String b) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.util.Date da = f.parse(a.substring(0, 10));
            java.util.Date dz = f.parse(b.substring(0, 10));
            if (da == null || dz == null) return 0;
            return Math.abs(dz.getTime() - da.getTime()) / 86400000L;
        } catch (Exception e) {
            return 0;
        }
    }

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
                "MAX(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS last_jual, " +
                // Bahan ambang DINAMIS (prediksi konsumsi — lihat followUpThresholdDays):
                "COALESCE(SUM(CASE WHEN t.type='JUAL' THEN t.jumlah_galon ELSE 0 END),0) AS fu_galon_jual, " +
                "MIN(CASE WHEN t.type='JUAL' THEN t.tanggal END) AS fu_first_jual, " +
                "(SELECT COALESCE(SUM(t2.jumlah_galon),0) FROM transactions t2 " +
                "  WHERE t2.customer_id=c._id AND t2.type='JUAL' AND date(t2.tanggal) = " +
                "   (SELECT date(MAX(t3.tanggal)) FROM transactions t3 " +
                "     WHERE t3.customer_id=c._id AND t3.type='JUAL')) AS fu_last_qty " +
                "FROM customers c " +
                "LEFT JOIN transactions t ON c._id = t.customer_id " +
                "WHERE c.name <> ? AND c." + DatabaseHelper.COL_IS_MINE + " = 1 AND " + NOT_HANDED_OVER_HERE + " " +
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
            // Ambang DINAMIS: HAVING di atas (aturan fixed) adalah SUPERSET-nya — pelanggan masuk
            // daftar hanya setelah melewati masa PALING LAMA antara fixed dan prediksi konsumsi.
            // Entri MANUAL tak terpengaruh (selalu tampil). Cermin filter web (FollowUpDue).
            int idxManual = cursor.getColumnIndex(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT);
            boolean manual = idxManual >= 0 && !cursor.isNull(idxManual);
            int idxLast = cursor.getColumnIndex("last_jual");
            String lastJual = idxLast >= 0 ? cursor.getString(idxLast) : null;
            double galonTotal = cursor.getDouble(cursor.getColumnIndexOrThrow("fu_galon_jual"));
            String firstJual = cursor.getString(cursor.getColumnIndexOrThrow("fu_first_jual"));
            double lastQty = cursor.getDouble(cursor.getColumnIndexOrThrow("fu_last_qty"));
            if (!manual && lastJual != null && lastJual.length() >= 10) {
                int threshold = followUpThresholdDays(days, galonTotal, firstJual, lastJual, lastQty);
                if (threshold > days
                        && lastJual.substring(0, 10).compareTo(cutoffTimestamp(threshold)) >= 0) {
                    continue;   // galon terakhirnya diprediksi belum habis → belum waktunya follow-up
                }
            }
            Customer c = cursorToCustomer(cursor);
            if (idxLast >= 0) {
                c.setCreatedAt(lastJual); // overloaded: last purchase ts
            }
            // Info prediksi order kembali (dari konsumsi galon/hari) untuk ditampilkan di kartu.
            c.setFollowUpReorderDay(followUpReorderDay(galonTotal, firstJual, lastJual, lastQty));
            c.setFollowUpRate(followUpDailyRate(galonTotal, firstJual, lastJual));
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

    /** Count of customers needing follow-up. Delegates to {@link #getFollowUpCandidates} so the
     *  badge SELALU sama dengan isi daftar — termasuk ambang DINAMIS prediksi konsumsi yang
     *  disaring di Java (SQL COUNT terpisah akan drift begitu ada filter pasca-query). */
    public int countFollowUpCandidates(int days) {
        return getFollowUpCandidates(days).size();
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
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM customers WHERE " + NOT_HANDED_OVER_HERE, null);
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
     *  null = nomor kosong/terlalu pendek (tidak bisa di-dedupe). Package-visible: dipakai juga
     *  {@link CustomerGiftDao} untuk mencocokkan gift lintas salinan pelanggan (Salsa #1/#2). */
    static String phoneKey(String phone) {
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

    /**
     * Tulis multi-lokasi sebagai JSON array [{name,lat,lng,wajib_ongkir}] (entri pertama = utama)
     * + mirror kompatibilitas: locations[0] → latitude/longitude/wajib_ongkir menimpa nilai
     * legacy yang sudah di-put pemanggil. SELALU menulis string — daftar null/kosong → literal
     * "[]" (BUKAN null): push sinkron meniadakan kolom null, jadi clear yang ditulis null tidak
     * pernah sampai ke server dan lokasi lama "hidup lagi" di perangkat lain. Baris sentinel
     * (0,0) (koordinat belum di-set) di-drop dan TIDAK pernah di-mirror ke latitude/longitude.
     */
    private void putLocations(ContentValues values, Customer customer) {
        org.json.JSONArray arr = new org.json.JSONArray();
        Customer.Location first = null;
        java.util.List<Customer.Location> list = customer.getLocations();
        if (list != null) {
            for (Customer.Location l : list) {
                if (l == null || (l.lat == 0 && l.lng == 0)) continue; // (0,0) = belum ditandai
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    String nm = l.name == null ? "" : l.name.trim();
                    o.put("name", nm.isEmpty() ? Customer.DEFAULT_LOCATION_NAME : nm);
                    o.put("lat", l.lat);
                    o.put("lng", l.lng);
                    o.put("wajib_ongkir", l.wajibOngkir);
                    arr.put(o);
                    if (first == null) first = l;
                } catch (org.json.JSONException ignored) { }
            }
        }
        values.put(DatabaseHelper.COL_LOCATIONS, arr.toString());
        if (first != null) {
            values.put(DatabaseHelper.COL_LATITUDE, first.lat);
            values.put(DatabaseHelper.COL_LONGITUDE, first.lng);
            values.put(DatabaseHelper.COL_WAJIB_ONGKIR, first.wajibOngkir ? 1 : 0);
        }
        // list kosong/null → nilai legacy dari model (sudah di-put pemanggil) dibiarkan.
    }

    /** Parse JSON array multi-lokasi → List&lt;Location&gt;. Null/kosong/invalid → null.
     *  Baris (0,0) di-drop (sentinel koordinat belum di-set); wajib_ongkir toleran
     *  boolean maupun angka 0/1 (format web). */
    public static java.util.List<Customer.Location> parseLocations(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            java.util.List<Customer.Location> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                double lat = o.optDouble("lat", 0);
                double lng = o.optDouble("lng", 0);
                if (Double.isNaN(lat)) lat = 0;
                if (Double.isNaN(lng)) lng = 0;
                if (lat == 0 && lng == 0) continue;
                String name = o.optString("name", "").trim();
                if (name.isEmpty()) name = Customer.DEFAULT_LOCATION_NAME;
                boolean wajib = o.optBoolean("wajib_ongkir", false)
                        || o.optInt("wajib_ongkir", 0) != 0;
                list.add(new Customer.Location(name, lat, lng, wajib));
            }
            return list.isEmpty() ? null : list;
        } catch (org.json.JSONException e) {
            return null;
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
        int idxPhotoUrl = cursor.getColumnIndex(DatabaseHelper.COL_PHOTO_URL);
        if (idxPhotoUrl >= 0 && !cursor.isNull(idxPhotoUrl)) {
            c.setPhotoUrl(cursor.getString(idxPhotoUrl));
        }
        // Agregat server lintas-perangkat (pull-only) + label perangkat asal.
        int idxSrvTrx = cursor.getColumnIndex(DatabaseHelper.COL_SRV_TRX);
        if (idxSrvTrx >= 0) c.setSrvTrx(cursor.getInt(idxSrvTrx));
        int idxSrvOrd = cursor.getColumnIndex(DatabaseHelper.COL_SRV_ORDERED);
        if (idxSrvOrd >= 0) c.setSrvOrdered(cursor.getInt(idxSrvOrd));
        int idxSrvBor = cursor.getColumnIndex(DatabaseHelper.COL_SRV_BORROWED);
        if (idxSrvBor >= 0) c.setSrvBorrowed(cursor.getInt(idxSrvBor));
        int idxSrvKmb = cursor.getColumnIndex(DatabaseHelper.COL_SRV_KEMBALI);
        if (idxSrvKmb >= 0) c.setSrvKembali(cursor.getInt(idxSrvKmb));
        int idxSrvFj = cursor.getColumnIndex(DatabaseHelper.COL_SRV_FIRST_JUAL);
        if (idxSrvFj >= 0 && !cursor.isNull(idxSrvFj)) c.setSrvFirstJual(cursor.getString(idxSrvFj));
        int idxOrigin = cursor.getColumnIndex(DatabaseHelper.COL_ORIGIN_LABEL);
        if (idxOrigin >= 0 && !cursor.isNull(idxOrigin)) c.setOriginLabel(cursor.getString(idxOrigin));
        int idxSrvSaldo = cursor.getColumnIndex(DatabaseHelper.COL_SRV_SALDO);
        if (idxSrvSaldo >= 0) c.setSrvSaldo(cursor.getDouble(idxSrvSaldo));
        int idxSrvPromo = cursor.getColumnIndex(DatabaseHelper.COL_SRV_PROMO_GALON);
        if (idxSrvPromo >= 0) c.setSrvPromoGalon(cursor.getInt(idxSrvPromo));
        int idxSrvPromoPulled = cursor.getColumnIndex(DatabaseHelper.COL_SRV_PROMO_PULLED);
        if (idxSrvPromoPulled >= 0) c.setSrvPromoPulled(cursor.getInt(idxSrvPromoPulled));
        int idxReseller = cursor.getColumnIndex(DatabaseHelper.COL_IS_RESELLER);
        if (idxReseller >= 0) c.setReseller(cursor.getInt(idxReseller) == 1);
        int idxWajibOngkir = cursor.getColumnIndex(DatabaseHelper.COL_WAJIB_ONGKIR);
        if (idxWajibOngkir >= 0) c.setWajibOngkir(cursor.getInt(idxWajibOngkir) == 1);
        int idxSince = cursor.getColumnIndex(DatabaseHelper.COL_RESELLER_SINCE);
        if (idxSince >= 0 && !cursor.isNull(idxSince)) {
            c.setResellerSince(cursor.getString(idxSince));
        }
        int idxAddPrice = cursor.getColumnIndex(DatabaseHelper.COL_KOMISI_ADD_TO_PRICE);
        if (idxAddPrice >= 0) c.setKomisiAddToPrice(cursor.getInt(idxAddPrice) == 1);
        int idxLinkedR = cursor.getColumnIndex(DatabaseHelper.COL_LINKED_RESELLER_UUID);
        if (idxLinkedR >= 0 && !cursor.isNull(idxLinkedR)) c.setLinkedResellerUuid(cursor.getString(idxLinkedR));
        int idxPinjamAdj = cursor.getColumnIndex(DatabaseHelper.COL_GALON_PINJAM_ADJUST);
        if (idxPinjamAdj >= 0 && !cursor.isNull(idxPinjamAdj)) c.setGalonPinjamAdjust(cursor.getInt(idxPinjamAdj));
        int idxMine = cursor.getColumnIndex(DatabaseHelper.COL_IS_MINE);
        c.setMine(idxMine < 0 || cursor.isNull(idxMine) || cursor.getInt(idxMine) == 1);
        int idxPrices = cursor.getColumnIndex(DatabaseHelper.COL_PRODUCT_PRICES);
        if (idxPrices >= 0 && !cursor.isNull(idxPrices)) {
            c.setProductPrices(parseProductPrices(cursor.getString(idxPrices)));
        }
        int idxKomisi = cursor.getColumnIndex("komisi_galon");
        if (idxKomisi >= 0) c.setKomisiGalon(cursor.getInt(idxKomisi));
        int idxNote = cursor.getColumnIndex(DatabaseHelper.COL_FOLLOWUP_NOTE);
        if (idxNote >= 0 && !cursor.isNull(idxNote)) c.setFollowupNote(cursor.getString(idxNote));
        int idxLastFu = cursor.getColumnIndex(DatabaseHelper.COL_LAST_FOLLOWUP_AT);
        if (idxLastFu >= 0 && !cursor.isNull(idxLastFu)) c.setLastFollowupAt(cursor.getString(idxLastFu));
        int idxManualFu = cursor.getColumnIndex(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT);
        if (idxManualFu >= 0 && !cursor.isNull(idxManualFu)) c.setFollowupManualAt(cursor.getString(idxManualFu));
        int idxHanded = cursor.getColumnIndex(DatabaseHelper.COL_HANDED_OVER_AT);
        if (idxHanded >= 0 && !cursor.isNull(idxHanded)) c.setHandedOverAt(cursor.getString(idxHanded));
        // Daftar Kunjungan: order terakhir lintas perangkat (server) + lokal + tanda dikunjungi
        // + pencatat. Semua dibaca dengan guard — hanya query tertentu yang memuatnya.
        int idxSrvLj = cursor.getColumnIndex(DatabaseHelper.COL_SRV_LAST_JUAL);
        if (idxSrvLj >= 0 && !cursor.isNull(idxSrvLj)) c.setSrvLastJual(cursor.getString(idxSrvLj));
        int idxLocalLj = cursor.getColumnIndex("local_last_jual");
        if (idxLocalLj >= 0 && !cursor.isNull(idxLocalLj)) c.setLocalLastJual(cursor.getString(idxLocalLj));
        int idxVisited = cursor.getColumnIndex(DatabaseHelper.COL_VISITED_AT);
        if (idxVisited >= 0 && !cursor.isNull(idxVisited)) c.setVisitedAt(cursor.getString(idxVisited));
        int idxCreatedBy = cursor.getColumnIndex(DatabaseHelper.COL_CUST_CREATED_BY);
        if (idxCreatedBy >= 0 && !cursor.isNull(idxCreatedBy)) c.setCreatedByName(cursor.getString(idxCreatedBy));
        // "Tandai Bermasalah": kolom issue_* (guard — hanya query SELECT c.* yang memuatnya).
        int idxIsFlags = cursor.getColumnIndex(DatabaseHelper.COL_ISSUE_FLAGS);
        if (idxIsFlags >= 0 && !cursor.isNull(idxIsFlags)) c.setIssueFlags(cursor.getString(idxIsFlags));
        int idxIsNote = cursor.getColumnIndex(DatabaseHelper.COL_ISSUE_NOTE);
        if (idxIsNote >= 0 && !cursor.isNull(idxIsNote)) c.setIssueNote(cursor.getString(idxIsNote));
        int idxIsRepAt = cursor.getColumnIndex(DatabaseHelper.COL_ISSUE_REPORTED_AT);
        if (idxIsRepAt >= 0 && !cursor.isNull(idxIsRepAt)) c.setIssueReportedAt(cursor.getString(idxIsRepAt));
        int idxIsRepBy = cursor.getColumnIndex(DatabaseHelper.COL_ISSUE_REPORTED_BY);
        if (idxIsRepBy >= 0 && !cursor.isNull(idxIsRepBy)) c.setIssueReportedBy(cursor.getString(idxIsRepBy));
        int idxIsResAt = cursor.getColumnIndex(DatabaseHelper.COL_ISSUE_RESOLVED_AT);
        if (idxIsResAt >= 0 && !cursor.isNull(idxIsResAt)) c.setIssueResolvedAt(cursor.getString(idxIsResAt));
        // Multi-lokasi + lazy synthesis (hanya di MODEL, tidak dipersist pembaca): baris legacy
        // tanpa kolom locations tapi punya koordinat → satu entri "Kediaman" membawa flag
        // wajib ongkir legacy, supaya semua pemakai cukup melihat getLocations().
        java.util.List<Customer.Location> locs = null;
        int idxLocs = cursor.getColumnIndex(DatabaseHelper.COL_LOCATIONS);
        // Kolom TERISI (termasuk "[]") ≠ kolom NULL: "[]" = daftar SENGAJA dikosongkan — jangan
        // disintesis ulang dari mirror lat/lng yang bisa basi (mis. hasil "Gabung Pelanggan" web
        // yang menyalin koordinat tanpa menyentuh locations); kalau tidak, HP menampilkan lokasi
        // hantu lalu mem-push-nya balik saat pelanggan diedit. Sintesis "Kediaman" hanya untuk
        // baris legacy yang kolomnya benar-benar NULL (cermin gerbang `locations === null` di web).
        boolean locsColPresent = idxLocs >= 0 && !cursor.isNull(idxLocs);
        if (locsColPresent) {
            locs = parseLocations(cursor.getString(idxLocs));
        }
        if (!locsColPresent && (locs == null || locs.isEmpty()) && c.hasCoordinates()) {
            locs = new ArrayList<>();
            locs.add(new Customer.Location(Customer.DEFAULT_LOCATION_NAME,
                    c.getLatitude(), c.getLongitude(), c.isWajibOngkir()));
        }
        if (locs != null) c.setLocations(locs);
        return c;
    }

    /**
     * Semua reseller beserta {@code komisi_galon} = total galon JUAL sejak
     * {@code reseller_since}. Saldo komisi rupiah dihitung caller:
     * komisi_galon × rate − total pencairan (lihat ResellerWithdrawalDao).
     */
    /** sync_uuid pelanggan (identitas lintas-perangkat) untuk id lokal — dipakai menyimpan tautan reseller. */
    public String getSyncUuidById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS, new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)}, null, null, null);
        String uuid = (c.moveToFirst() && !c.isNull(0)) ? c.getString(0) : null;
        c.close();
        return uuid;
    }

    /** id lokal pelanggan untuk sebuah sync_uuid (resolusi tautan reseller → afiliasi). -1 bila tak ada. */
    public long getIdBySyncUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return -1;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS, new String[]{DatabaseHelper.COL_ID},
                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
        long id = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        return id;
    }

    /** Nama pelanggan/reseller untuk sebuah sync_uuid (resolusi tautan reseller → tampilan).
     *  null bila tak ada padanan (mis. reseller belum tersinkron ke perangkat ini). */
    public String nameBySyncUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS, new String[]{DatabaseHelper.COL_NAME},
                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null, "1");
        String name = (c.moveToFirst() && !c.isNull(0)) ? c.getString(0) : null;
        c.close();
        return name;
    }

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
                "AND " + NOT_HANDED_OVER_HERE + " " +
                "GROUP BY c._id " +
                "ORDER BY komisi_galon DESC, c.name ASC";
        Cursor cursor = db.rawQuery(query, null);
        while (cursor.moveToNext()) {
            list.add(cursorToCustomer(cursor));
        }
        cursor.close();
        return list;
    }

    // ----------------------------------------------------------- Pelanggan Promosi

    /** Satu baris layar "Pelanggan Promosi": pelanggan baru yang diberi galon GRATIS pada hari
     *  daftarnya (akuisisi tercatat DI PERANGKAT INI), plus tanggal PEMBELIAN pertamanya. */
    public static class PromoRow {
        public long id;
        public String name;
        public String phone;
        public String promoDay;    // "yyyy-MM-dd" — kapan promosi diberikan
        public int galon;          // galon gratis yang diberikan
        public String repeatDay;   // "yyyy-MM-dd" pembelian pertama, null = belum order ulang
        public int days;           // jeda hari promo → pembelian (hanya valid bila repeatDay != null)
    }

    /**
     * Kohort promo galon gratis perangkat ini: pelanggan dengan JUAL Rp 0 pada hari daftarnya
     * (transaksi akuisisi ada di DB lokal — otomatis hanya pelanggan yang diakuisisi di sini),
     * promo dalam [startDay..endDay] ("yyyy-MM-dd"; null = tanpa batas). Konversi = pembelian
     * berbayar pertama di hari lebih baru: pakai agg server ({@code srv_first_paid}, lintas
     * perangkat — pembelian biasanya terjadi di HP depot) DAN fallback transaksi berbayar lokal,
     * diambil yang paling awal. Pelanggan yang sudah diserahterimakan ("Sudah Order Ulang")
     * TETAP tampil — layar ini justru memantau keberhasilan konversinya. Pencairan komisi
     * ([PENCAIRAN KOMISI], JUAL Rp 0 sistem) dikecualikan dari akuisisi.
     */
    public java.util.List<PromoRow> getPromoCustomers(String startDay, String endDay, String search) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT c." + DatabaseHelper.COL_ID + ", c." + DatabaseHelper.COL_NAME
                + ", c." + DatabaseHelper.COL_PHONE
                + ", substr(c." + DatabaseHelper.COL_CREATED_AT + ",1,10) AS reg_day"
                + ", substr(MIN(CASE WHEN t." + DatabaseHelper.COL_TOTAL_HARGA + "=0"
                + "   AND substr(t." + DatabaseHelper.COL_TANGGAL + ",1,10)=substr(c." + DatabaseHelper.COL_CREATED_AT + ",1,10)"
                + "   THEN t." + DatabaseHelper.COL_TANGGAL + " END),1,10) AS promo_day"
                + ", COALESCE(SUM(CASE WHEN t." + DatabaseHelper.COL_TOTAL_HARGA + "=0"
                + "   AND substr(t." + DatabaseHelper.COL_TANGGAL + ",1,10)=substr(c." + DatabaseHelper.COL_CREATED_AT + ",1,10)"
                + "   THEN t." + DatabaseHelper.COL_JUMLAH_GALON + " ELSE 0 END),0) AS galon"
                + ", MIN(CASE WHEN t." + DatabaseHelper.COL_TOTAL_HARGA + ">0"
                + "   AND substr(t." + DatabaseHelper.COL_TANGGAL + ",1,10)>substr(c." + DatabaseHelper.COL_CREATED_AT + ",1,10)"
                // Order TERTUNDA (dijeda dari web) bukan konversi — selaras agg server & halaman web.
                + "   AND COALESCE(t." + DatabaseHelper.COL_DELIVERY_STATUS + ",'')<>'TERTUNDA'"
                + "   THEN substr(t." + DatabaseHelper.COL_TANGGAL + ",1,10) END) AS local_repeat"
                + ", c." + DatabaseHelper.COL_SRV_FIRST_PAID
                + " FROM " + DatabaseHelper.TABLE_CUSTOMERS + " c"
                + " JOIN " + DatabaseHelper.TABLE_TRANSACTIONS + " t ON t." + DatabaseHelper.COL_CUSTOMER_ID + "=c." + DatabaseHelper.COL_ID
                + "   AND t." + DatabaseHelper.COL_TYPE + "='JUAL'"
                + "   AND COALESCE(t." + DatabaseHelper.COL_CATATAN + ",'') NOT LIKE '%[PENCAIRAN KOMISI]%'"
                + (search != null && !search.trim().isEmpty()
                    ? " WHERE (c." + DatabaseHelper.COL_NAME + " LIKE ? OR c." + DatabaseHelper.COL_PHONE + " LIKE ?)" : "")
                + " GROUP BY c." + DatabaseHelper.COL_ID
                + " HAVING promo_day IS NOT NULL"
                + (startDay != null ? " AND promo_day >= ?" : "")
                + (endDay != null ? " AND promo_day <= ?" : "")
                + " ORDER BY promo_day DESC, c." + DatabaseHelper.COL_NAME + " ASC";
        java.util.List<String> argList = new java.util.ArrayList<>();
        if (search != null && !search.trim().isEmpty()) {
            String like = "%" + search.trim() + "%";
            argList.add(like);
            argList.add(like);
        }
        if (startDay != null) argList.add(startDay);
        if (endDay != null) argList.add(endDay);
        String[] args = argList.isEmpty() ? null : argList.toArray(new String[0]);
        java.util.List<PromoRow> out = new java.util.ArrayList<>();
        try (Cursor c = db.rawQuery(sql, args)) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            while (c.moveToNext()) {
                PromoRow r = new PromoRow();
                r.id = c.getLong(0);
                r.name = c.getString(1);
                r.phone = c.getString(2);
                String regDay = c.getString(3);
                r.promoDay = c.getString(4);
                r.galon = c.getInt(5);
                String localRepeat = c.isNull(6) ? null : c.getString(6);
                String srvPaid = c.isNull(7) ? null : c.getString(7);
                // Konversi = yang PALING AWAL antara agg server & transaksi lokal; agg server
                // hanya dihitung bila jatuh di hari LEBIH BARU dari hari daftar (pembelian
                // berbayar di hari akuisisi = bagian akuisisi, bukan order ulang).
                String srvDay = (srvPaid != null && srvPaid.length() >= 10) ? srvPaid.substring(0, 10) : null;
                if (srvDay != null && regDay != null && srvDay.compareTo(regDay) <= 0) srvDay = null;
                String repeat = localRepeat;
                if (srvDay != null && (repeat == null || srvDay.compareTo(repeat) < 0)) repeat = srvDay;
                r.repeatDay = repeat;
                if (repeat != null && r.promoDay != null) {
                    try {
                        long ms = fmt.parse(repeat).getTime() - fmt.parse(r.promoDay).getTime();
                        r.days = (int) (ms / (24L * 60 * 60 * 1000));
                    } catch (Exception ignored) { r.days = 0; }
                }
                out.add(r);
            }
        }
        return out;
    }
}
