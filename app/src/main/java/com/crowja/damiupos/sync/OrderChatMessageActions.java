package com.crowja.damiupos.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keputusan MURNI (tanpa Android / org.json -- diuji di JVM, lihat src/test) untuk "✏️ Edit pesan" /
 * "🗑️ Hapus untuk semua orang" di "💬 Chat Pesanan" (tekan lama gelembung keluar).
 *
 * <p>Aturan sebenarnya (pesan keluar, teks, ≤ 15 menit untuk edit, ≤ 2 hari untuk hapus, pemanggil
 * boleh mengirim) dihitung SERVER per pesan: {@code editable}/{@code deletable} + batas waktunya
 * {@code edit_until}/{@code delete_until}. Di sini hanya: tawarkan aksi yang server izinkan DAN belum
 * lewat batas waktunya (polling inkremental tak mengirim ulang baris yang tak berubah, jadi bendera
 * {@code true} lama bisa basi), plus aturan tombol Simpan dan konfirmasi dua-ketuk Hapus.</p>
 */
public final class OrderChatMessageActions {

    private OrderChatMessageActions() {}

    public enum Action { EDIT, DELETE }

    /** Batas teks sunting = batas kirim server (OrderChat::SEND_TEXT_MAX). */
    public static final int TEXT_MAX = 4000;

    /** Ketukan kedua "Hapus" harus datang dalam rentang ini setelah ketukan pertama. */
    public static final long DELETE_ARM_WINDOW_MS = 4000L;

    /**
     * Aksi yang ditawarkan untuk satu gelembung.
     *
     * @param untilEditMs  epoch ms {@code edit_until}; {@link Long#MAX_VALUE} = tak dikirim server (tak dibatasi di klien)
     * @param untilDeleteMs epoch ms {@code delete_until}; idem
     */
    public static List<Action> offer(boolean outbound, boolean optimistic, boolean deleted, String waMessageId,
                                     boolean editable, long untilEditMs, boolean deletable, long untilDeleteMs,
                                     long nowMs) {
        if (!outbound || optimistic || deleted || waMessageId == null || waMessageId.isEmpty()) {
            return Collections.emptyList();
        }
        List<Action> out = new ArrayList<>(2);
        if (editable && nowMs < untilEditMs) out.add(Action.EDIT);
        if (deletable && nowMs < untilDeleteMs) out.add(Action.DELETE);
        return out;
    }

    /** Simpan aktif hanya bila teks baru tak kosong, berubah (abaikan spasi tepi), dan ≤ {@link #TEXT_MAX}. */
    public static boolean canSave(String original, String edited) {
        String e = edited == null ? "" : edited.trim();
        String o = original == null ? "" : original.trim();
        return !e.isEmpty() && !e.equals(o) && e.length() <= TEXT_MAX;
    }

    /** Ketukan "Hapus" ini mengeksekusi? true bila sudah dipersenjatai ({@code armedAtMs > 0}) dan masih dalam jendela. */
    public static boolean deleteConfirmed(long armedAtMs, long nowMs) {
        return armedAtMs > 0 && nowMs >= armedAtMs && nowMs - armedAtMs <= DELETE_ARM_WINDOW_MS;
    }
}
