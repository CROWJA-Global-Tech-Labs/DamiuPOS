package com.crowja.damiupos.wa;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Aturan video Status WhatsApp di HP (murni, diuji JVM). Batas dibaca dari server ({@code form.video}: max_seconds,
 * max_parts, max_bytes, long_side, segment_seconds); nilai bawaan di bawah hanya cadangan hati-hati bila field tak ada.
 * Video dipotong per {@link #segmentSeconds} detik, tiap potongan dikompres ≤ max_bytes lalu diunggah sendiri-sendiri.
 */
public final class WaStatusVideoRules {

    static final int FALLBACK_MAX_SECONDS = 30;
    static final int FALLBACK_MAX_PARTS = 10;
    /** Cadangan: batas Bridge 1 MB → ±750 KB berkas. */
    static final long FALLBACK_MAX_BYTES = 750L * 1024;
    /** Bitrate total minimal yang masih layak (dok server: ±450 kbps). */
    static final int MIN_TOTAL_BPS = 450_000;
    static final int AUDIO_BPS = 128_000;
    static final int MIN_VIDEO_BPS = 150_000;
    static final int MAX_VIDEO_BPS = 2_400_000;   // dok: mode URL 16 MB → ±2–2,5 Mbps
    /** Potongan terakhir yang lebih pendek dari ini digabung ke potongan sebelumnya bila masih muat. */
    static final long MIN_TAIL_MS = 1_000L;

    public final int maxSeconds;
    public final int maxParts;
    public final long maxBytes;
    public final int longSide;
    public final int segmentSeconds;

    WaStatusVideoRules(int maxSeconds, int maxParts, long maxBytes, int longSide, int segmentSeconds) {
        this.maxSeconds = maxSeconds > 0 ? maxSeconds : FALLBACK_MAX_SECONDS;
        this.maxParts = maxParts > 0 ? maxParts : FALLBACK_MAX_PARTS;
        this.maxBytes = maxBytes > 0 ? maxBytes : FALLBACK_MAX_BYTES;
        this.longSide = longSide > 0 ? longSide : (this.maxBytes < 4L * 1048576 ? 480 : 720);
        int seg = segmentSeconds > 0 ? segmentSeconds
                : (int) Math.floor(this.maxBytes * 0.9 * 8 / MIN_TOTAL_BPS);
        this.segmentSeconds = Math.max(1, Math.min(this.maxSeconds, seg));
    }

    /** Dari {@code form.video} (boleh null → semua cadangan). {@code max_mb} dipakai bila {@code max_bytes} tak ada. */
    public static WaStatusVideoRules from(@Nullable JSONObject v) {
        if (v == null) return new WaStatusVideoRules(0, 0, 0, 0, 0);
        long bytes = v.optLong("max_bytes", 0);
        if (bytes <= 0 && v.optDouble("max_mb", 0) > 0) bytes = (long) (v.optDouble("max_mb") * 1048576);
        return new WaStatusVideoRules(v.optInt("max_seconds", 0), v.optInt("max_parts", 0), bytes,
                v.optInt("long_side", 0), v.optInt("segment_seconds", 0));
    }

    public long segmentMs() {
        return segmentSeconds * 1000L;
    }

    public long maxTotalMs() {
        return segmentMs() * maxParts;
    }

    /** Potongan {start, end} (ms) untuk video sepanjang {@code durationMs}; dibatasi {@link #maxParts} potongan. */
    public List<long[]> plan(long durationMs) {
        List<long[]> out = new ArrayList<>();
        long seg = segmentMs();
        long end = Math.min(durationMs, maxTotalMs());
        for (long s = 0; s < end && out.size() < maxParts; s += seg) out.add(new long[]{s, Math.min(end, s + seg)});
        // Ekor sangat pendek: gabung ke sebelumnya bila total masih ≤ max_seconds (tak menambah satu Status kosong).
        if (out.size() > 1) {
            long[] last = out.get(out.size() - 1);
            long[] prev = out.get(out.size() - 2);
            if (last[1] - last[0] < MIN_TAIL_MS && last[1] - prev[0] <= maxSeconds * 1000L) {
                prev[1] = last[1];
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    /** Video lebih panjang dari yang bisa dipotong → hanya bagian awal yang dipakai. */
    public boolean tooLong(long durationMs) {
        return durationMs > maxTotalMs() + 500;
    }

    /** Bitrate video (bps) agar satu potongan sepanjang {@code partMs} ±90% {@link #maxBytes} termasuk audio. */
    public int videoBitrate(long partMs) {
        long ms = Math.max(1_000L, partMs);
        long total = (long) (maxBytes * 0.9 * 8 * 1000 / ms);
        return (int) Math.max(MIN_VIDEO_BPS, Math.min(MAX_VIDEO_BPS, total - AUDIO_BPS));
    }

    /** Potongan hasil kompres: null = boleh, selain itu pesan untuk operator. */
    @Nullable
    public String partCheck(int index, int count, long bytes, long durationMs) {
        String pre = count > 1 ? "Potongan " + index + "/" + count + ": " : "";
        if (bytes <= 0) return pre + "video kosong atau tak bisa dibaca.";
        if (durationMs > maxSeconds * 1000L + 1000) return pre + clock(durationMs) + " — maksimal " + maxSeconds + " detik.";
        if (bytes > maxBytes) return pre + kb(bytes) + " — melebihi batas kirim " + kb(maxBytes) + ".";
        return null;
    }

    /** Teks bantuan di form. */
    public String hint() {
        return "Video dikompres di HP (" + longSide + " px, MP4) dan dipotong per " + segmentSeconds + " detik, maksimal "
                + maxParts + " potongan (" + clock(maxTotalMs()) + "); tiap potongan ≤ " + kb(maxBytes)
                + " dan tayang sebagai satu Status berurutan. Teks di bawah menjadi keterangan potongan pertama (opsional).";
    }

    /** "1:05". */
    public static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /** "750 KB" / "12,3 MB". */
    public static String kb(long bytes) {
        if (bytes < 1048576) return Math.round(bytes / 1024.0) + " KB";
        return String.format(new Locale("id", "ID"), "%.1f MB", bytes / 1048576.0);
    }
}
