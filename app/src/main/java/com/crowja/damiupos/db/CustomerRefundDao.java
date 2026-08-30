package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.UUID;

/**
 * REFUND pelanggan (sisi perangkat). Uang yang dikembalikan depot disimpan sebagai SALDO dan
 * otomatis ditawarkan sebagai potongan pada pembelian berikutnya.
 *
 * <p>Bentuknya BUKU BESAR, bukan kolom saldo: baris {@code credit} (pemberian — biasanya ditulis
 * operator di web) dan baris {@code usage} (pemakaian — ditulis siapa pun yang memotong, web
 * MAUPUN HP ini). Saldo = Σcredit − Σusage, dipagari 0. Kalau saldo disimpan sebagai satu angka,
 * dua perangkat yang memotong bersamaan akan saling menimpa lewat LWW dan uang pelanggan hilang;
 * dengan buku besar, dua baris pemakaian itu justru menjumlah dengan benar setelah sync.
 * Cermin persis {@code App\Support\RefundBalance} di server.
 *
 * <p>Baris yang ditulis di sini ditandai kotor ({@code synced=0}) → ikut push berikutnya.
 */
public class CustomerRefundDao {

    private final DatabaseHelper dbHelper;

    public CustomerRefundDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Saldo refund pelanggan (≥ 0). {@code customerId} = _id lokal baris customers.
     * Perangkat ini menarik SEMUA pelanggan cabang (branch-wide), jadi satu id sudah mewakili
     * orangnya di HP ini — penjumlahan lintas-salinan ditangani server saat menyusun angkanya.
     */
    public double balanceFor(long customerId) {
        if (customerId <= 0) return 0;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT " + DatabaseHelper.COL_REFUND_KIND + ", SUM(" + DatabaseHelper.COL_REFUND_AMOUNT + ") " +
                        "FROM " + DatabaseHelper.TABLE_CUSTOMER_REFUNDS +
                        " WHERE " + DatabaseHelper.COL_REFUND_CUSTOMER_ID + "=?" +
                        " GROUP BY " + DatabaseHelper.COL_REFUND_KIND,
                new String[]{String.valueOf(customerId)});
        double credit = 0, usage = 0;
        try {
            while (c.moveToNext()) {
                String kind = c.getString(0);
                double amt = c.getDouble(1);
                if ("usage".equals(kind)) usage += amt; else credit += amt;
            }
        } finally {
            c.close();
        }
        double saldo = credit - usage;
        return saldo > 0 ? Math.round(saldo * 100d) / 100d : 0;
    }

    /**
     * Catat PEMAKAIAN saldo pada sebuah transaksi. Nominal dibatasi saldo yang benar-benar ada
     * (dihitung ulang di sini — jangan percaya angka dari form), lalu baris buku besar ditulis dan
     * ditandai kotor. Mengembalikan nominal yang BENAR-BENAR terpotong (0 = tak ada).
     */
    public double spend(long customerId, double amount, String trxUuid, String byName) {
        if (customerId <= 0 || amount <= 0) return 0;
        double avail = balanceFor(customerId);
        double use = Math.min(amount, avail);
        use = Math.round(use * 100d) / 100d;
        if (use <= 0) return 0;

        String now = DatabaseHelper.nowIso();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_REFUND_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_REFUND_KIND, "usage");
        v.put(DatabaseHelper.COL_REFUND_AMOUNT, use);
        v.put(DatabaseHelper.COL_REFUND_TRX_UUID, trxUuid);
        if (byName != null && !byName.isEmpty()) v.put(DatabaseHelper.COL_REFUND_BY, byName);
        v.put(DatabaseHelper.COL_CREATED_AT, now);
        v.put(DatabaseHelper.COL_SYNC_UUID, UUID.randomUUID().toString());
        v.put(DatabaseHelper.COL_EDITED_AT, now);
        v.put(DatabaseHelper.COL_SYNCED, 0);
        dbHelper.getWritableDatabase().insert(DatabaseHelper.TABLE_CUSTOMER_REFUNDS, null, v);
        return use;
    }

    /**
     * Saldo refund yang DIPAKAI oleh satu transaksi (Σ baris 'usage' bertaut uuid transaksi itu).
     * Dipakai struk (foto & teks WA) untuk mencetak "Dari saldo refund" + "Sisa dibayar". Dibaca
     * dari buku besar, bukan dari penanda "[REFUND Rp X]" pada catatan — penanda bisa hilang bila
     * catatan diedit, sedangkan baris buku besar adalah uangnya sendiri.
     */
    public double usedForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return 0;
        Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT SUM(" + DatabaseHelper.COL_REFUND_AMOUNT + ") FROM " + DatabaseHelper.TABLE_CUSTOMER_REFUNDS +
                        " WHERE " + DatabaseHelper.COL_REFUND_TRX_UUID + "=? AND " +
                        DatabaseHelper.COL_REFUND_KIND + "='usage'",
                new String[]{trxUuid});
        double sum = 0;
        try {
            if (c.moveToFirst()) sum = c.getDouble(0);
        } finally {
            c.close();
        }
        return sum > 0 ? Math.round(sum * 100d) / 100d : 0;
    }

    /**
     * Batalkan pemakaian yang terlanjur tercatat untuk sebuah transaksi — dipakai saat pembuatan
     * transaksi GAGAL setelah saldo terpotong, supaya uang pelanggan tak hangus. Baris di-hard
     * delete: ia belum pernah ter-push (masih dirty) pada alur kegagalan itu.
     */
    public void revokeForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        dbHelper.getWritableDatabase().delete(DatabaseHelper.TABLE_CUSTOMER_REFUNDS,
                DatabaseHelper.COL_REFUND_TRX_UUID + "=? AND " + DatabaseHelper.COL_REFUND_KIND + "='usage'",
                new String[]{trxUuid});
    }
}
