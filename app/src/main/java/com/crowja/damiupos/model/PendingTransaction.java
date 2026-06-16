package com.crowja.damiupos.model;

/**
 * Transaksi yang ditunda ("Transaksi Pending") — dicatat staf saat sebuah
 * pesanan belum bisa dieksekusi (mis. hendak pulang, stok habis sementara).
 *
 * <p>Hanya menyimpan ringkasan secukupnya untuk membuka kembali layar
 * "Transaksi Baru" terisi (pelanggan + catatan/ringkasan pesanan). Saat
 * transaksi benar-benar dieksekusi, baris pending dihapus.
 */
public class PendingTransaction {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;

    private long id;
    private long customerId = -1;     // -1 = belum dipilih (mis. pelanggan Umum)
    private String customerName;      // snapshot nama saat dibuat
    private String customerPhone;     // snapshot nomor (untuk struk/prefill)
    private String note;              // ringkasan pesanan + catatan staf
    private long createdBy;           // user id pembuat
    private String createdByName;     // snapshot nama pembuat
    private int status = STATUS_PENDING;
    private String createdAt;

    public PendingTransaction() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCustomerId() { return customerId; }
    public void setCustomerId(long v) { this.customerId = v; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String v) { this.customerName = v; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String v) { this.customerPhone = v; }

    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }

    public long getCreatedBy() { return createdBy; }
    public void setCreatedBy(long v) { this.createdBy = v; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String v) { this.createdByName = v; }

    public int getStatus() { return status; }
    public void setStatus(int v) { this.status = v; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
}
