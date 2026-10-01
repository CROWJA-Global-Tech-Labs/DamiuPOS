package com.crowja.damiupos.util;

/**
 * Keputusan murni (tanpa Android) gerbang foto bukti SELESAI di antrean pengiriman: wajib atau
 * tidak, dan pengantar mana yang tampil sebelum kamera dibuka. Dipisah dari
 * {@code DeliveryQueueActivity.doComplete} — satu-satunya pintu yang dilewati SEMUA jalur Selesai di
 * HP (tombol ✓ kartu, Selesai mode terpandu, "Ubah…", gerbang Bermasalah/Data Belum Lengkap) —
 * supaya urutan alasannya bisa diuji di JVM.
 *
 * <p>Urutan alasan (yang pertama menang, hanya menentukan TEKS pengantar — wajibnya sama):
 * <ol>
 *   <li>{@link Reason#CASH_BON} — uang tak diterima; fotonya satu-satunya bukti galon sampai, dan
 *       ikut terkirim ke pelanggan bersama konfirmasi WA-nya.</li>
 *   <li>{@link Reason#RESELLER} — pesanan link reseller (flag server {@code delivery_proof_required}
 *       ATAU penanda {@code [ORDER RESELLER]}); reseller melihat fotonya di halaman pesanannya.</li>
 *   <li>{@link Reason#BRANCH} — setelan cabang mewajibkan foto untuk SEMUA order (tanpa pengantar,
 *       kamera langsung dibuka seperti sebelumnya).</li>
 * </ol>
 *
 * <p>Begitu wajib (alasan apa pun selain {@link Reason#NONE}), TIDAK ada jalan pintas: izin kamera
 * ditolak, kamera dibatalkan, atau perangkat tanpa aplikasi kamera — semuanya membiarkan order tetap
 * di antrean. Foto opsional (NONE) tetap boleh dilewati.
 */
public final class DeliveryProofPolicy {

    private DeliveryProofPolicy() {
    }

    public enum Reason { NONE, CASH_BON, RESELLER, BRANCH }

    /**
     * @param branchRequired setelan cabang {@code delivery_proof_required} aktif
     * @param cashBon        catatan order memuat penanda {@code [CASH BON]}
     * @param orderRequired  order ini sendiri mewajibkan foto ({@code Transaction.requiresDeliveryProof})
     */
    public static Reason reason(boolean branchRequired, boolean cashBon, boolean orderRequired) {
        if (cashBon) return Reason.CASH_BON;
        if (orderRequired) return Reason.RESELLER;
        if (branchRequired) return Reason.BRANCH;
        return Reason.NONE;
    }

    /** Foto wajib → order TIDAK boleh selesai tanpa foto, termasuk saat tak ada aplikasi kamera. */
    public static boolean isRequired(Reason r) {
        return r != null && r != Reason.NONE;
    }
}
