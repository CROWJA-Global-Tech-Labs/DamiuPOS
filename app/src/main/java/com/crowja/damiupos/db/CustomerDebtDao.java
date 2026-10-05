package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.util.ReceiptNo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HUTANG pelanggan (sisi perangkat) — piutang depot, kebalikan {@link CustomerRefundDao}.
 *
 * <p>Bentuknya BUKU BESAR, bukan kolom saldo: baris {@code debt} (pelanggan berhutang — lahir dari
 * penjualan berbayar "HUTANG" atau dicatat manual) dan baris {@code payment} (pelunasan, boleh
 * dicicil). Sisa = Σdebt − Σpayment, dipagari 0. Kalau sisanya disimpan sebagai satu angka, dua
 * kurir yang menerima pelunasan bersamaan akan saling menimpa lewat LWW dan uang depot hilang dari
 * catatan; dengan buku besar, dua baris pembayaran itu justru menjumlah dengan benar setelah sync.
 * Cermin persis {@code App\Support\DebtBalance} di server.
 *
 * <p>Baris yang ditulis di sini ditandai kotor ({@code synced=0}) → ikut push berikutnya.
 */
public class CustomerDebtDao {

    public static final String KIND_DEBT = "debt";
    public static final String KIND_PAYMENT = "payment";

    private final DatabaseHelper dbHelper;

    public CustomerDebtDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** Satu baris buku besar — dipakai daftar riwayat di Detail Pelanggan. */
    public static class Entry {
        public long id;
        public String kind;
        public double amount;
        public String reason;
        public String createdAt;
        public String byName;

        public boolean isDebt() {
            return !KIND_PAYMENT.equals(kind);
        }
    }

    /**
     * Sisa hutang seorang ORANG (≥ 0) — Σ(debt − payment) SELURUH salinan dedup-nya, dipagari 0
     * SEKALI di akhir. {@code customerId} = _id lokal salah satu salinan baris customers.
     *
     * <p><b>Kenapa lintas-salinan.</b> Satu orang bisa punya beberapa baris customers di HP ini
     * (didaftarkan di lebih dari satu perangkat, lalu semuanya ditarik karena customers branch-wide).
     * Web menjumlah hutang lintas SELURUH grup dedup ({@code CustomerController::mergedUuidsFor} →
     * {@code DebtBalance::for}). Dulu metode ini hanya menjumlah SATU salinan dengan alasan
     * "penjumlahan lintas-salinan ditangani server" — itu KELIRU: server hanya mengirim agregat
     * {@code agg_*} untuk entitas customers, tak pernah ada {@code agg_debt}. Akibatnya angka HP
     * dan web DIJAMIN beda begitu orangnya punya lebih dari satu salinan.
     *
     * <p><b>Kenapa dipagari SEKALI di akhir, bukan per salinan.</b> Pembayaran bisa tercatat di
     * salinan yang berbeda dari hutangnya, membuat satu salinan bersaldo NEGATIF. Memagari
     * per-salinan lebih dulu membuang angka negatif itu dan MENGGELEMBUNGKAN total — jebakan
     * floor-then-sum yang sama seperti pada Galon Dipinjam. Server menjumlah dulu, memagari
     * belakangan; di sini harus persis sama.
     */
    public double balanceFor(long customerId) {
        if (customerId <= 0) return 0;
        double net = 0;
        for (Long id : new CustomerDao(dbHelper).mergedGroupIds(customerId)) {
            net += rawBalanceFor(id);
        }
        return net > 0 ? Math.round(net * 100d) / 100d : 0;
    }

