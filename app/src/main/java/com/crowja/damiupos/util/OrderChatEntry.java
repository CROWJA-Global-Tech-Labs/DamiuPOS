package com.crowja.damiupos.util;

/**
 * Kapan kartu antrean dari SERVER (Perangkat Lain, Antrean Tertunda) menawarkan "💬 Chat Pesanan".
 *
 * <p>Antrian Saya menawarkannya untuk setiap order JUAL. Server membuka chat-nya saat pertama
 * dibuka ({@code OrderChat::initOnDemand}: order JUAL berpelanggan, dicari di chat WA akun cabang),
 * jadi syarat "sesi sudah ada" ({@code chat_session}) saja menyembunyikan tombol dari order buatan
 * staf yang percakapannya tetap bisa dibuka — mis. order yang dikomplain pelanggan lewat WhatsApp.</p>
 */
public final class OrderChatEntry {

    private OrderChatEntry() {}

    /**
     * @param chatSession  {@code chat_session} baris server — sesi aktif sudah ada (order agen AI)
     * @param type         JUAL | KEMBALI | … ; null/kosong = baris tanpa kolom tipe (Perangkat Lain)
     * @param pickupOnly   {@code pickup_only} — ambil galon saja, bukan pesanan jual
     * @param customerUuid {@code customer_uuid} — kosong = pelanggan Umum (tak ada nomor untuk dicari)
     */
    public static boolean available(boolean chatSession, String type, boolean pickupOnly, String customerUuid) {
        if (chatSession) return true;
        if (pickupOnly) return false;
        String t = type == null ? "" : type.trim();
        if (!t.isEmpty() && !"JUAL".equalsIgnoreCase(t)) return false;
        String c = customerUuid == null ? "" : customerUuid.trim();
        return !c.isEmpty() && !"null".equals(c);
    }
}
