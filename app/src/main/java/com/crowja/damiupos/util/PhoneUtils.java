package com.crowja.damiupos.util;

/**
 * Normalizes an Indonesian phone number to the local {@code 08…} format
 * (e.g. "+62 812-345" / "62812345" / "812345" → "0812345"). Idempotent ("0812…" → "0812…").
 *
 * <p>Mirrors the server {@code App\Support\Phone::local} logic exactly so the web dashboard and the
 * phone produce the SAME stored value. A number with no digits normalizes to "" (treated as blank =
 * customer without a number).
 */
public final class PhoneUtils {

    private PhoneUtils() {
    }

    /** Canonical national part (drop country code 62 / leading 0), or "" when there are no digits. */
    public static String canonical(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D+", "");
        if (d.isEmpty()) return "";
        // Awalan internasional "00" ("0062 821…", "0082142319379") dibuang DULU — tanpa ini "0062…"
        // jadi "062…" (kanonik palsu) dan lolos dari guard duplikat. Cermin Phone::canonical server.
        if (d.startsWith("00")) {
            d = d.substring(2);
            if (d.isEmpty()) return "";
        }
        if (d.startsWith("62")) {
            d = d.substring(2);
            if (d.startsWith("0")) d = d.substring(1);   // "+62 0812…" ≡ "0812…" (common typo); once only
        } else if (d.startsWith("0")) {
            d = d.substring(1);
        }
        return d;
    }

    /** Local 0-prefixed form ("08…"). Returns "" when there are no digits (keep blank = no number). */
    public static String toLocal08(String phone) {
        String national = canonical(phone);
        return national.isEmpty() ? "" : ("0" + national);
    }

    /** Panjang kanonik minimum agar sebuah nomor dianggap NOMOR sungguhan (di bawah ini = placeholder
     *  "0", "62", "12345" → tak pernah memblokir). Sama dengan aturan guard server (< 8 digit). */
    public static final int MIN_CANONICAL_DIGITS = 8;

    /** True bila nomor ini cukup panjang untuk dijadikan kunci duplikat. */
    public static boolean isMatchable(String phone) {
        return canonical(phone).length() >= MIN_CANONICAL_DIGITS;
    }

    /** Dua nomor sama bila kanonik keduanya sama dan tidak kosong ("0821-4231-9379" ≡ "+62 821 4231 9379"). */
    public static boolean sameNumber(String a, String b) {
        String ca = canonical(a);
        return !ca.isEmpty() && ca.equals(canonical(b));
    }

    private static final java.util.regex.Pattern NON_DIGITS = java.util.regex.Pattern.compile("\\D+");

    /** Hanya digit dari kolom mentah (Pattern dikompilasi sekali — dipanggil per baris saat memindai). */
    public static String digitsOnly(String raw) {
        return raw == null ? "" : NON_DIGITS.matcher(raw).replaceAll("");
    }

    /** 7 digit terakhir bagian nasional (atau seluruhnya bila lebih pendek) — kunci prefilter. "" bila kosong. */
    public static String matchTail(String national) {
        if (national == null || national.isEmpty()) return "";
        return national.length() > 7 ? national.substring(national.length() - 7) : national;
    }

    /**
     * Prefilter murah untuk memindai kolom nomor mentah (kolom {@code phone} atau JSON {@code phones}):
     * deret DIGIT kolom mentah (pemisah apa pun dibuang) memuat 7 digit terakhir bagian nasional.
     * Tanpa ini "0821-4231-9379" tak ditemukan LIKE '%2319379%' pada kolom mentah. Hanya prefilter —
     * pemanggil tetap memverifikasi dengan {@link #sameNumber}. Kolom null/kosong → false.
     */
    public static boolean rawColumnMayContain(String rawColumn, String national) {
        if (rawColumn == null || national == null || national.isEmpty()) return false;
        return digitsOnly(rawColumn).contains(matchTail(national));
    }

    /**
     * Varian BATCH dari {@link #rawColumnMayContain}: untuk SATU baris (kolom {@code phone} + kolom
     * JSON {@code phones}) kembalikan indeks kunci di {@code tails} (hasil {@link #matchTail}) yang
     * lolos prefilter. Digit kedua kolom dihitung SEKALI per baris, bukan sekali per nomor — dasar
     * pemindaian satu-lintasan {@code CustomerDao.findByPhonesCanonical}. Tail kosong tak pernah cocok.
     */
    public static java.util.List<Integer> prefilterHits(String phoneCol, String phonesCol,
                                                        java.util.List<String> tails) {
        java.util.List<Integer> hits = new java.util.ArrayList<>();
        if (tails == null || tails.isEmpty()) return hits;
        String dp = phoneCol == null ? "" : digitsOnly(phoneCol);
        String dps = phonesCol == null ? "" : digitsOnly(phonesCol);
        if (dp.isEmpty() && dps.isEmpty()) return hits;
        for (int i = 0; i < tails.size(); i++) {
            String t = tails.get(i);
            if (t == null || t.isEmpty()) continue;
            if (dp.contains(t) || dps.contains(t)) hits.add(i);
        }
        return hits;
    }
}