    /** Σdebt − Σpayment MENTAH untuk SATU salinan pelanggan — boleh NEGATIF (lihat balanceFor). */
    private double rawBalanceFor(long customerId) {
        if (customerId <= 0) return 0;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT " + DatabaseHelper.COL_DEBT_KIND + ", SUM(" + DatabaseHelper.COL_DEBT_AMOUNT + ") " +
                        "FROM " + DatabaseHelper.TABLE_CUSTOMER_DEBTS +
                        " WHERE " + DatabaseHelper.COL_DEBT_CUSTOMER_ID + "=?" +
                        " GROUP BY " + DatabaseHelper.COL_DEBT_KIND,
                new String[]{String.valueOf(customerId)});
        double debt = 0, paid = 0;
        try {
            while (c.moveToNext()) {
                String kind = c.getString(0);
                double amt = c.getDouble(1);
                if (KIND_PAYMENT.equals(kind)) paid += amt; else debt += amt;
            }
        } finally {
            c.close();
        }
        return debt - paid;
    }

    /** Daftar {@code _id} salinan dedup + placeholder "?,?,?" untuk klausa IN. */
    private String[] groupArgs(long customerId) {
        java.util.List<Long> ids = new CustomerDao(dbHelper).mergedGroupIds(customerId);
        if (ids.isEmpty()) ids.add(customerId);
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) args[i] = String.valueOf(ids.get(i));
        return args;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "?" : ",?");
        return sb.toString();
    }

    /** Riwayat hutang & pembayaran seorang ORANG (terbaru dulu) — lintas seluruh salinan dedup,
     *  se-altitude dengan {@link #balanceFor} supaya daftar & angkanya tak pernah bercerita beda. */
    public List<Entry> historyFor(long customerId, int limit) {
        List<Entry> out = new ArrayList<>();
        if (customerId <= 0) return out;
        String[] args = groupArgs(customerId);
        Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT " + DatabaseHelper.COL_ID + ", " + DatabaseHelper.COL_DEBT_KIND + ", " +
                        DatabaseHelper.COL_DEBT_AMOUNT + ", " + DatabaseHelper.COL_DEBT_REASON + ", " +
                        DatabaseHelper.COL_CREATED_AT + ", " + DatabaseHelper.COL_DEBT_BY +
                        " FROM " + DatabaseHelper.TABLE_CUSTOMER_DEBTS +
                        " WHERE " + DatabaseHelper.COL_DEBT_CUSTOMER_ID + " IN (" + placeholders(args.length) + ")" +
                        " ORDER BY " + DatabaseHelper.COL_CREATED_AT + " DESC, " + DatabaseHelper.COL_ID + " DESC" +
                        " LIMIT " + Math.max(1, limit),
                args);
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.id = c.getLong(0);
                e.kind = c.getString(1);
                e.amount = c.getDouble(2);
                e.reason = c.getString(3);
                e.createdAt = c.getString(4);
                e.byName = c.getString(5);
                out.add(e);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Sisa hutang TANPA menghitung baris yang bertaut transaksi ini — "hutang sebelumnya".
     * Cermin {@code App\Support\DebtBalance::forExcludingTransaction} di server.
     *
     * <p>Dipakai {@link TransactionDao#applyDeliveryAdjustment} menyusun TAGIHAN DI PINTU =
     * penjualan ini + hutang sebelumnya. Memakai {@link #balanceFor} apa adanya akan MENGHITUNG DUA
     * KALI (baris hutang milik transaksi ini sendiri sudah termasuk di sana), dan mengecualikan
     * baris pembayarannya membuat perhitungan IDEMPOTEN — menekan Selesai dua kali menghasilkan
     * angka yang sama, bukan menumpuk.
     *
     * <p>🧺 Bila transaksinya leg checkout multi-lokasi (dicari dari baris transaksi LOKAL-nya —
     * leg yang sedang diselesaikan selalu ada di HP ini), hutang SEGAR leg saudaranya ikut
     * dikecualikan; lihat {@link #balanceExcludingTransaction(long, String, String)}.
     */
    public double balanceExcludingTransaction(long customerId, String trxUuid) {
        return balanceExcludingTransaction(customerId, trxUuid, checkoutUuidForTrxUuid(trxUuid));
    }

    /**
     * Seperti {@link #balanceExcludingTransaction(long, String)} dengan checkout_uuid leg yang
     * sudah diketahui pemanggil (null/kosong = order biasa → perilaku lama persis). Cermin
     * {@code DebtBalance::forExcludingTransaction} dengan pengecualian checkout_uuid di server.
     *
     * <p>Pembayaran per lokasi: tiap leg ditagih DI PINTUNYA SENDIRI untuk subtotalnya sendiri, jadi
     * HUTANG leg saudara yang baru lahir dari checkout yang sama BUKAN "hutang lama" pelanggan di
     * pintu leg ini. Dikenali lewat {@code customer_debts.checkout_uuid} — tabel itu branch-wide,
     * sedangkan transaksi saudaranya belum tentu ada di HP kurir ini, jadi JANGAN pernah berbasis
     * daftar sync_uuid saudara dari transaksi lokal. Aturan persisnya di {@link #priorDebtOf}.
     */
    public double balanceExcludingTransaction(long customerId, String trxUuid, String checkoutUuid) {
        if (customerId <= 0) return 0;
        String[] group = groupArgs(customerId);
        List<LedgerRow> rows = new ArrayList<>();
        Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT " + DatabaseHelper.COL_DEBT_KIND + ", " + DatabaseHelper.COL_DEBT_AMOUNT + ", "
                        + DatabaseHelper.COL_DEBT_TRX_UUID + ", " + DatabaseHelper.COL_DEBT_CHECKOUT_UUID
                        + " FROM " + DatabaseHelper.TABLE_CUSTOMER_DEBTS
                        + " WHERE " + DatabaseHelper.COL_DEBT_CUSTOMER_ID + " IN (" + placeholders(group.length) + ")",
                group);
        try {
            while (c.moveToNext()) {
                rows.add(new LedgerRow(c.getString(0), c.isNull(1) ? 0 : c.getDouble(1),
                        c.getString(2), c.getString(3)));
            }
        } finally {
            c.close();
        }
        return priorDebtOf(rows, trxUuid, checkoutUuid);
    }

    /** Satu baris buku besar mentah — bahan {@link #priorDebtOf} (murni, diuji di JVM). */
    static final class LedgerRow {
        final String kind;
        final double amount;
        final String trxUuid;
        final String checkoutUuid;

        LedgerRow(String kind, double amount, String trxUuid, String checkoutUuid) {
            this.kind = kind;
            this.amount = amount;
            this.trxUuid = trxUuid;
            this.checkoutUuid = checkoutUuid;
        }
    }

    /**
     * "Hutang sebelumnya" (≥ 0) dari baris buku besar seorang ORANG: Σdebt − Σpayment, dipagari 0
     * SEKALI di akhir (sama seperti {@link #balanceFor}). Dikecualikan:
     * <ol>
     *   <li>SEMUA baris bertaut transaksi ini sendiri (debt & payment) — idempoten (perilaku lama);</li>
     *   <li>baris DEBT ber-checkout_uuid sama — hutang segar leg saudara, ditagih di pintunya sendiri.</li>
     * </ol>
     * Baris PAYMENT leg saudara SENGAJA tetap dihitung: di HP pembayaran bertaut transaksi hanya lahir
     * sebagai pelunasan hutang LAMA ("Sekalian Lunasi Hutang" di leg utama, atau kelebihan bayar di
     * pintu leg lain), jadi memang mengurangi hutang lama yang ditagih di pintu ini.
     */
    static double priorDebtOf(Iterable<LedgerRow> rows, String trxUuid, String checkoutUuid) {
        boolean hasTrx = trxUuid != null && !trxUuid.isEmpty();
        boolean hasCheckout = checkoutUuid != null && !checkoutUuid.trim().isEmpty();
        String co = hasCheckout ? checkoutUuid.trim() : null;
        double debt = 0, paid = 0;
        for (LedgerRow r : rows) {
            if (r == null) continue;
            if (hasTrx && trxUuid.equals(r.trxUuid)) continue;
            boolean isPayment = KIND_PAYMENT.equals(r.kind);
            if (hasCheckout && !isPayment && r.checkoutUuid != null && co.equals(r.checkoutUuid.trim())) continue;
            if (isPayment) paid += r.amount; else debt += r.amount;
        }
        double sisa = debt - paid;
        return sisa > 0 ? Math.round(sisa * 100d) / 100d : 0;
    }

    /** checkout_uuid transaksi LOKAL ber-sync_uuid ini; null bila bukan leg / tak ada di HP ini. */
    public String checkoutUuidForTrxUuid(String trxUuid) {
        return trxRefs(trxUuid)[1];
    }

    /**
     * Selaraskan baris PEMBAYARAN milik SEBUAH TRANSAKSI dengan nominal yang benar-benar diterima —
     * cermin {@link #syncForTransaction} untuk sisi kredit, dan cermin
     * {@code App\Support\DebtBalance::syncPaymentForTransaction} di server. IDEMPOTEN: menekan
     * "Selesai" berkali-kali menyetel ulang baris yang sama, bukan menumpuk pembayaran baru.
     */
    public void syncPaymentForTransaction(long customerId, String trxUuid, double amount,
                                          String byName, String reason) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        double amt = Math.round(Math.max(0, amount) * 100d) / 100d;
        if (amt <= 0 || customerId <= 0) {
            voidPaymentForTransaction(trxUuid);
            return;
        }
        long rowId = -1;
        Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT " + DatabaseHelper.COL_ID + " FROM " + DatabaseHelper.TABLE_CUSTOMER_DEBTS
                        + " WHERE " + DatabaseHelper.COL_DEBT_TRX_UUID + "=? AND "
                        + DatabaseHelper.COL_DEBT_KIND + "='" + KIND_PAYMENT + "' ORDER BY "
                        + DatabaseHelper.COL_ID + " LIMIT 1",
                new String[]{trxUuid});
        try {
            if (c.moveToFirst()) rowId = c.getLong(0);
        } finally {
            c.close();
        }
        if (rowId <= 0) {
            write(customerId, KIND_PAYMENT, amt, reason, trxUuid, byName);
            return;
        }
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DEBT_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_DEBT_AMOUNT, amt);
        if (reason != null && !reason.isEmpty()) v.put(DatabaseHelper.COL_DEBT_REASON, reason);
        dbHelper.syncUpdate(dbHelper.getWritableDatabase(), DatabaseHelper.TABLE_CUSTOMER_DEBTS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(rowId)});
    }

    /** Buang baris PEMBAYARAN milik sebuah transaksi (tombstone — barisnya bisa sudah ter-push). */
    public void voidPaymentForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        dbHelper.syncDelete(dbHelper.getWritableDatabase(), DatabaseHelper.TABLE_CUSTOMER_DEBTS,
                "customer_debts",
                DatabaseHelper.COL_DEBT_TRX_UUID + "=? AND " + DatabaseHelper.COL_DEBT_KIND + "='" + KIND_PAYMENT + "'",
                new String[]{trxUuid});
    }

    /**
     * Catat hutang BARU — dari penjualan yang dibayar "HUTANG" (bawa {@code trxUuid}) atau
     * pencatatan manual dari kartu pelanggan ({@code trxUuid} null).
     */
    public void charge(long customerId, double amount, String reason, String trxUuid, String byName) {
        if (customerId <= 0 || amount <= 0) return;
        write(customerId, KIND_DEBT, Math.round(amount * 100d) / 100d, reason, trxUuid, byName);
    }

    /**
     * Catat PEMBAYARAN hutang (boleh sebagian). Nominal dibatasi sisa hutang yang benar-benar ada
     * (dihitung ulang di sini — jangan percaya angka dari form). Mengembalikan nominal yang
     * BENAR-BENAR tercatat (0 = tak ada sisa hutang).
     */
    public double pay(long customerId, double amount, String trxUuid, String byName, String reason) {
        if (customerId <= 0 || amount <= 0) return 0;
        double sisa = balanceFor(customerId);
        double bayar = Math.min(amount, sisa);
        bayar = Math.round(bayar * 100d) / 100d;
        if (bayar <= 0) return 0;
        write(customerId, KIND_PAYMENT, bayar, reason, trxUuid, byName);
        return bayar;
    }

    /**
     * Pelunasan hutang LAMA yang dibayarkan LEWAT transaksi ini ("Sekalian Lunasi Hutang" saat
     * checkout, atau bayar sebagian saat Selesai) — uang TAMBAHAN di luar tagihan penjualan itu
     * sendiri. Cermin {@code App\Support\DebtBalance::paidForTransaction} di server. Struk memakainya
     * untuk mencetak "Pelunasan hutang: Rp X" supaya pelanggan tahu uang yang benar-benar ia
     * serahkan hari ini, bukan cuma nilai penjualannya.
     */
    public double paidForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return 0;
        android.database.Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(" + DatabaseHelper.COL_DEBT_AMOUNT + "), 0) FROM "
                        + DatabaseHelper.TABLE_CUSTOMER_DEBTS
                        + " WHERE " + DatabaseHelper.COL_DEBT_TRX_UUID + "=? AND "
                        + DatabaseHelper.COL_DEBT_KIND + "='" + KIND_PAYMENT + "'",
                new String[]{trxUuid});
        double sum = 0;
        try {
            if (c.moveToFirst()) sum = c.getDouble(0);
        } finally {
            c.close();
        }
        return sum;
    }

    /** {receipt_no, checkout_uuid} baris transactions ber-sync_uuid ini; elemen null bila tak
     *  ada/tak ditemukan (satu query untuk keduanya — write() butuh dua-duanya per baris hutang). */
    private String[] trxRefs(String trxUuid) {
        String[] out = new String[2];
        if (trxUuid == null || trxUuid.isEmpty()) return out;
        Cursor c = dbHelper.getReadableDatabase().query(DatabaseHelper.TABLE_TRANSACTIONS,
                new String[]{DatabaseHelper.COL_RECEIPT_NO, DatabaseHelper.COL_CHECKOUT_UUID},
                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{trxUuid}, null, null, null, "1");
        try {
            if (c.moveToFirst()) {
                out[0] = c.getString(0);
                String co = c.getString(1);
                out[1] = (co != null && !co.trim().isEmpty()) ? co.trim() : null;
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * ID transaksi (receipt_no) dari baris hutang MASIH TERUTANG milik pelanggan ini — dicetak di
     * struk supaya pelanggan tahu hutangnya berasal dari pembelian yang mana. Cermin
     * App\Support\DebtBalance::originReceiptsFor di web (default 1).
     *
     * <p>SENGAJA hanya SATU nomor (yang TERBARU): buku besarnya pooled, bukan FIFO per-invoice,
     * jadi merangkai beberapa nomor sekaligus MENYESATKAN — seolah seluruh sisa hutang berasal dari
     * semua pembelian itu, padahal pembayaran yang masuk tak bisa dipetakan ke invoice tertentu.
     */
    public List<String> originReceiptsFor(long customerId, int limit) {
        List<String> out = new ArrayList<>();
        if (customerId <= 0) return out;
        String[] args = groupArgs(customerId);
        Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT " + DatabaseHelper.COL_DEBT_REASON + " FROM " + DatabaseHelper.TABLE_CUSTOMER_DEBTS +
                        " WHERE " + DatabaseHelper.COL_DEBT_CUSTOMER_ID + " IN (" + placeholders(args.length) + ") AND " +
                        DatabaseHelper.COL_DEBT_KIND + "='" + KIND_DEBT + "' AND " +
                        DatabaseHelper.COL_DEBT_REASON + " IS NOT NULL" +
                        " ORDER BY " + DatabaseHelper.COL_ID + " DESC",
                args);
        try {
            while (c.moveToNext() && out.size() < Math.max(1, limit)) {
                String reason = c.getString(0);
                // Hanya reason berbentuk receipt_no (KODE-DDMMYY-N lama atau KODE-DDMMYYHHMM-XXXXX
                // baru) — reason lama/manual (teks bebas) bukan rujukan struk yang bisa
                // ditelusuri, jadi dilewati.
                if (ReceiptNo.isReceiptNo(reason) && !out.contains(reason)) {
                    out.add(reason);
                }
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Buang hutang milik sebuah transaksi — dipakai saat pembuatan transaksi GAGAL setelah
     * hutangnya terlanjur tercatat, supaya pelanggan tak ditagih untuk penjualan yang tak jadi.
     * Baris di-hard delete: ia belum pernah ter-push (masih dirty) pada alur kegagalan itu.
     */
    public void revokeForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        dbHelper.getWritableDatabase().delete(DatabaseHelper.TABLE_CUSTOMER_DEBTS,
                DatabaseHelper.COL_DEBT_TRX_UUID + "=? AND " + DatabaseHelper.COL_DEBT_KIND + "='" + KIND_DEBT + "'",
                new String[]{trxUuid});
    }

    /**
     * Selaraskan baris hutang milik SEBUAH TRANSAKSI dengan keadaan transaksi itu sekarang — dipakai
     * saat metode bayarnya diubah ke/dari "HUTANG". Cermin {@code App\Support\DebtBalance
     * ::syncForTransaction} di server. Baris PEMBAYARAN tak disentuh: cicilan yang terlanjur
     * diterima adalah fakta, dan sisa hutang cukup jatuh ke 0 karena pembacaan dipagari di 0.
     */
    public void syncForTransaction(long customerId, String trxUuid, String paymentMethod,
                                   double total, String byName) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        boolean wanted = "HUTANG".equals(paymentMethod) && customerId > 0 && total > 0;

        Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT " + DatabaseHelper.COL_ID + " FROM " + DatabaseHelper.TABLE_CUSTOMER_DEBTS +
                        " WHERE " + DatabaseHelper.COL_DEBT_TRX_UUID + "=? AND " +
                        DatabaseHelper.COL_DEBT_KIND + "='" + KIND_DEBT + "' ORDER BY " +
                        DatabaseHelper.COL_ID + " LIMIT 1",
                new String[]{trxUuid});
        long rowId = -1;
        try {
            if (c.moveToFirst()) rowId = c.getLong(0);
        } finally {
            c.close();
        }

        if (!wanted) {
            voidForTransaction(trxUuid);
            return;
        }
        if (rowId <= 0) {
            charge(customerId, total, "Penjualan dibayar nanti", trxUuid, byName);
            return;
        }
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DEBT_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_DEBT_AMOUNT, Math.round(total * 100d) / 100d);
        // 🧺 Baris hutang leg yang belum membawa checkout_uuid (mis. ditulis server sebelum ia ikut
        // menyalinnya) dilengkapi sekalian — nilainya imutabel, jadi menimpanya selalu aman.
        String checkoutUuid = checkoutUuidForTrxUuid(trxUuid);
        if (checkoutUuid != null) v.put(DatabaseHelper.COL_DEBT_CHECKOUT_UUID, checkoutUuid);
        dbHelper.syncUpdate(dbHelper.getWritableDatabase(), DatabaseHelper.TABLE_CUSTOMER_DEBTS, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(rowId)});
    }

    /**
     * Buang hutang milik sebuah transaksi yang DI-VOID / tak lagi berhutang. Memakai syncDelete
     * (tombstone) — bukan hard delete seperti {@link #revokeForTransaction}: barisnya bisa sudah
     * ter-push, dan hard delete lokal akan membuatnya hidup lagi pada pull berikutnya.
     */
    public void voidForTransaction(String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        dbHelper.syncDelete(dbHelper.getWritableDatabase(), DatabaseHelper.TABLE_CUSTOMER_DEBTS,
                "customer_debts",
                DatabaseHelper.COL_DEBT_TRX_UUID + "=? AND " + DatabaseHelper.COL_DEBT_KIND + "='" + KIND_DEBT + "'",
                new String[]{trxUuid});
    }

    private void write(long customerId, String kind, double amount, String reason, String trxUuid, String byName) {
        // ALASAN HUTANG = ID transaksi struk (App\Support\ReceiptNumber) bila baris ini lahir dari
        // penjualan yang bisa dikenali — cermin App\Support\DebtBalance::write di web. Hanya untuk
        // baris `debt` (bukan `payment` — reason pembayaran tetap teks manusiawi); transaksi lama/
        // tanpa receipt_no jatuh ke teks default pemanggil apa adanya.
        // 🧺 Hutang leg checkout multi-lokasi ikut membawa checkout_uuid leg-nya (cermin
        // DebtBalance::syncForTransaction di web) — dasar pengecualian di pintu leg saudara. Hanya baris
        // `debt`: pembayaran bertaut transaksi = pelunasan hutang LAMA, tetap berlaku di pintu mana pun.
        String checkoutUuid = null;
        if (KIND_DEBT.equals(kind) && trxUuid != null && !trxUuid.isEmpty()) {
            String[] refs = trxRefs(trxUuid);
            String receiptNo = refs[0];
            if (receiptNo != null && !receiptNo.isEmpty()) reason = receiptNo;
            checkoutUuid = refs[1];
        }
        String now = DatabaseHelper.nowIso();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DEBT_CUSTOMER_ID, customerId);
        v.put(DatabaseHelper.COL_DEBT_KIND, kind);
        v.put(DatabaseHelper.COL_DEBT_AMOUNT, amount);
        if (reason != null && !reason.isEmpty()) v.put(DatabaseHelper.COL_DEBT_REASON, reason);
        if (trxUuid != null && !trxUuid.isEmpty()) v.put(DatabaseHelper.COL_DEBT_TRX_UUID, trxUuid);
        if (checkoutUuid != null) v.put(DatabaseHelper.COL_DEBT_CHECKOUT_UUID, checkoutUuid);
        if (byName != null && !byName.isEmpty()) v.put(DatabaseHelper.COL_DEBT_BY, byName);
        v.put(DatabaseHelper.COL_CREATED_AT, now);
        v.put(DatabaseHelper.COL_SYNC_UUID, UUID.randomUUID().toString());
        v.put(DatabaseHelper.COL_EDITED_AT, now);
        v.put(DatabaseHelper.COL_SYNCED, 0);
        dbHelper.getWritableDatabase().insert(DatabaseHelper.TABLE_CUSTOMER_DEBTS, null, v);
    }
}
