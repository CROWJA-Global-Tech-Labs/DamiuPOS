package com.crowja.damiupos.model;

import java.util.ArrayList;
import java.util.List;

public class Transaction {
    public static final String TYPE_JUAL = "JUAL";       // galon keluar ke pelanggan
    public static final String TYPE_KEMBALI = "KEMBALI"; // galon kembali dari pelanggan

    public static final String ONGKIR_NONE = "none";
    public static final String ONGKIR_PER_GALON = "per_galon";
    public static final String ONGKIR_BORONGAN = "borongan";

    // Apakah botol galon di-pinjam (default, customer harus mengembalikan)
    // atau di-beli (milik pelanggan, tidak akan ditagih).
    // BAWA_SENDIRI: pelanggan bawa botol sendiri, depot hanya isi air
    // (tidak ada botol keluar, tidak ada harga botol ditagih).
    public static final String OWNERSHIP_PINJAM = "PINJAM";
    public static final String OWNERSHIP_BELI = "BELI";
    public static final String OWNERSHIP_BAWA_SENDIRI = "BAWA_SENDIRI";

    /** Penanda catatan: order dibuat PELANGGAN SENDIRI lewat halaman Order Online publik (kampanye
     *  jenis ORDER), bukan diinput staf — cermin App\Models\Transaction::SELF_ORDER_MARKER (web). */
    public static final String SELF_ORDER_MARKER = "[ORDER ONLINE]";

    // Metode pembayaran (untuk transaksi JUAL).
    public static final String PAY_TUNAI = "TUNAI";
    public static final String PAY_QRIS = "QRIS";
    public static final String PAY_TRANSFER = "TRANSFER";
    /** Galonnya keluar sekarang, uangnya belakangan. Penjualan TETAP tercatat penuh (omzet & bonus
     *  tak berubah); yang dicatat sebagai piutang hanya uangnya — lihat CustomerDebtDao. */
    public static final String PAY_HUTANG = "HUTANG";

    // Antrian Delivery: status pemrosesan order.
    public static final String DELIVERY_PENDING = "PENDING";
    public static final String DELIVERY_DONE = "DONE";
    // Diparkir keluar dari antrian aktif, dijadwalkan lanjut otomatis — cermin
    // App\Support\TertundaSchedule di web (lihat COL_DELIVERY_TERTUNDA_RESUME_AT).
    public static final String DELIVERY_TERTUNDA = "TERTUNDA";

    private long id;
    private long customerId;
    private String customerName; // for display purposes
    private String customerPhone; // for display purposes (export, struk, dll.)
    private String customerAddress; // display (antrian delivery)
    private double customerLat;     // display — navigasi antrian delivery
    private double customerLng;     // display — navigasi antrian delivery
    private boolean customerPriority; // display — pelanggan prioritas (badge ⭐ di kartu antrian)
    /** Urutan antar MANUAL dari Strategi Pengiriman (web). 0/absen = otomatis. Pull-only. */
    private int deliverySeq;
    /** ⚡ Prioritas PENGIRIMAN INI saja, ditandai operator (+ alasan). Beda dari ⭐ di atas yang
     *  berlaku untuk SEMUA order pelanggan tsb. HP boleh menyetelnya SAAT order DIBUAT (toggle
     *  "Tandai Prioritas" di Transaksi Baru, {@see #priorityRequested}); setelah tersimpan, hanya
     *  web yang boleh mengubah/membatalkannya (server-authoritative pada UPDATE). */
    private String orderPriorityAt;

    private String orderPriorityReason;

