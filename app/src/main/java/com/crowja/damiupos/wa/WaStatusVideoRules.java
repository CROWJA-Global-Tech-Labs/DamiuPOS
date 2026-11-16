package com.crowja.damiupos.wa;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Aturan video Status WhatsApp di HP (murni, diuji JVM): batas satu Status (60 detik, 16 MB — sama dengan server
 * {@code WaStatusPost::MAX_VIDEO_SECONDS / MAX_VIDEO_KB}), kapan video perlu dikompres ulang, dan bitrate kompresinya.
 * Server tetap memeriksa ulang; ini supaya operator tahu sebelum mengunggah.
 */
public final class WaStatusVideoRules {

    public static final long MAX_MS = 60_000L;
    /** Toleransi pembulatan durasi (server: +1 detik). */
    public static final long SLACK_MS = 1_000L;
    public static final long MAX_BYTES = 16L * 1024 * 1024;
    /** Sisi terpanjang hasil kompres (720p) — WhatsApp sendiri memutar Status sekitar ukuran ini. */
    public static final int MAX_SIDE = 1280;
    /** Ukuran sasaran kompres: di bawah batas supaya kontainer MP4 + audio tak melewatinya. */
    static final long TARGET_BYTES = 14L * 1024 * 1024;
    static final int AUDIO_BPS = 128_000;
    static final int MIN_VIDEO_BPS = 700_000;
    static final int MAX_VIDEO_BPS = 3_000_000;

    private WaStatusVideoRules() {}

    /** Lebih panjang dari satu Status → harus dipotong (HP menawarkan memakai 60 detik pertama). */
    public static boolean tooLong(long durationMs) {
        return durationMs > MAX_MS + SLACK_MS;
    }

    /**
     * Perlu dikompres ulang? Ya bila terlalu besar, resolusinya di atas 720p, bukan MP4, atau harus dipotong. Video MP4
     * kecil yang sudah pas diunggah apa adanya (kualitas asli).
     */
    public static boolean needsTranscode(long bytes, long durationMs, int width, int height, @Nullable String mime) {
        return bytes > MAX_BYTES || Math.max(width, height) > MAX_SIDE || !"video/mp4".equals(mime) || tooLong(durationMs);
    }

    /** Bitrate video (bps) agar hasil {@code durationMs} (dipotong ke 60 detik) ±14 MB termasuk audio. */
    public static int videoBitrate(long durationMs) {
        long ms = Math.max(1_000L, Math.min(durationMs, MAX_MS));
        long total = TARGET_BYTES * 8L * 1000L / ms;
        long video = total - AUDIO_BPS;
        return (int) Math.max(MIN_VIDEO_BPS, Math.min(MAX_VIDEO_BPS, video));
    }

    /** Hasil akhir yang siap diunggah: null = boleh, selain itu pesan untuk operator. */
    @Nullable
    public static String finalCheck(long bytes, long durationMs) {
        if (bytes <= 0) return "Video kosong atau tak bisa dibaca.";
        if (tooLong(durationMs)) {
            return "Video " + clock(durationMs) + " — Status WhatsApp maksimal " + (MAX_MS / 1000) + " detik.";
        }
        if (bytes > MAX_BYTES) {
            return "Video " + mb(bytes) + " — maksimal " + mb(MAX_BYTES) + ". Pilih video yang lebih pendek.";
        }
        return null;
    }

    /** "1:05". */
    public static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** "12,3 MB". */
    public static String mb(long bytes) {
        return String.format(new Locale("id", "ID"), "%.1f MB", bytes / 1048576.0);
    }
}
