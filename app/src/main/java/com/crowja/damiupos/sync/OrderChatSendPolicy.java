package com.crowja.damiupos.sync;

/**
 * Keputusan MURNI (tanpa Android / org.json -- diuji di JVM, lihat src/test) atas satu jawaban kirim
 * "💬 Chat Pesanan" ({@code POST /api/transactions/{uuid}/chat/send}), dipakai {@link OrderChatOutbox}.
 *
 * <p>Aturan inti: kunci idempoten (client_key) hanya boleh diganti bila server menyatakan pengiriman
 * itu PASTI tak terjadi. Server menjalankan rate limit dan gerbang can_send SEBELUM pencarian
 * client_key (spec §6.3 langkah 4-6), jadi 429/422/403 pada ulangan kunci-SAMA tidak berarti
 * percobaan pertama gagal -- bisa saja sudah sampai ke bridge (202). Maka:
 * <ul>
 *   <li>2xx dengan {@code ok:true}, bukan {@code pending}, dan ada {@code message} → terkirim;
 *       2xx lain (202 / pending / body kosong / tak lengkap) → ulang dengan kunci SAMA.</li>
 *   <li>tak ada respons / 5xx → ulang dengan kunci SAMA.</li>
 *   <li>409 {@code send_unknown} → status tak diketahui.</li>
 *   <li>{@code new_key_required:true}, atau 4xx pada percobaan PERTAMA (ditolak sebelum bridge) →
 *       gagal final; kirim ulang = kunci BARU.</li>
 *   <li>4xx lain pada ULANGAN: 429 → tunggu lebih lama lalu ulang dengan kunci SAMA; selebihnya →
 *       status tak diketahui (ketuk = "mungkin sudah terkirim — kirim ulang sebagai pesan baru?").</li>
 * </ul>
 */
public final class OrderChatSendPolicy {

    private OrderChatSendPolicy() {}

    public enum Outcome {
        /** Terkirim — server mengembalikan baris pesannya. */
        SENT,
        /** Belum pasti — ulang dengan kunci SAMA ({@link #retryDelayMs}). */
        RETRY,
        /** Kena rate limit pada ulangan — ulang dengan kunci SAMA setelah {@link #rateLimitDelayMs}. */
        RETRY_SLOW,
        /** Mungkin sudah terkirim — tanya pengguna sebelum kirim ulang dengan kunci BARU. */
        UNKNOWN,
        /** Pasti tak terkirim — kirim ulang memakai kunci BARU. */
        FAILED
    }

    /** Ulangan kunci-SAMA maksimum setelah percobaan pertama (spec §10: "up to 6 times"). */
    public static final int MAX_RETRIES = 6;
    static final long RETRY_BASE_MS = 5000L;
    static final long RETRY_CAP_MS = 30000L;
    static final long RATE_LIMIT_MIN_MS = 20000L;
    static final long RATE_LIMIT_MAX_MS = 60000L;

    /**
     * @param status         HTTP status (0 = tak ada respons)
     * @param bodyOk         2xx dengan body JSON {@code ok:true}
     * @param pending        body {@code pending:true} (202)
     * @param hasMessage     body punya objek {@code message}
     * @param errorCode      {@code error.code} (boleh null)
     * @param newKeyRequired body {@code new_key_required:true}
     * @param firstAttempt   ini permintaan PERTAMA untuk kunci ini (tak ada yang mungkin sudah sampai)
     */
    public static Outcome decide(int status, boolean bodyOk, boolean pending, boolean hasMessage,
                                 String errorCode, boolean newKeyRequired, boolean firstAttempt) {
        if (status >= 200 && status < 300) {
            return bodyOk && !pending && hasMessage ? Outcome.SENT : Outcome.RETRY;
        }
        if (status <= 0 || status >= 500) return Outcome.RETRY;
        if (status == 409 && "send_unknown".equals(errorCode)) return Outcome.UNKNOWN;
        if (newKeyRequired || firstAttempt) return Outcome.FAILED;
        if (status == 429) return Outcome.RETRY_SLOW;
        return Outcome.UNKNOWN;
    }

    /** Jeda sebelum ulangan ke-{@code retry} (1 = ulangan pertama): 5, 10, 20, 30, 30, 30 detik —
     *  ulangan pertama tetap 5 detik (spec), sesudahnya melebar supaya gangguan bridge tak dibalas
     *  unggahan ulang berkali-kali dalam 30 detik (tiap ulangan mengirim ulang gambarnya). */
    public static long retryDelayMs(int retry) {
        int n = Math.max(1, retry);
        long d = RETRY_BASE_MS << Math.min(n - 1, 4);
        return Math.min(RETRY_CAP_MS, d);
    }

    /** Jeda setelah 429 pada ulangan: hormati Retry-After bila ada, minimal 20 detik, maksimal 60. */
    public static long rateLimitDelayMs(long retryAfterMs) {
        return Math.min(RATE_LIMIT_MAX_MS, Math.max(RATE_LIMIT_MIN_MS, retryAfterMs));
    }
}