    private String orderPriorityBy;
    /** Sinyal SEMENTARA "Tandai Prioritas" dicentang saat mengisi form Transaksi Baru — dibaca
     *  {@code TransactionDao.insert} untuk menstempel orderPriorityAt/By yang sebenarnya. Tidak
     *  disimpan/disinkron sendiri (bukan kolom DB); jangan dipakai untuk cek "sudah prioritas?"
     *  di luar alur pembuatan — pakai {@link #isOrderPriority()}. */
    private boolean priorityRequested;
    private boolean customerNoPhoto;  // display — pelanggan tujuan belum ada FOTO rumah (kartu antrian)
    private boolean customerNoCoord;  // display — pelanggan tujuan belum ada KOORDINAT (kartu antrian)
    private long productId;
    private String productName;  // for display purposes
    private String type;         // JUAL or KEMBALI
    private int jumlahGalon;
    private double hargaPerGalon;
    private double totalHarga;
    private double ongkir;
    private String ongkirType = ONGKIR_PER_GALON;
    private String galonOwnership = OWNERSHIP_PINJAM;
    private double hargaBotolGalon; // harga beli botol galon saat ownership=BELI
    private String paymentMethod;   // TUNAI | QRIS | TRANSFER (untuk JUAL)
    private long resellerId;        // reseller afiliasi yg dapat komisi (0 = tidak ada)
    private String tanggal;
    private String editedAt; // stempel sinkron (UTC ISO) — kunci urutan kronologis yang konsisten
    private String catatan;
    // Antrian Delivery (diisi untuk JUAL): status + waktu antri + waktu selesai.
    private String deliveryStatus;
    private String deliveryQueuedAt;
    private String deliveryTertundaAt;      // saat order ditandai TERTUNDA
    private String deliveryTertundaResumeAt; // jadwal lanjut otomatis (Pesanan Tertunda)
    /** "Pesanan Terbuka" (lelang): non-null = order TANPA perangkat tujuan spesifik, staf perangkat
     *  mana pun boleh mengklaimnya. Server-authoritative (pull-only) & PERMANEN — tak pernah
     *  di-null-kan lagi setelah diklaim (lihat App\Support\Reports::resumeDueTertunda di web). Masih
     *  "terbuka" secara bisnis = kolom ini terisi DAN kolom mentah delivery_device_uuid masih kosong
     *  (dicek langsung di query DAO — lihat {@code TransactionDao.getDeliveryQueue}). */
    private String deliveryOpenDispatchAt;
    /** Badge ✏️ "Sudah Diubah" — non-null = order ini pernah di-edit (web owner-direct, HP langsung
     *  saat masih di antrian, atau disetujui via email) sejak dibuat. Server-authoritative, pull-only. */
    private String lastManualEditAt;
    /** Badge 🗑️ "Void Diajukan" — non-null selama ada permintaan void PENDING untuk order ini.
     *  Server-authoritative, pull-only; lepas lagi kalau ditolak (disetujui → order lenyap dari
     *  antrian sama sekali lewat tombstone). */
    private String voidRequestPendingAt;
    private String deliveryDoneAt;
    private String deliveryToken;    // token link lacak publik (web /track/{token})
    // Lokasi tujuan pengiriman terpilih (multi-lokasi pelanggan). Persisted & disinkron.
    // 0/null = tidak dipilih → navigasi fallback ke koordinat pelanggan.
    private String deliveryDestName;
    private double deliveryDestLat;
    private double deliveryDestLng;
    // "Perangkat yang ditugaskan" (marketing/SPV): uuid perangkat lain yang ditugaskan menangani
    // transaksi ini. null/kosong = perangkat sendiri (tanpa penugasan). Server yang menerjemahkannya.
    private String assignedDeviceUuid;
    /** ID transaksi unik struk (<KODE>-DDMMYY-<COUNTER>) — cermin App\Support\ReceiptNumber (web).
     *  Ditetapkan SEKALI di TransactionDao.insert() untuk baris JUAL, tak pernah diubah. */
    private String receiptNo;
    private List<TransactionItem> items = new ArrayList<>();

