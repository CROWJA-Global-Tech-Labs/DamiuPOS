package com.crowja.damiupos.checkout;

import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 🧺 Satu BAGIAN checkout multi-lokasi di Transaksi Baru HP ("Bagi ke beberapa lokasi"): isi order
 * untuk SATU lokasi pelanggan. Tiap bagian bergalon &gt; 0 disimpan sebagai satu leg JUAL biasa
 * (lihat TransactionActivity.doSaveCheckout) dan dihargai PERSIS seperti order tunggal ke lokasi itu:
 * harga = harga lokasi → harga pelanggan → harga jual (+ komisi reseller bila komisi-ke-harga), ongkir
 * dari tarif lokasinya sendiri — tak pernah satu ongkir untuk seluruh checkout.
 *
 * <p>Aturan default ongkir SAMA dengan web/agen: tarif lokasi &gt; 0 → Per Botol dengan nominal tarif
 * itu, selain itu Tanpa. TIDAK ada fallback "ongkir order terakhir" di mode ini (order tunggal HP
 * masih memakainya) supaya pelanggan yang sama dibagi identik di HP, web, dan agen.
 *
 * <p>Kelas murni (tanpa Android) agar bisa diuji di JVM — lihat CheckoutPartTest.
 */
public final class CheckoutPart {

    /** Pemisah catatan checkout ↔ catatan lokasi — SAMA dengan pemisah yang dipecah struk gabungan
     *  (CheckoutStrukText) dan server (StrukWa) menjadi catatan bersama + catatan per 📍 lokasi. */
    public static final String LEG_NOTE_SEP = " · ";

    // Gerbang rollout "boleh membuat checkout" BUKAN di sini lagi: vonis server dari /api/me
    // (SyncSettings.isCheckoutMultiEnabled). app_settings 'checkout_multi_enabled' tak pernah sampai ke
    // HP (bukan SHAREABLE_KEYS) — membacanya hanya membuat fitur ini tak terjangkau.

    /** Lokasi tujuan bagian ini (salah satu {@code Customer.getLocations()}, berkoordinat). */
    public final Customer.Location loc;
    /** Indeks lokasi di daftar lokasi pelanggan (0 = utama). */
    public final int locIndex;

    /** Jumlah per produk (id lokal produk) — urutan = urutan produk di form. */
    public final LinkedHashMap<Long, Integer> qtyByProduct = new LinkedHashMap<>();
    /** Harga per pcs per produk — diisi dari harga lokasi ini, boleh diubah operator. */
    public final Map<Long, Double> priceByProduct = new HashMap<>();

    /** {@link Transaction#ONGKIR_NONE} / {@link Transaction#ONGKIR_PER_GALON} / {@link Transaction#ONGKIR_BORONGAN}. */
    public String ongkirType = Transaction.ONGKIR_NONE;
    /** Per Botol: tarif per galon. Borongan: nominal sekali. Tanpa: diabaikan. Disimpan apa adanya ke
     *  kolom transactions.ongkir — konvensi yang sama dengan order tunggal. */
    public double ongkirRate;
    /** Nominal ongkir sudah diketik operator → jangan ditimpa default otomatis lagi. */
    public boolean ongkirEdited;
    /** Lokasi wajib ongkir: tarif lokasi &gt; 0 (aturan web/agen, lihat {@link #isWajib}). */
    public boolean wajib;

    /** Galon kosong yang ditukar di lokasi ini (hanya bermakna untuk kepemilikan "Di Pinjam"). */
    public int kembali;
    /** Jumlah kembali sudah diketik operator → tak diikutkan pembagian default lagi. */
    public boolean kembaliEdited;

    /** Perangkat yang mengantar bagian ini; null = perangkat ini. Bisa sentinel "Pesanan Terbuka". */
    public String assignedDeviceUuid;
    /** Catatan khusus lokasi ini (mis. "titip satpam") — disambung ke catatan checkout dengan " · ". */
    public String note = "";

    public CheckoutPart(Customer.Location loc, int locIndex) {
        this.loc = loc;
        this.locIndex = locIndex;
    }

