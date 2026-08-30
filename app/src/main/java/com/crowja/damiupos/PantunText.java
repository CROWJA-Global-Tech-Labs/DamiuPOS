package com.crowja.damiupos;

/**
 * Perakitan teks PANTUN follow-up di sisi HP.
 *
 * CERMIN PERSIS {@code App\Support\PantunText} di web — ubah keduanya bersamaan (pola yang sama
 * dengan DeliveryPlan.php ⟷ DeliveryPlanner.java). Yang dicerminkan di sini HANYA bagian yang
 * dibutuhkan saat MENGIRIM (render + pangkas merek); aturan validasi bentuk (rima/suku kata/panjang)
 * sengaja TIDAK ikut dibawa, sebab pantun tak pernah dikarang di HP — HP cuma memakai yang sudah
 * lolos validasi di server.
 */
public final class PantunText {

    private PantunText() {}

    /** Batas nama merek — dipangkas saat BACA, sama seperti sisi web. */
    public static final int BRAND_MAX = 24;

    /** Token merek di dalam teks tersimpan. */
    public static final String BRAND_TOKEN = "{brand}";

    /** Token hari pengiriman — dirender "hari ini"/"besok" mengikuti jam tutup operasional cabang,
     *  sama persis dengan template follow-up biasa. OPSIONAL: tak semua pantun memakainya. */
    public static final String DAY_TOKEN = "{hari_kirim}";

    /**
     * Teks siap kirim: token {brand} diganti nama merek DAN dicetak tebal oleh renderer (bukan oleh
     * penulis korpus) supaya seluruh korpus seragam; {hari_kirim} diganti kata hari pengiriman.
     * Merek kosong → teks dikembalikan apa adanya; pemanggil yang bertanggung jawab tidak mengirim
     * pantun dalam keadaan itu.
     */
    public static String render(String body, String brand, String dayWord) {
        if (body == null) return "";
        String out = body.trim();
        String b = brand == null ? "" : brand.trim();
        if (!b.isEmpty()) {
            if (b.length() > BRAND_MAX) b = b.substring(0, BRAND_MAX);
            out = out.replace(BRAND_TOKEN, "*" + b + "*");
        }
        if (dayWord != null && !dayWord.trim().isEmpty()) {
            out = out.replace(DAY_TOKEN, dayWord.trim());
        }
        return out;
    }
}
