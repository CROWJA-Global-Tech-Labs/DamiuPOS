package com.crowja.damiupos.model;

/**
 * Pesanan masuk yang dideteksi dari notifikasi WhatsApp.
 * Belum jadi {@link Transaction} sampai user approve di OrderInboxActivity.
 */
public class OrderInbox {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** Sudah selesai/ditolak DAN user tap "Arsipkan" — tidak muncul di
     *  inbox utama, hanya di view Arsip. */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final String PARSER_REGEX = "regex";
    public static final String PARSER_CLAUDE = "claude";

    private long id;
    private String senderName;     // Title dari notif WA (biasanya nama kontak / nomor)
    private String senderPhone;    // Best-effort extract jika sender berupa nomor
    private long customerId;       // 0 jika belum ter-match dengan pelanggan
    private String rawMessage;     // Isi notif apa adanya
    private String parsedJson;     // Hasil parser (JSON: is_order, type, items, urgent, ...)
    private String parserUsed;     // 'regex' atau 'claude'
    private String status;         // PENDING / APPROVED / REJECTED
    private long trxId;            // Id transaksi setelah approved (kalau ada)
    private boolean replied;       // true setelah user klik "Balas"
    private String receivedAt;

    public OrderInbox() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String v) { this.senderName = v; }

    public String getSenderPhone() { return senderPhone; }
    public void setSenderPhone(String v) { this.senderPhone = v; }

    public long getCustomerId() { return customerId; }
    public void setCustomerId(long v) { this.customerId = v; }

    public String getRawMessage() { return rawMessage; }
    public void setRawMessage(String v) { this.rawMessage = v; }

    public String getParsedJson() { return parsedJson; }
    public void setParsedJson(String v) { this.parsedJson = v; }

    public String getParserUsed() { return parserUsed; }
    public void setParserUsed(String v) { this.parserUsed = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v != null ? v : STATUS_PENDING; }

    public long getTrxId() { return trxId; }
    public void setTrxId(long v) { this.trxId = v; }

    public boolean isReplied() { return replied; }
    public void setReplied(boolean v) { this.replied = v; }

    public String getReceivedAt() { return receivedAt; }
    public void setReceivedAt(String v) { this.receivedAt = v; }
}