    /** Bagian baru dengan default ongkir lokasi (aturan web/agen) — jumlah & harga diisi pemanggil. */
    public static CheckoutPart withDefaults(Customer.Location loc, int locIndex) {
        CheckoutPart p = new CheckoutPart(loc, locIndex);
        double rate = locationRate(loc);
        p.ongkirType = rate > 0 ? Transaction.ONGKIR_PER_GALON : Transaction.ONGKIR_NONE;
        p.ongkirRate = rate;
        p.wajib = isWajib(loc);
        return p;
    }

    /** Salinan dalam — lembar pembagian mengedit salinan, baru disalin balik saat "Simpan pembagian". */
    public CheckoutPart copy() {
        CheckoutPart c = new CheckoutPart(loc, locIndex);
        c.qtyByProduct.putAll(qtyByProduct);
        c.priceByProduct.putAll(priceByProduct);
        c.ongkirType = ongkirType;
        c.ongkirRate = ongkirRate;
        c.ongkirEdited = ongkirEdited;
        c.wajib = wajib;
        c.kembali = kembali;
        c.kembaliEdited = kembaliEdited;
        c.assignedDeviceUuid = assignedDeviceUuid;
        c.note = note;
        return c;
    }

    // ------------------------------------------------------------------ lokasi

    /** Tarif ongkir lokasi (Rp/galon) dari server; 0 bila tak ada — sama dengan rateOf di Transaksi Baru. */
    public static double locationRate(Customer.Location l) {
        return l != null && l.ongkir != null && !l.ongkir.isNaN() && l.ongkir > 0 ? l.ongkir : 0;
    }

    /**
     * Wajib ongkir = lokasi BERTARIF (&gt; 0) — aturan yang SAMA dengan web/agen. Flag lama
     * {@code loc.wajibOngkir} sengaja diabaikan di mode bagi lokasi: tanpa tarif tak ada nominal
     * default, dan HP yang menegurnya sementara web/agen tidak hanya membuat ketiganya berbeda vonis.
     */
    public static boolean isWajib(Customer.Location l) {
        return l != null && locationRate(l) > 0;
    }

    /** Lokasi layak jadi leg: punya koordinat sungguhan — server (locationsOrDefault) membuang (0,0). */
    public static boolean hasCoordinates(Customer.Location l) {
        return l != null && (l.lat != 0 || l.lng != 0);
    }

    /** Nama lokasi untuk ditampilkan & disimpan sebagai delivery_dest_name. */
    public static String nameOf(Customer.Location l) {
        String n = l != null && l.name != null ? l.name.trim() : "";
        return n.isEmpty() ? Customer.DEFAULT_LOCATION_NAME : n;
    }

    public String name() {
        return nameOf(loc);
    }

