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
}
