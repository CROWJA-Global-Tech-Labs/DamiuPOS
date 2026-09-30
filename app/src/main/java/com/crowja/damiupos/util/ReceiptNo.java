package com.crowja.damiupos.util;

import java.util.Random;
import java.util.regex.Pattern;

/**
 * ID transaksi struk ({@code receipt_no}): {@code <KODE>-<DDMMYYHHMM>-<SUFFIX>}, mis.
 * {@code ZK-2809260945-K7P2M} (28-09-26 pukul 09:45, waktu LOKAL transaksi).
 *
 * <p>SUFFIX = 5 karakter ACAK dari {@code [A-Z0-9]} untuk setiap transaksi yang dibuat HP. Dulu nomornya
 * {@code <KODE>-DDMMYY-<counter>} dan akhirannya
 * angka urut dari counter lokal per-perangkat, yang bisa menghasilkan nomor kembar antar
 * pelanggan (tidak atomik, hanya ingat hari terakhir, hilang saat install ulang/hapus data, dan tak
 * pernah melihat baris yang lahir di server). Nomor lama yang sudah tersimpan dibiarkan apa adanya
 * (string buram) — pembaca format harus menerima KEDUA bentuk lewat {@link #isReceiptNo(String)}.
 *
 * <p>Kelas murni (tanpa Android) agar bisa diuji di JVM.
 */
public final class ReceiptNo {

    /** Alfabet suffix. Dipilih merata per karakter lewat {@code rng.nextInt(36)}. */
    static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Panjang suffix acak. */
    static final int SUFFIX_LENGTH = 5;

    /** Lama: KODE-DDMMYY-<angka urut, berapa digit pun>. Baru: KODE-DDMMYYHHMM-<5 karakter [A-Z0-9]>. */
    private static final Pattern FORMAT =
            Pattern.compile("[A-Z0-9]{1,4}-(?:\\d{6}-\\d+|\\d{10}-[A-Z0-9]{5})");

    /** Stempel waktu LOKAL "yyyy-MM-dd" diikuti (opsional) "[ T]HH:mm...". */
    private static final Pattern LOCAL_STAMP =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2}).*)?", Pattern.DOTALL);

    private ReceiptNo() {}

    /**
     * 5 karakter acak dari {@link #ALPHABET}, tiap karakter memakai {@code rng.nextInt(36)} supaya
     * huruf dan angka sama peluangnya (bukan generator huruf-besar-kecil yang di-uppercase, yang
     * akan condong ke huruf).
     */
    public static String randomSuffix(Random rng) {
        char[] out = new char[SUFFIX_LENGTH];
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            out[i] = ALPHABET.charAt(rng.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    /**
     * {@code ddMMyyHHmm} dari stempel waktu LOKAL "yyyy-MM-dd HH:mm[:ss...]" (pemisah spasi atau 'T').
     * Stempel hanya-tanggal ("yyyy-MM-dd") jadi pukul 0000 — sama dengan web
     * ({@code Carbon::parse($t->tanggal)->format('dmyHi')}). Pemanggil wajib sudah menormalkan stempel
     * berakhiran Z (UTC) ke waktu lokal lewat {@link Ts#local(String)}.
     *
     * @return null bila bentuknya tak dikenali
     */
    public static String stamp(String localTimestamp) {
        if (localTimestamp == null) return null;
        java.util.regex.Matcher m = LOCAL_STAMP.matcher(localTimestamp.trim());
        if (!m.matches()) return null;
        String yy = m.group(1).substring(2);
        String hh = m.group(4) != null ? m.group(4) : "00";
        String mm = m.group(5) != null ? m.group(5) : "00";
        return m.group(3) + m.group(2) + yy + hh + mm;
    }

    /** Menyusun {@code <KODE>-<DDMMYYHHMM>-<SUFFIX>}. */
    public static String compose(String code, String stamp, String suffix) {
        return code + "-" + stamp + "-" + suffix;
    }

    /** True untuk receipt_no bentuk lama (KODE-DDMMYY-angka urut) maupun baru (KODE-DDMMYYHHMM-5 karakter acak). */
    public static boolean isReceiptNo(String s) {
        return s != null && FORMAT.matcher(s).matches();
    }
}