    /**
     * Indeks lokasi yang boleh dibagi: berkoordinat, nama unik (tanpa beda kapital/spasi — server
     * menolak lokasi yang sama dua kali), paling banyak {@link CheckoutConstants#MAX_LEGS}. Urutan asli
     * dipertahankan (lokasi utama dulu).
     */
    public static List<Integer> splittableIndexes(List<Customer.Location> locations) {
        List<Integer> out = new ArrayList<>();
        if (locations == null) return out;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < locations.size() && out.size() < CheckoutConstants.MAX_LEGS; i++) {
            Customer.Location l = locations.get(i);
            if (!hasCoordinates(l)) continue;
            if (!seen.add(nameOf(l).toLowerCase(Locale.ROOT))) continue;
            out.add(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ isi

    public int qty(long productId) {
        Integer q = qtyByProduct.get(productId);
        return q != null ? q : 0;
    }

    public void setQty(long productId, int qty) {
        qtyByProduct.put(productId, Math.max(0, qty));
    }

    public double price(long productId) {
        Double p = priceByProduct.get(productId);
        return p != null ? p : 0;
    }

    public void setPrice(long productId, double price) {
        priceByProduct.put(productId, Math.max(0, price));
    }

    /** Jumlah galon bagian ini (semua jenis air dijumlah — sama dengan totalJumlah order tunggal). */
    public int galon() {
        int sum = 0;
        for (Integer q : qtyByProduct.values()) if (q != null && q > 0) sum += q;
        return sum;
    }

    /** Bagian ini jadi leg? Hanya bila ada galon yang dikirim ke lokasinya. */
    public boolean isActive() {
        return galon() > 0;
    }

    public double itemsSubtotal() {
        double sum = 0;
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            int q = e.getValue() != null ? e.getValue() : 0;
            if (q > 0) sum += q * price(e.getKey());
        }
        return sum;
    }

    /** Ongkir bagian ini: Per Botol = tarif × max(1, galon), Borongan = nominal, Tanpa = 0. */
    public double ongkirTotal() {
        double rate = Math.max(0, ongkirRate);
        if (Transaction.ONGKIR_PER_GALON.equals(ongkirType)) return rate * Math.max(1, galon());
        if (Transaction.ONGKIR_BORONGAN.equals(ongkirType)) return rate;
        return 0;
    }

    /** Nilai kolom transactions.ongkir untuk leg ini (tarif Per Botol / nominal Borongan / 0). */
    public double storedOngkir() {
        return Transaction.ONGKIR_NONE.equals(ongkirType) ? 0 : Math.max(0, ongkirRate);
    }

    /** Biaya botol galon yang dibeli (kepemilikan BELI) — dihitung per leg seperti order tunggal. */
    public double botolTotal(boolean beli, double hargaBotol) {
        return beli ? Math.max(0, hargaBotol) * galon() : 0;
    }

    /** Total leg = item + ongkir + botol — rumus yang sama dengan order tunggal (dan composeOrder server). */
    public double total(boolean beli, double hargaBotol) {
        return itemsSubtotal() + ongkirTotal() + botolTotal(beli, hargaBotol);
    }

    /** Lokasi wajib ongkir tapi bagian ini tanpa ongkir (Rp 0). */
    public boolean ongkirMissing() {
        return wajib && ongkirTotal() <= 0;
    }

    /** Lokasi bebas ongkir tapi bagian ini ditagih ongkir. */
    public boolean ongkirUnexpected() {
        return !wajib && ongkirTotal() > 0;
    }

    /** Baris item terbayar (jumlah &gt; 0) dalam urutan produk form. */
    public List<TransactionItem> toItems(List<Product> products) {
        List<TransactionItem> out = new ArrayList<>();
        if (products == null) return out;
        for (Product p : products) {
            int q = qty(p.getId());
            if (q <= 0) continue;
            out.add(new TransactionItem(p.getId(), p.getName(), q, price(p.getId())));
        }
        return out;
    }

    /**
     * Ringkasan satu baris untuk popup konfirmasi:
     * "1× Galon Rp 6.000 + ongkir Rp 2.000 = Rp 8.000" (+ "botol Rp …" bila beli botol).
     */
    public String summary(List<Product> products, boolean beli, double hargaBotol) {
        NumberFormat nf = rupiah();
        StringBuilder sb = new StringBuilder();
        for (TransactionItem it : toItems(products)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.jumlah).append("× ").append(it.productName)
                    .append(" Rp ").append(nf.format(Math.round(it.hargaPerGalon)));
        }
        double ongkir = ongkirTotal();
        if (ongkir > 0) sb.append(" + ongkir Rp ").append(nf.format(Math.round(ongkir)));
        double botol = botolTotal(beli, hargaBotol);
        if (botol > 0) sb.append(" + botol Rp ").append(nf.format(Math.round(botol)));
        sb.append(" = Rp ").append(nf.format(Math.round(total(beli, hargaBotol))));
        return sb.toString();
    }

    // ------------------------------------------------------------------ aturan bersama

    /**
     * Harga per pcs satu produk — rumus applyResellerPricing di Transaksi Baru: harga khusus
     * (lokasi → pelanggan, sudah dilebur di {@code override}) atau harga jual, ditambah komisi
     * reseller (override per produk, fallback rate global) bila reseller memakai komisi-ke-harga.
     */
    public static double unitPrice(double hargaJual, Double override, boolean addKomisi,
                                   Double komisiOverride, double globalKomisi) {
        double price = override != null ? override : hargaJual;
        if (addKomisi) price += komisiOverride != null ? komisiOverride : globalKomisi;
        return price;
    }

