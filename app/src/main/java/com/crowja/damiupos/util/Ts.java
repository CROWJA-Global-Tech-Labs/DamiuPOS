package com.crowja.damiupos.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Perbandingan STEMPEL WAKTU yang tahan campuran format.
 *
 * <p>Satu kolom waktu di SQLite bisa berisi DUA bentuk sekaligus, tergantung siapa yang menulis:
 * <pre>
 *   ditulis HP     : "2026-08-27 14:40:12"           waktu LOKAL, pemisah spasi
 *   ditarik server : "2026-08-27T07:40:12.766088Z"   UTC, pemisah 'T', mikrodetik, akhiran Z
 * </pre>
 *
 * <p>Membandingkannya sebagai STRING apa adanya salah dua kali sekaligus:
 * <ol>
 *   <li>Spasi (0x20) selalu lebih kecil dari 'T' (0x54), jadi pada TANGGAL YANG SAMA bentuk ISO
 *       selalu terbaca "lebih baru" daripada bentuk lokal, apa pun jamnya.</li>
 *   <li>Nilainya beda 7 jam untuk WIB, jadi urutan bisa terbalik walau pemisahnya diseragamkan.</li>
 * </ol>
 *
 * <p>Akibat nyatanya pada pasangan stempel "bersuperseding" (ditandai vs diselesaikan): kunjungan
 * atau prioritas yang SUDAH diselesaikan bisa muncul lagi seolah masih terbuka, karena stempel
 * penyelesaian dari HP terbaca lebih tua daripada stempel penandaan dari server.
 *
 * <p>Semua perbandingan stempel waktu lintas-sumber harus lewat kelas ini — di Java memakai
 * {@link #millis(String)}, di SQL memakai {@link #localExpr(String)} — supaya jalur SQL dan jalur
 * Java tak pernah menyimpang satu sama lain.
 */
public final class Ts {

    private Ts() {}

    /** SimpleDateFormat mahal & tak thread-safe → satu instance per thread, dipakai ulang. */
    private static final ThreadLocal<SimpleDateFormat> FMT = new ThreadLocal<SimpleDateFormat>() {
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        }
    };

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /**
     * Stempel waktu menjadi milidetik epoch, apa pun bentuknya.
     *
     * @return {@link Long#MAX_VALUE} bila kosong atau tak bisa diurai — sengaja "tak hingga" supaya
     *         nilai yang hilang dianggap PALING BARU oleh pemanggil yang mencari yang terlama.
     */
    public static long millis(String s) {
        if (s == null || s.isEmpty()) {
            return Long.MAX_VALUE;
        }
        try {
            String x = s.trim();
            boolean utc = x.endsWith("Z");
            String core = (utc ? x.substring(0, x.length() - 1) : x).replace('T', ' ');
            int dot = core.indexOf('.');
            if (dot > 0) {
                core = core.substring(0, dot);
            }
            if (core.length() > 19) {
                core = core.substring(0, 19);
            }
            SimpleDateFormat sdf = FMT.get();
            sdf.setTimeZone(utc ? UTC : TimeZone.getDefault());
            Date d = sdf.parse(core.trim());

            return d == null ? Long.MAX_VALUE : d.getTime();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Stempel waktu apa pun bentuknya menjadi teks LOKAL "yyyy-MM-dd HH:mm:ss".
     *
     * <p>Baris asal-web tiba sebagai ISO-UTC ("2026-08-28T01:00:00.000000Z") sedangkan HP menulis
     * waktu lokal. Pembaca yang memotong teksnya begitu saja (mis. substring jam) akan menampilkan
     * jam UTC untuk baris asal-web — selisih 7 jam di Asia/Jakarta. Pakai ini, jangan substring.
     *
     * @return "" bila kosong / tak bisa diurai
     */
    public static String local(String s) {
        long ms = millis(s);
        if (ms == Long.MAX_VALUE) {
            return "";
        }
        SimpleDateFormat sdf = FMT.get();
        sdf.setTimeZone(TimeZone.getDefault());

        return sdf.format(new Date(ms));
    }

    /** Jam:menit LOKAL ("HH:mm") dari stempel bentuk apa pun; "" bila kosong/tak terurai. */
    public static String hm(String s) {
        String v = local(s);

        return v.length() >= 16 ? v.substring(11, 16) : "";
    }

    /** Sama dengan {@link #millis}, tapi yang kosong/gagal dianggap PALING TUA. */
    public static long millisOrMin(String s) {
        long v = millis(s);

        return v == Long.MAX_VALUE ? Long.MIN_VALUE : v;
    }

    /** {@code a} benar-benar lebih baru daripada {@code b}? Kosong = tak pernah → tidak lebih baru. */
    public static boolean after(String a, String b) {
        return millisOrMin(a) > millisOrMin(b);
    }

    /**
     * Stempel → MIKRODETIK epoch (fraksi detik ikut dihitung, beda dengan {@link #millis}); {@link Long#MIN_VALUE}
     * bila kosong/tak terurai. Untuk kunci LWW edited_at: dua suntingan dalam detik yang sama tetap terurut.
     */
    public static long micros(String s) {
        long ms = millisOrMin(s);
        if (ms == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        long frac = 0;
        int dot = s.indexOf('.');
        int n = 0;
        for (int i = dot + 1; dot > 0 && i < s.length() && n < 6 && Character.isDigit(s.charAt(i)); i++, n++) {
            frac = frac * 10 + (s.charAt(i) - '0');
        }
        for (; n < 6; n++) {
            frac *= 10;
        }

        return ms * 1000L + frac;
    }

    /**
     * Kunci LWW {@code local} (suntingan HP yang belum terdorong) lebih BARU dari {@code server}? Dibandingkan
     * sebagai WAKTU — server lama mengirim edited_at "…T…Z" sedangkan HP menstempel jam dinding lokal, dan
     * perbandingan string membuat ✓ Selesai / ▶ yang belum terdorong selalu kalah lalu tertimpa pull (order
     * "bagus melati 2" muncul lagi, 23 Okt 2026). Tak terurai → jatuh ke perbandingan string lama.
     */
    public static boolean newerStamp(String local, String server) {
        if (local == null || local.isEmpty()) {
            return false;
        }
        long l = micros(local);
        long s = micros(server);
        if (l == Long.MIN_VALUE || s == Long.MIN_VALUE) {
            return server == null || local.compareTo(server) > 0;
        }

        return l > s;
    }

    /**
     * Ekspresi SQL yang menormalkan kolom stempel waktu ke waktu LOKAL "YYYY-MM-DD HH:MM:SS",
     * sehingga dua kolom bisa dibandingkan sebagai string dengan benar.
     *
     * <p>Mikrodetik dan akhiran Z dipotong DULU lewat SUBSTR(...,1,19) supaya {@code datetime()}
     * hanya menerima bentuk yang pasti dipahaminya (fraksi 6 digit tidak dijamin lolos di semua
     * versi SQLite Android). Nilai ber-akhiran Z dianggap UTC lalu digeser ke lokal; nilai tulisan
     * HP memang sudah lokal, jadi dibiarkan.
     *
     * @param col nama kolom, boleh ber-alias tabel (mis. {@code "c.priority_at"})
     */
    public static String localExpr(String col) {
        String core = "REPLACE(SUBSTR(" + col + ",1,19),'T',' ')";

        // SQLite menganggap string tanpa zona sebagai UTC, jadi 'localtime' = UTC → lokal.
        return "(CASE WHEN " + col + " LIKE '%Z' THEN datetime(" + core + ",'localtime') ELSE " + core + " END)";
    }
}
