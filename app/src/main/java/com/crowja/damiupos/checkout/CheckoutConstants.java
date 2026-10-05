package com.crowja.damiupos.checkout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 🧺 CHECKOUT MULTI-LOKASI — konstanta & aturan murni bersama HP (Transaksi Baru, struk, antrean).
 *
 * <p>Satu checkout = N baris JUAL biasa ("leg"), satu per lokasi tujuan, diikat trio
 * {@code checkout_uuid / checkout_seq / checkout_size} (lihat DatabaseHelper.COL_CHECKOUT_UUID).
 * Tiap leg dihargai PERSIS seperti order tunggal ke lokasinya, punya receipt_no, token lacak,
 * perangkat tujuan, hutang & KEMBALI-nya sendiri — dan DIBAYAR DI PINTUNYA SENDIRI untuk
 * subtotalnya sendiri (satu metode bayar untuk semua leg, tanpa stempel "dibayar untuk semua").
 * Cermin {@code App\Models\Transaction::MAX_CHECKOUT_LEGS} dkk. di web.
 *
 * <p>Kelas murni (tanpa Android) agar bisa diuji di JVM.
 */
public final class CheckoutConstants {

    private CheckoutConstants() {}

    /** Intent extra ReceiptActivity: uuid checkout → struk GABUNGAN semua leg (bukan satu baris). */
    public static final String EXTRA_CHECKOUT_UUID = "checkout_uuid";

    /** Minimal leg satu checkout — di bawah ini ya order biasa. */
    public static final int MIN_LEGS = 2;

    /** Batas leg satu checkout — cermin {@code Transaction::MAX_CHECKOUT_LEGS} di server. */
    public static final int MAX_LEGS = 10;

    /** Selisih tanggal antar leg: leg k = dasar + (k−1) detik. WAJIB detik bulat — format tanggal HP
     *  "yyyy-MM-dd HH:mm:ss" — supaya tiap leg tanggal-nya beda dan pasangan JUAL↔KEMBALI (pelanggan +
     *  tanggal PERSIS) tetap menunjuk leg yang benar. */
    public static final long LEG_TANGGAL_STEP_MS = 1000L;

    /** Format kolom tanggal transaksi buatan HP (waktu lokal). */
    public static final String TANGGAL_FMT = "yyyy-MM-dd HH:mm:ss";

    /** Ikon badge leg di kartu antrean / struk ("🧺 1/2 · Rumah"). */
    public static final String BADGE_ICON = "🧺";

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** uuid checkout baru (sekali per checkout, dibagi semua leg-nya). */
    public static String newCheckoutUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Trio checkout sah? Aturan yang SAMA dengan sanitasi server saat insert: uuid berbentuk uuid,
     * {@link #MIN_LEGS} ≤ size ≤ {@link #MAX_LEGS}, 1 ≤ seq ≤ size. Trio tak sah = order biasa
     * (TransactionDao.insert tak menulisnya sama sekali, tak pernah setengah-setengah).
     */
    public static boolean isValidTrio(String checkoutUuid, int seq, int size) {
        if (checkoutUuid == null || !UUID_RE.matcher(checkoutUuid.trim()).matches()) return false;
        if (size < MIN_LEGS || size > MAX_LEGS) return false;
        return seq >= 1 && seq <= size;
    }

    /**
     * Tanggal leg ke-{@code seq} dari tanggal dasar {@code baseDb} ("yyyy-MM-dd HH:mm:ss"): dasar +
     * (seq−1) detik. Aritmetika jam-dinding murni (diurai & diformat di UTC, tanpa konversi zona —
     * pola SyncSettings.trxLookbackCursor), jadi hasilnya tetap waktu lokal yang sama formatnya.
     * Null bila dasar kosong / tak bisa diurai / seq &lt; 1 — pemanggil wajib menolak menyimpan,
     * karena leg bertanggal kembar merusak pasangan KEMBALI-nya.
     */
    public static String legTanggal(String baseDb, int seq) {
        if (baseDb == null || seq < 1) return null;
        String s = baseDb.trim();
        if (s.length() < 19) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat(TANGGAL_FMT, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            Date d = f.parse(s.substring(0, 19));
            if (d == null) return null;
            return f.format(new Date(d.getTime() + (seq - 1) * LEG_TANGGAL_STEP_MS));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Badge leg untuk kartu antrean / struk: "🧺 1/2 · Rumah" (tanpa " · …" bila nama tujuan kosong).
     * Cukup dari seq/size baris INI — HP kurir sering hanya memegang satu leg. "" bila bukan leg.
     */
    public static String badge(int seq, int size, String destName) {
        if (size < MIN_LEGS || size > MAX_LEGS || seq < 1 || seq > size) return "";
        String dest = destName != null ? destName.trim() : "";
        return BADGE_ICON + " " + seq + "/" + size + (dest.isEmpty() ? "" : " · " + dest);
    }
}