    /** Bagian yang benar-benar jadi leg (bergalon &gt; 0), urutan dipertahankan. */
    public static List<CheckoutPart> active(List<CheckoutPart> parts) {
        List<CheckoutPart> out = new ArrayList<>();
        if (parts == null) return out;
        for (CheckoutPart p : parts) if (p != null && p.isActive()) out.add(p);
        return out;
    }

    /** Σ total bagian aktif. */
    public static double grandTotal(List<CheckoutPart> parts, boolean beli, double hargaBotol) {
        double sum = 0;
        for (CheckoutPart p : active(parts)) sum += p.total(beli, hargaBotol);
        return sum;
    }

    /**
     * Bagi {@code amount} secara RAKUS mengikuti urutan leg: leg k menerima min(sisa, caps[k]).
     * Dipakai saldo komisi (caps = total leg) lalu saldo refund (caps = total leg − saldo leg) — jadi
     * tiap leg membawa penanda [SALDO KOMISI]/[REFUND]-nya sendiri dan tak pernah melebihi totalnya.
     */
    public static double[] allocate(double amount, double[] caps) {
        double[] out = new double[caps != null ? caps.length : 0];
        double left = Math.max(0, amount);
        for (int i = 0; i < out.length && left > 0; i++) {
            double take = Math.min(left, Math.max(0, caps[i]));
            out[i] = take;
            left -= take;
        }
        return out;
    }

    /**
     * Default galon kembali per bagian: rakus mengikuti urutan bagian, min(galon bagian, sisa galon
     * DIPINJAM) — cermin default order tunggal (min(jual, dipinjam)), tak pernah melebihi stok pinjam.
     */
    public static int[] defaultKembali(int[] galons, int held) {
        int[] out = new int[galons != null ? galons.length : 0];
        int left = Math.max(0, held);
        for (int i = 0; i < out.length; i++) {
            int take = Math.min(Math.max(0, galons[i]), left);
            out[i] = take;
            left -= take;
        }
        return out;
    }

    /**
     * Tanggal DASAR leg 1 ("yyyy-MM-dd HH:mm:ss"; leg k = dasar + (k−1) detik, lihat
     * {@link CheckoutConstants#legTanggal}). Tertunda → jadwal lanjut (semua leg ikut jadwal). Selain
     * itu max(tanggal terpilih, sekarang): mode bagi lokasi untuk pengiriman, jadi tanggal mundur
     * dinaikkan ke sekarang — server menggeser tanggal JUAL yang jauh di belakang waktu antrinya,
     * sedangkan KEMBALI berpasangannya tidak, dan pasangan JUAL↔KEMBALI per leg pun putus.
     */
    public static String baseTanggal(String selectedDb, String nowDb, boolean tertunda, String resumeDb) {
        if (tertunda && resumeDb != null && resumeDb.trim().length() >= 19) return resumeDb.trim().substring(0, 19);
        String now = nowDb != null ? nowDb.trim() : "";
        String sel = selectedDb != null ? selectedDb.trim() : "";
        if (sel.length() < 19) return now.length() >= 19 ? now.substring(0, 19) : null;
        if (now.length() < 19) return sel.substring(0, 19);
        sel = sel.substring(0, 19);
        now = now.substring(0, 19);
        // Format tetap lebar & berurutan besar→kecil → perbandingan string = perbandingan waktu.
        return sel.compareTo(now) >= 0 ? sel : now;
    }

    /** Catatan satu leg: catatan checkout + " · " + catatan lokasinya (yang kosong dilewati) — pemisah
     *  yang dipakai struk gabungan untuk memisahkan catatan bersama dari catatan per lokasi. */
    public static String legNote(String checkoutNote, String partNote) {
        String a = checkoutNote != null ? checkoutNote.trim() : "";
        String b = partNote != null ? partNote.trim() : "";
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + LEG_NOTE_SEP + b;
    }

    private static NumberFormat rupiah() {
        return NumberFormat.getInstance(new Locale("id", "ID"));
    }
}
