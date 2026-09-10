package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * Gift pelanggan (device side). Hadiah (produk/item custom) di-assign dari web/dashboard dan
 * ditarik ke tiap perangkat cabang (branch-wide, pull). Sebuah gift "pending" sampai transaksi
 * JUAL pelanggan itu meng-klaim-nya: {@link #redeemForTransaction} mengisi {@code redeemed_at},
 * menautkan struk ({@code redeemed_transaction_uuid}), lalu di-push balik ke server.
 *
 * <p>Dipakai untuk: (a) memperingatkan kasir saat memilih pelanggan yang punya gift pending, dan
 * (b) menampilkan gift + alasan + ucapan terima kasih di struk (gambar & teks WA) transaksi yang
 * meng-klaim-nya. {@code item_name} adalah snapshot (produk bisa ganti nama).
 */
public class CustomerGiftDao {

    /** Satu gift untuk ditampilkan/diberikan. */
    public static final class Gift {
        public final long localId;
        public final String itemType;   // product | custom
        public final String itemName;
        public final int qty;
        public final String reason;      // boleh null/empty
        public final String syncUuid;
        /** Sudah dilekatkan ke transaksi ini (struk sudah menjanjikannya), belum Selesai. Null =
         *  belum dilekatkan ke mana pun. Cermin App\Support\Gifts di web. */
        public final String pendingTransactionUuid;

        Gift(long localId, String itemType, String itemName, int qty, String reason, String syncUuid,
             String pendingTransactionUuid) {
            this.localId = localId;
            this.itemType = itemType;
            this.itemName = itemName;
            this.qty = qty;
            this.reason = reason;
            this.syncUuid = syncUuid;
            this.pendingTransactionUuid = pendingTransactionUuid;
        }

        /** Label ringkas, mis. "3 pcs Gelas Cantik". */
        public String label() {
            return qty + " pcs " + (itemName != null ? itemName : "");
        }

        /**
         * Gift berwujud PRODUK — bisa diwujudkan jadi baris item Rp 0 pada transaksi. Gift produk
         * TIDAK diklaim otomatis saat transaksi dibuat: staf memutuskan lewat popup konfirmasi
         * (tambah baris gratis / gratiskan item yang dibeli). Cermin aturan yang sama di server.
         */
        public boolean isProduct() {
            return "product".equals(itemType);
        }
    }

    private final DatabaseHelper dbHelper;

    public CustomerGiftDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    private static final String[] COLS = {
            DatabaseHelper.COL_ID, DatabaseHelper.COL_GIFT_ITEM_TYPE,
            DatabaseHelper.COL_GIFT_ITEM_NAME, DatabaseHelper.COL_GIFT_QTY,
            DatabaseHelper.COL_GIFT_REASON, DatabaseHelper.COL_SYNC_UUID,
            DatabaseHelper.COL_GIFT_PENDING_TRX_UUID,
    };

    private static Gift fromCursor(Cursor c) {
        return new Gift(c.getLong(0), c.getString(1), c.getString(2),
                c.getInt(3), c.getString(4), c.getString(5), c.getString(6));
    }

    /** Predikat "belum diberikan" (redeemed_at NULL/kosong). */
    private static final String REDEEMED_EMPTY =
            "(" + DatabaseHelper.COL_GIFT_REDEEMED_AT + " IS NULL OR "
                    + DatabaseHelper.COL_GIFT_REDEEMED_AT + " = '')";

    /** Predikat "belum dilekatkan ke transaksi mana pun" (pending_transaction_uuid NULL/kosong). */
    private static final String NOT_ATTACHED =
            "(" + DatabaseHelper.COL_GIFT_PENDING_TRX_UUID + " IS NULL OR "
                    + DatabaseHelper.COL_GIFT_PENDING_TRX_UUID + " = '')";

    /** Benar-benar bebas ditawarkan: belum diberikan DAN belum dilekatkan ke transaksi lain. */
    private static final String PENDING = "(" + REDEEMED_EMPTY + " AND " + NOT_ATTACHED + ")";

    /**
     * _id SEMUA salinan pelanggan lokal dengan nomor sama (branch-wide) untuk {@code customerLocalId}
     * — pelanggan yang sama bisa terdaftar di beberapa perangkat sebagai baris berbeda ("Salsa #1" &
     * "Salsa #2"); gift menempel di satu salinan tapi transaksi bisa tercatat pada salinan lain.
     * Cocokkan lewat {@link CustomerDao#phoneKey} (kunci dedup yang sama dengan daftar Pelanggan).
     * Selalu memuat {@code customerLocalId} sendiri; nomor kosong → hanya dirinya.
     */
    private List<Long> personCustomerIds(SQLiteDatabase db, long customerLocalId) {
        List<Long> ids = new ArrayList<>();
        ids.add(customerLocalId);
        String phone = null;
        try (Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS, new String[]{DatabaseHelper.COL_PHONE},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerLocalId)}, null, null, null)) {
            if (c.moveToFirst()) phone = c.getString(0);
        }
        String key = CustomerDao.phoneKey(phone);
        if (key == null) return ids;   // tanpa nomor → tak bisa disamakan lintas salinan
        try (Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_PHONE}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (id == customerLocalId) continue;
                if (key.equals(CustomerDao.phoneKey(c.getString(1)))) ids.add(id);
            }
        }
        return ids;
    }

    /** "customer_id IN (?,?,…)" untuk daftar id (aman: id numerik dari DB). */
    private static String inClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder(DatabaseHelper.COL_GIFT_CUSTOMER_ID + " IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.append(')').toString();
    }

    /** Gift yang masih pending (belum diberikan) untuk pelanggan ini (lintas salinan), urut waktu dibuat. */
    public List<Gift> pendingForCustomer(long customerLocalId) {
        List<Gift> out = new ArrayList<>();
        if (customerLocalId <= 0) return out;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMER_GIFTS, COLS,
                inClause(personCustomerIds(db, customerLocalId)) + " AND " + PENDING,
                null, null, null, DatabaseHelper.COL_CREATED_AT + " ASC");
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Gift pending berjenis PRODUK — yang ditawarkan lewat popup setelah transaksi JUAL dibuat. */
    public List<Gift> pendingProductForCustomer(long customerLocalId) {
        List<Gift> out = new ArrayList<>();
        for (Gift g : pendingForCustomer(customerLocalId)) {
            if (g.isProduct()) out.add(g);
        }
        return out;
    }

    /** Apakah pelanggan ini (lintas salinan) punya minimal satu gift pending — untuk peringatan. */
    public boolean hasPendingForCustomer(long customerLocalId) {
        if (customerLocalId <= 0) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMER_GIFTS,
                new String[]{DatabaseHelper.COL_ID},
                inClause(personCustomerIds(db, customerLocalId)) + " AND " + PENDING,
                null, null, null, null, "1");
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /**
     * Klaim SEMUA gift CUSTOM pending pelanggan {@code customerLocalId} untuk transaksi JUAL ber-
     * uuid {@code trxUuid}. {@code queued} — transaksi ini MASIH masuk antrean delivery (PENDING/
     * TERTUNDA, belum Selesai)? true → hanya DILEKATKAN ({@code pending_transaction_uuid}, struk
     * sudah boleh menjanjikannya tapi belum "sungguh diberikan"); false (self-pick/tunai walk-in,
     * "dibuat = selesai") → langsung {@code redeemed_at} seperti dulu. {@link #finalizeAttachedForTransaction}
     * yang menuntaskan gift terlekat saat order itu akhirnya ditandai Selesai. Cermin
     * App\Support\Gifts::redeemForTransaction di web — jaga selaras.
     *
     * <p>Beroperasi pada {@code db} yang diberikan (dipanggil di dalam TransactionDao.insert). Aman
     * dipanggil untuk non-JUAL/tanpa pelanggan (no-op). Mengembalikan gift yang baru dilekatkan/diberikan.
     */
    public List<Gift> redeemForTransaction(SQLiteDatabase db, long customerLocalId, String trxUuid,
                                            String byName, boolean queued) {
        List<Gift> redeemed = new ArrayList<>();
        if (customerLocalId <= 0 || trxUuid == null || trxUuid.isEmpty()) return redeemed;
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMER_GIFTS, COLS,
                inClause(personCustomerIds(db, customerLocalId)) + " AND " + PENDING,
                null, null, null, DatabaseHelper.COL_CREATED_AT + " ASC");
        try {
            // Gift PRODUK dilewati: wujudnya baris item, jadi butuh keputusan staf lewat popup
            // (lihat TransactionActivity#maybeOfferProductGift). Kalau ikut diklaim di sini,
            // gift-nya hilang dari daftar pending sebelum sempat ditawarkan.
            while (c.moveToNext()) {
                Gift g = fromCursor(c);
                if (!g.isProduct()) redeemed.add(g);
            }
        } finally {
            c.close();
        }
        if (redeemed.isEmpty()) return redeemed;
        String now = DatabaseHelper.nowIso();
        for (Gift g : redeemed) {
            ContentValues v = new ContentValues();
            // Siapa yang MEMUTUSKAN memberi gift ini dicatat sekarang baik dua-duanya — beda dari
            // siapa yang belakangan menstempel Selesai (bisa kurir lain).
            if (byName != null && !byName.isEmpty()) {
                v.put(DatabaseHelper.COL_GIFT_REDEEMED_BY, byName);
            }
            if (queued) {
                v.put(DatabaseHelper.COL_GIFT_PENDING_TRX_UUID, trxUuid);
            } else {
                v.put(DatabaseHelper.COL_GIFT_REDEEMED_AT, now);
                v.put(DatabaseHelper.COL_GIFT_REDEEMED_TRX_UUID, trxUuid);
            }
            v.put(DatabaseHelper.COL_EDITED_AT, now);
            v.put(DatabaseHelper.COL_SYNCED, 0);
            db.update(DatabaseHelper.TABLE_CUSTOMER_GIFTS, v,
                    DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(g.localId)});
        }
        return redeemed;
    }

    /**
     * Klaim/lekatkan SATU gift PRODUK (dipakai popup gift produk setelah staf memilih wujudnya).
     * {@code queued} — sama artinya dengan {@link #redeemForTransaction}. Sama seperti method itu
     * tapi untuk satu baris, di database milik dbHelper (dipanggil dari luar sebuah insert transaksi).
     */
    public void redeemOne(long giftLocalId, String trxUuid, String byName, boolean queued) {
        if (giftLocalId <= 0 || trxUuid == null || trxUuid.isEmpty()) return;
        String now = DatabaseHelper.nowIso();
        ContentValues v = new ContentValues();
        if (byName != null && !byName.isEmpty()) {
            v.put(DatabaseHelper.COL_GIFT_REDEEMED_BY, byName);
        }
        if (queued) {
            v.put(DatabaseHelper.COL_GIFT_PENDING_TRX_UUID, trxUuid);
        } else {
            v.put(DatabaseHelper.COL_GIFT_REDEEMED_AT, now);
            v.put(DatabaseHelper.COL_GIFT_REDEEMED_TRX_UUID, trxUuid);
        }
        v.put(DatabaseHelper.COL_EDITED_AT, now);
        v.put(DatabaseHelper.COL_SYNCED, 0);
        dbHelper.getWritableDatabase().update(DatabaseHelper.TABLE_CUSTOMER_GIFTS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(giftLocalId)});
    }

    /**
     * Tuntaskan gift yang DILEKATKAN ke transaksi ini ({@code pending_transaction_uuid}) menjadi
     * SUNGGUH DIBERIKAN — dipanggil TEPAT SEKALI dari {@link TransactionDao#markDelivered}, saat
     * transaksinya ditandai Selesai. Aman dipanggil untuk transaksi tanpa gift terlekat (no-op) dan
     * aman dipanggil berulang (predikat menyaring baris yang sudah punya redeemed_at). Cermin
     * App\Support\Gifts::finalizeAttachedForTransaction di web.
     */
    public void finalizeAttachedForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String now = DatabaseHelper.nowIso();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_GIFT_REDEEMED_AT, now);
        v.put(DatabaseHelper.COL_GIFT_REDEEMED_TRX_UUID, trxUuid);
        v.putNull(DatabaseHelper.COL_GIFT_PENDING_TRX_UUID);
        v.put(DatabaseHelper.COL_EDITED_AT, now);
        v.put(DatabaseHelper.COL_SYNCED, 0);
        db.update(DatabaseHelper.TABLE_CUSTOMER_GIFTS, v,
                DatabaseHelper.COL_GIFT_PENDING_TRX_UUID + "=? AND " + REDEEMED_EMPTY,
                new String[]{trxUuid});
    }

    /**
     * Gift yang di-klaim oleh transaksi ber-uuid {@code trxUuid} — baik yang sudah TUNTAS
     * ({@code redeemed_transaction_uuid}) maupun yang baru DILEKATKAN dan masih menunggu Selesai
     * ({@code pending_transaction_uuid}) — untuk ditampilkan di struk (gambar & teks WA). Struknya
     * boleh menjanjikan gift ini WALAU ordernya belum Selesai; cermin App\Support\Gifts::redeemedBy.
     */
    public List<Gift> redeemedForTransaction(String trxUuid) {
        List<Gift> out = new ArrayList<>();
        if (trxUuid == null || trxUuid.isEmpty()) return out;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMER_GIFTS, COLS,
                DatabaseHelper.COL_GIFT_REDEEMED_TRX_UUID + "=? OR " + DatabaseHelper.COL_GIFT_PENDING_TRX_UUID + "=?",
                new String[]{trxUuid, trxUuid}, null, null, DatabaseHelper.COL_CREATED_AT + " ASC");
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }
}
