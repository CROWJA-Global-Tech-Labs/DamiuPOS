package com.crowja.damiupos.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Aturan MURNI (tanpa Android/org.json, bisa diuji JVM) untuk "Ambil Alih butuh persetujuan":
 * kapan tombol Ambil pada kartu order perangkat lain dikunci, dan teks status kecil di bawah kartu.
 *
 * <p>Alurnya: perangkat pemilik antrean menyalakan setelan web "pesanan di antrean perangkat ini
 * butuh persetujuan bila diambil perangkat lain". Klaim dari HP lain lalu dijawab 409
 * {@code needs_approval} — HP peminta mencatat permintaannya (lihat
 * {@link com.crowja.damiupos.sync.ClaimRequests}) dan tombol Ambil order itu DIKUNCI sampai
 * {@code expires_at} (±1 jam sejak diminta), walau permintaannya ditolak, supaya pemilik tak
 * dihujani permintaan ulang untuk order yang sama.</p>
 */
public final class ClaimRequestRules {

    private ClaimRequestRules() {}

    /** Jendela kunci bawaan bila server tak menyebut {@code expires_at} yang bisa diurai. */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String FAILED = "FAILED";

    /** Waktu server ({@code "Y-m-d H:i:s"}) selalu Asia/Jakarta — jangan ikut zona waktu HP. */
    private static final TimeZone JAKARTA = TimeZone.getTimeZone("Asia/Jakarta");

    /** Satu permintaan ambil alih KELUAR (perangkat ini yang meminta). */
    public static final class Entry {
        public final String trxUuid;
        public final long requestId;
        public final String ownerName;
        public final String status;
        public final long expiresAtMs;
        /** Kapan HP ini terakhir menulis entri dari jawaban klaim — penjaga balapan dengan poll. */
        public final long recordedAtMs;

        public Entry(String trxUuid, long requestId, String ownerName, String status,
                     long expiresAtMs, long recordedAtMs) {
            this.trxUuid = trxUuid == null ? "" : trxUuid;
            this.requestId = requestId;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.status = normStatus(status);
            this.expiresAtMs = expiresAtMs;
            this.recordedAtMs = recordedAtMs;
        }

        public Entry withStatus(String newStatus, String newOwner, long newExpiresAtMs) {
            return new Entry(this.trxUuid, this.requestId,
                    newOwner == null || newOwner.isEmpty() ? this.ownerName : newOwner,
                    newStatus, newExpiresAtMs > 0 ? newExpiresAtMs : this.expiresAtMs,
                    this.recordedAtMs);
        }
    }

    public static String normStatus(String s) {
        if (s == null) return PENDING;
        String v = s.trim().toUpperCase(Locale.US);
        return v.isEmpty() ? PENDING : v;
    }

    /** Entri sudah lewat jendelanya → buang dari penyimpanan. */
    public static boolean isStale(Entry e, long now) {
        return e == null || now >= e.expiresAtMs;
    }

    /**
     * Tombol Ambil order ini dikunci? Menunggu, ditolak, atau disetujui (order sedang pindah ke
     * sini) — selama jendela belum habis. Kedaluwarsa/gagal TIDAK mengunci: boleh minta lagi.
     */
    public static boolean blocksClaim(Entry e, long now) {
        if (isStale(e, now)) return false;
        return PENDING.equals(e.status) || REJECTED.equals(e.status) || APPROVED.equals(e.status);
    }

    /** Teks kecil di bawah kartu; null bila tak ada yang perlu ditampilkan. */
    public static String cardLine(Entry e, long now) {
        if (isStale(e, now)) return null;
        String owner = e.ownerName.isEmpty() ? "perangkat pemilik" : e.ownerName;
        String until = hm(e.expiresAtMs);
        switch (e.status) {
            case PENDING:
                return "⏳ Permintaan ambil alih sudah dikirim ke " + owner
                        + " — menunggu persetujuan (s/d " + until + ")";
            case REJECTED:
                return "❌ Permintaan ambil alih ditolak " + owner + " — bisa minta lagi pukul " + until;
            case APPROVED:
                return "✅ Disetujui " + owner + " — order dipindahkan ke perangkat ini";
            case EXPIRED:
                return "⌛ Permintaan ke " + owner + " kedaluwarsa — boleh minta lagi";
            case FAILED:
                return "⚠ Permintaan ke " + owner + " gagal — order sudah tak bisa dipindahkan";
            default:
                return null;
        }
    }

    /** Pesan toast saat kurir menekan Ambil pada order yang masih terkunci. */
    public static String blockedToast(Entry e, long now) {
        String line = cardLine(e, now);
        return line != null ? line : "Permintaan ambil alih order ini sudah dikirim.";
    }

    /**
     * {@code "Y-m-d H:i:s"} (Asia/Jakarta) → epoch ms. Bentuk ISO berakhiran Z juga diterima.
     * Gagal/kosong → {@code fallback}.
     */
    public static long parseServerTime(String s, long fallback) {
        if (s == null) return fallback;
        String x = s.trim();
        if (x.isEmpty() || "null".equals(x)) return fallback;
        try {
            boolean utc = x.endsWith("Z");
            String core = (utc ? x.substring(0, x.length() - 1) : x).replace('T', ' ');
            int dot = core.indexOf('.');
            if (dot > 0) core = core.substring(0, dot);
            if (core.length() > 19) core = core.substring(0, 19);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setLenient(false);
            f.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : JAKARTA);
            Date d = f.parse(core.trim());
            return d == null ? fallback : d.getTime();
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** Jam:menit di zona waktu HP. */
    public static String hm(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(ms));
    }

    /** "± 42 mnt lagi" / "kurang dari 1 mnt lagi" / "sudah lewat". */
    public static String timeLeft(long expiresAtMs, long now) {
        long left = expiresAtMs - now;
        if (left <= 0) return "sudah lewat";
        long min = left / 60000L;
        if (min < 1) return "kurang dari 1 mnt lagi";
        return "± " + min + " mnt lagi";
    }
}
