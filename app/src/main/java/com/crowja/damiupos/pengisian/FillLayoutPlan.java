package com.crowja.damiupos.pengisian;

/**
 * Memilih kepadatan kartu produk layar Pengisian supaya SEMUA produk muat di layar tanpa menggulir,
 * menyesuaikan resolusi/tinggi layar apa pun. Murni hitungan (tanpa View) supaya bisa diuji JUnit.
 *
 * <p>Tinggi kartu = sisa tinggi area produk dibagi jumlah kartu (dikurangi jarak antar kartu). Makin
 * sedikit ruang, makin ringkas isinya ({@link #TIER_FULL} → {@link #TIER_MEDIUM} → {@link #TIER_COMPACT}).
 * Bila semua produk (termasuk yang tak punya pesanan) tak muat walau sudah ringkas, produk tanpa
 * aktivitas dilipat ke "Produk lain"; bila yang aktif saja masih tak muat, layar jatuh ke menggulir
 * (kartu ringkas dengan tinggi alami).</p>
 *
 * <p>Ambang tinggi = tinggi ALAMI isi kartu pada tingkat itu (dp), supaya tak terpotong.</p>
 */
public final class FillLayoutPlan {

    public static final int TIER_FULL = 0;
    public static final int TIER_MEDIUM = 1;
    public static final int TIER_COMPACT = 2;

    /** Tinggi minimum (dp) sebuah kartu agar pantas memakai tingkat itu. */
    public static final float FULL_MIN_DP = 200f;
    public static final float MEDIUM_MIN_DP = 152f;
    public static final float COMPACT_MIN_DP = 118f;

    /** Batas atas supaya satu kartu tunggal tak melar sampai sebesar layar. */
    public static final float CARD_MAX_DP = 260f;

    /** Jarak bawah antar kartu (sama dengan layout_marginBottom kartu). */
    public static final float CARD_GAP_DP = 8f;

    /** Tinggi baris pelipat "Produk lain" bila tampil. */
    public static final float TOGGLE_DP = 56f;

    public static final class Result {
        /** true = semua produk (aktif + lain) ditampilkan langsung, pelipat disembunyikan. */
        public final boolean inlineAll;
        public final int tier;
        /** Tinggi TETAP tiap kartu (dp); 0 = pakai tinggi alami. */
        public final float cardDp;
        /** true = tak muat walau ringkas → layar boleh menggulir. */
        public final boolean scrolls;

        Result(boolean inlineAll, int tier, float cardDp, boolean scrolls) {
            this.inlineAll = inlineAll;
            this.tier = tier;
            this.cardDp = cardDp;
            this.scrolls = scrolls;
        }
    }

    private FillLayoutPlan() {
    }

    public static int tierFor(float cardDp) {
        return tierFor(cardDp, 1f);
    }

    /**
     * @param fontScale skala font sistem (Setelan > Ukuran font): teks membesar tapi tombol (dp) tidak,
     *                  jadi kartu yang sama butuh tinggi lebih besar. Tinggi dinilai sebagai
     *                  {@code cardDp / fontScale} supaya isi tak terpotong pada font besar.
     */
    public static int tierFor(float cardDp, float fontScale) {
        float eff = cardDp / Math.max(1f, fontScale);
        if (eff >= FULL_MIN_DP) return TIER_FULL;
        if (eff >= MEDIUM_MIN_DP) return TIER_MEDIUM;
        return TIER_COMPACT;
    }

    /**
     * @param areaDp         tinggi (dp) yang tersedia untuk kartu produk: tinggi area gulir dikurangi hero,
     *                       peringatan, dan padding. Nilai <= 0 (belum diukur) → tingkat ringkas alami.
     * @param activeCount    jumlah produk aktif (ada pesanan/stok/isi)
     * @param otherCount     jumlah produk tanpa aktivitas
     * @param othersExpanded pekerja sudah membuka "Produk lain" secara manual
     */
    public static Result choose(float areaDp, int activeCount, int otherCount, boolean othersExpanded) {
        return choose(areaDp, activeCount, otherCount, othersExpanded, 1f);
    }

    public static Result choose(float areaDp, int activeCount, int otherCount, boolean othersExpanded,
                                float fontScale) {
        final float fs = Math.max(1f, fontScale);
        int total = activeCount + otherCount;
        if (total <= 0) return new Result(true, TIER_FULL, 0f, false);
        if (areaDp <= 0f) return new Result(otherCount == 0, TIER_COMPACT, 0f, true);

        // 1) Semua produk langsung tampil, bila tiap kartu masih muat pada tingkat ringkas.
        float perAll = areaDp / total - CARD_GAP_DP;
        if (perAll / fs >= COMPACT_MIN_DP) {
            return new Result(true, tierFor(perAll, fs), Math.min(perAll, CARD_MAX_DP), false);
        }

        // 2) Tak muat: hanya yang aktif (+ pelipat; bila dibuka manual, semua ikut dihitung).
        int shown = othersExpanded ? total : Math.max(activeCount, 0);
        float toggle = otherCount > 0 ? TOGGLE_DP : 0f;
        if (shown <= 0) return new Result(false, TIER_FULL, 0f, false);
        float per = (areaDp - toggle) / shown - CARD_GAP_DP;
        if (per / fs >= COMPACT_MIN_DP) {
            return new Result(false, tierFor(per, fs), Math.min(per, CARD_MAX_DP), false);
        }
        // 3) Masih tak muat → kartu ringkas tinggi alami, layar menggulir.
        return new Result(false, TIER_COMPACT, 0f, true);
    }
}