    public Transaction() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCustomerId() { return customerId; }
    public void setCustomerId(long customerId) { this.customerId = customerId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String v) { this.receiptNo = v; }

    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String v) { this.customerAddress = v; }
    public boolean isCustomerPriority() { return customerPriority; }
    public void setCustomerPriority(boolean v) { this.customerPriority = v; }

    public int getDeliverySeq() { return deliverySeq; }
    public void setDeliverySeq(int v) { this.deliverySeq = v; }

    public String getOrderPriorityAt() { return orderPriorityAt; }
    public void setOrderPriorityAt(String v) { this.orderPriorityAt = v; }

    public String getOrderPriorityReason() { return orderPriorityReason; }
    public void setOrderPriorityReason(String v) { this.orderPriorityReason = v; }

    public String getOrderPriorityBy() { return orderPriorityBy; }
    public void setOrderPriorityBy(String v) { this.orderPriorityBy = v; }

    public boolean isPriorityRequested() { return priorityRequested; }
    public void setPriorityRequested(boolean v) { this.priorityRequested = v; }

    /** Ditandai ⚡ prioritas? HANYA cek terisi/tidak — JANGAN membandingkan string waktunya:
     *  nilai ini datang dari server sebagai ISO-8601 UTC sedangkan tulisan lokal berformat lain. */
    public boolean isOrderPriority() {
        return orderPriorityAt != null && !orderPriorityAt.trim().isEmpty();
    }

    public boolean isCustomerNoPhoto() { return customerNoPhoto; }
    public void setCustomerNoPhoto(boolean v) { this.customerNoPhoto = v; }

    public boolean isCustomerNoCoord() { return customerNoCoord; }
    public void setCustomerNoCoord(boolean v) { this.customerNoCoord = v; }

    /** Data pelanggan tujuan belum lengkap untuk delivery (foto ATAU koordinat kosong) — kartu antrian
     *  diberi tanda ❗ + berkedip. Cermin {@link com.crowja.damiupos.model.Customer#isIncomplete()}. */
    public boolean isCustomerDataIncomplete() { return customerNoPhoto || customerNoCoord; }

    public double getCustomerLat() { return customerLat; }
    public void setCustomerLat(double v) { this.customerLat = v; }

    public double getCustomerLng() { return customerLng; }
    public void setCustomerLng(double v) { this.customerLng = v; }

    public long getProductId() { return productId; }
    public void setProductId(long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getJumlahGalon() { return jumlahGalon; }
    public void setJumlahGalon(int jumlahGalon) { this.jumlahGalon = jumlahGalon; }

    public double getHargaPerGalon() { return hargaPerGalon; }
    public void setHargaPerGalon(double hargaPerGalon) { this.hargaPerGalon = hargaPerGalon; }

    public double getTotalHarga() { return totalHarga; }
    public void setTotalHarga(double totalHarga) { this.totalHarga = totalHarga; }

    public double getOngkir() { return ongkir; }
    public void setOngkir(double ongkir) { this.ongkir = ongkir; }

    public String getOngkirType() { return ongkirType; }
    public void setOngkirType(String ongkirType) { this.ongkirType = ongkirType; }

    public String getGalonOwnership() { return galonOwnership; }
    public void setGalonOwnership(String v) {
        this.galonOwnership = v != null ? v : OWNERSHIP_PINJAM;
    }

    public double getHargaBotolGalon() { return hargaBotolGalon; }
    public void setHargaBotolGalon(double v) { this.hargaBotolGalon = v; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String v) { this.paymentMethod = v; }

    /** Label ramah metode pembayaran ("Tunai"/"QRIS"/"Transfer"/"Hutang"), "" kalau kosong. */
    public String getPaymentMethodLabel() {
        if (PAY_TUNAI.equals(paymentMethod)) return "Tunai";
        if (PAY_QRIS.equals(paymentMethod)) return "QRIS";
        if (PAY_TRANSFER.equals(paymentMethod)) return "Transfer";
        if (PAY_HUTANG.equals(paymentMethod)) return "Hutang";
        return "";
    }

    public long getResellerId() { return resellerId; }
    public void setResellerId(long v) { this.resellerId = v; }

    public String getTanggal() { return tanggal; }
    public void setTanggal(String tanggal) { this.tanggal = tanggal; }

    public String getEditedAt() { return editedAt; }
    public void setEditedAt(String v) { this.editedAt = v; }

    /** Waktu efektif untuk tampilan & urutan: utamakan edited_at (stempel sinkron yang
     *  konsisten UTC), jatuh ke tanggal kalau kosong. tanggal lama hasil sinkron bisa
     *  ter-skew tz, sedangkan edited_at selalu konsisten — jadi ini yang dipakai. */
    public String getEffectiveTime() {
        return (editedAt != null && !editedAt.isEmpty()) ? editedAt : tanggal;
    }

    public String getCatatan() { return catatan; }
    public void setCatatan(String catatan) { this.catatan = catatan; }

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String v) { this.deliveryStatus = v; }

    public String getDeliveryQueuedAt() { return deliveryQueuedAt; }
    public void setDeliveryQueuedAt(String v) { this.deliveryQueuedAt = v; }

    public String getDeliveryTertundaAt() { return deliveryTertundaAt; }
    public void setDeliveryTertundaAt(String v) { this.deliveryTertundaAt = v; }

    public String getDeliveryTertundaResumeAt() { return deliveryTertundaResumeAt; }
    public void setDeliveryTertundaResumeAt(String v) { this.deliveryTertundaResumeAt = v; }

    public String getDeliveryOpenDispatchAt() { return deliveryOpenDispatchAt; }
    public void setDeliveryOpenDispatchAt(String v) { this.deliveryOpenDispatchAt = v; }

    /** Masih "Pesanan Terbuka" (belum diklaim siapa pun)? HANYA cek terisi — cocok dgn
     *  {@link #isOrderPriority()}: nilainya ISO-8601 UTC dari server, jangan dibandingkan. */
    public boolean isOpenDispatch() {
        return deliveryOpenDispatchAt != null && !deliveryOpenDispatchAt.trim().isEmpty();
    }

    public String getLastManualEditAt() { return lastManualEditAt; }
    public void setLastManualEditAt(String v) { this.lastManualEditAt = v; }

    /** Badge ✏️ — order ini pernah di-edit sejak dibuat (owner web, HP langsung, atau via persetujuan). */
    public boolean wasManuallyEdited() {
        return lastManualEditAt != null && !lastManualEditAt.trim().isEmpty();
    }

    public String getVoidRequestPendingAt() { return voidRequestPendingAt; }
    public void setVoidRequestPendingAt(String v) { this.voidRequestPendingAt = v; }

    /** Badge 🗑️ — ada permintaan void PENDING untuk order ini (belum diputuskan). */
    public boolean hasPendingVoidRequest() {
        return voidRequestPendingAt != null && !voidRequestPendingAt.trim().isEmpty();
    }

    /** Order ini dibuat pelanggan sendiri via halaman Order Online (kampanye jenis ORDER)? */
    public boolean isSelfOrder() {
        return catatan != null && catatan.contains(SELF_ORDER_MARKER);
    }

    public String getDeliveryDoneAt() { return deliveryDoneAt; }
    public void setDeliveryDoneAt(String v) { this.deliveryDoneAt = v; }

    public String getDeliveryToken() { return deliveryToken; }
    public void setDeliveryToken(String v) { this.deliveryToken = v; }

    public String getDeliveryDestName() { return deliveryDestName; }
    public void setDeliveryDestName(String v) { this.deliveryDestName = v; }

    public String getAssignedDeviceUuid() { return assignedDeviceUuid; }
    public void setAssignedDeviceUuid(String v) { this.assignedDeviceUuid = v; }

    public double getDeliveryDestLat() { return deliveryDestLat; }
    public void setDeliveryDestLat(double v) { this.deliveryDestLat = v; }

    public double getDeliveryDestLng() { return deliveryDestLng; }
    public void setDeliveryDestLng(double v) { this.deliveryDestLng = v; }

    public List<TransactionItem> getItems() { return items; }
    public void setItems(List<TransactionItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
}
