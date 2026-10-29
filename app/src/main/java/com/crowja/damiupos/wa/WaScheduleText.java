package com.crowja.damiupos.wa;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Teks murni (tanpa Android/org.json) untuk layar Broadcast Terjadwal — bisa diuji unit.
 * Cermin perilaku form web /wa-schedules: pratinjau {nama}, format waktu server "yyyy-MM-dd HH:mm".
 */
public final class WaScheduleText {

    private WaScheduleText() {}

    /** Format waktu yang dikirim & diterima server (zona aplikasi, mis. Asia/Jakarta). */
    public static final String WIRE = "yyyy-MM-dd HH:mm";

    private static final Locale ID = new Locale("id", "ID");
    private static final Pattern NAMA = Pattern.compile("\\{nama\\}|\\{name\\}", Pattern.CASE_INSENSITIVE);

    /** Pratinjau pesan: {nama}/{name} → nama penerima pertama (atau "Bu Sari"); kosong → "Kak"; pesan kosong → "—". */
    public static String preview(String message, String firstName) {
        String m = message == null ? "" : message;
        String who = firstName == null ? "Bu Sari" : (firstName.trim().isEmpty() ? "Kak" : firstName);
        String out = NAMA.matcher(m).replaceAll(java.util.regex.Matcher.quoteReplacement(who));
        return out.isEmpty() ? "—" : out;
    }

    /** Sisipkan "{nama}" di posisi kursor [start, end]. */
    public static String insertNama(String text, int start, int end) {
        String t = text == null ? "" : text;
        int s = Math.max(0, Math.min(start, t.length()));
        int e = Math.max(s, Math.min(end, t.length()));
        return t.substring(0, s) + "{nama}" + t.substring(e);
    }

    /** "2026-10-30 08:00" → "30 Okt 2026 08:00"; null/tak terbaca → apa adanya / "". */
    public static String display(String wire) {
        if (wire == null || wire.trim().isEmpty()) return "";
        try {
            Date d = new SimpleDateFormat(WIRE, Locale.US).parse(wire.trim());
            return d == null ? wire : new SimpleDateFormat("dd MMM yyyy HH:mm", ID).format(d);
        } catch (ParseException e) {
            return wire;
        }
    }

    /** Waktu kalender → format server. */
    public static String wire(Calendar c) {
        return new SimpleDateFormat(WIRE, Locale.US).format(c.getTime());
    }

    /** Format server → Calendar (null bila tak terbaca). */
    public static Calendar parse(String wire) {
        if (wire == null || wire.trim().isEmpty()) return null;
        try {
            Date d = new SimpleDateFormat(WIRE, Locale.US).parse(wire.trim());
            if (d == null) return null;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return c;
        } catch (ParseException e) {
            return null;
        }
    }

    /** "08:00" dari jam & menit. */
    public static String hhmm(int h, int m) {
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    /** Potong label wilayah ke batas server (60 karakter). */
    public static String label60(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** "5 terkirim · 1 gagal · 2 menunggu" (bagian nol dilewati kecuali terkirim). */
    public static String countsLine(int sent, int failed, int pending) {
        StringBuilder b = new StringBuilder();
        b.append(sent).append(" terkirim");
        if (failed > 0) b.append(" · ").append(failed).append(" gagal");
        if (pending > 0) b.append(" · ").append(pending).append(" menunggu");
        return b.toString();
    }
}
