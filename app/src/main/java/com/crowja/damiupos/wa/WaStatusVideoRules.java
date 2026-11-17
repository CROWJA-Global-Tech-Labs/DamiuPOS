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
    /** Video MP4 kecil & pendek dengan sisi terpanjang ≤ ini dipakai apa adanya (server: {@code pass_long_side}). */
    public int passLongSide = FALLBACK_PASS_LONG_SIDE;

    static final int FALLBACK_PASS_LONG_SIDE = 1920;

    WaStatusVideoRules(int maxSeconds, int maxParts, long maxBytes, int longSide, int segmentSeconds) {
        this.maxSeconds = maxSeconds > 0 ? maxSeconds : FALLBACK_MAX_SECONDS;
        this.maxParts = maxParts > 0 ? maxParts : FALLBACK_MAX_PARTS;
        this.maxBytes = maxBytes > 0 ? maxBytes : FALLBACK_MAX_BYTES;
        this.longSide = longSide > 0 ? longSide : (this.maxBytes < 4L * 1048576 ? 640 : 1280);
        int seg = segmentSeconds > 0 ? segmentSeconds
                : (int) Math.floor(this.maxBytes * 0.9 * 8 / MIN_TOTAL_BPS);
        this.segmentSeconds = Math.max(1, Math.min(this.maxSeconds, seg));
    }

    /** Dari {@code form.video} (boleh null → semua cadangan). {@code max_mb} dipakai bila {@code max_bytes} tak ada. */
    public static WaStatusVideoRules from(@Nullable JSONObject v) {
        if (v == null) return new WaStatusVideoRules(0, 0, 0, 0, 0);
        long bytes = v.optLong("max_bytes", 0);
        if (bytes <= 0 && v.optDouble("max_mb", 0) > 0) bytes = (long) (v.optDouble("max_mb") * 1048576);
        WaStatusVideoRules r = new WaStatusVideoRules(v.optInt("max_seconds", 0), v.optInt("max_parts", 0), bytes,
                v.optInt("long_side", 0), v.optInt("segment_seconds", 0));
        if (v.optInt("pass_long_side", 0) > 0) r.passLongSide = v.optInt("pass_long_side");
        return r;
    }

    /**
     * Ukuran keluaran kompres {lebar, tinggi}: RASIO ASLI (rotasi 90/270 ditukar dulu), sisi terpanjang
     * {@link #longSide} (tak diperbesar), kelipatan 16 (encoder H.264 HP). Bukan bingkai persegi — bingkai persegi
     * membuat Status 9:16 tampil kecil berbingkai hitam, tak penuh layar.
     */
    public int[] outputSize(int width, int height, int rotation) {
        int w = width, h = height;
        if (rotation == 90 || rotation == 270) {
            w = height;
            h = width;
        }
        if (w <= 0 || h <= 0) return new int[]{longSide * 9 / 16 / 16 * 16, longSide};
        double s = Math.min(1.0, (double) longSide / Math.max(w, h));
        return new int[]{r16(w * s), r16(h * s)};
    }

    private static int r16(double x) {
        return Math.max(16, (int) Math.floor(x / 16.0) * 16);   // ke bawah: tak pernah diperbesar
    }

    /** MP4 yang sudah muat (ukuran, durasi satu potongan, ≤ 1920 px & sisi pendek ≤ 1080) → tak perlu dikompres. */
    public boolean usableAsIs(@Nullable String mime, long bytes, long durationMs, int width, int height) {
        return "video/mp4".equals(mime) && bytes > 0 && bytes <= maxBytes && durationMs <= segmentMs() + 500
                && Math.max(width, height) <= passLongSide && Math.min(width, height) <= 1080;
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
