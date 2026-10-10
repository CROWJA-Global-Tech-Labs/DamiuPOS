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

    /** Penanda catatan: order dipesan RESELLER untuk pelanggannya (afiliasi) lewat link pemesanan
     *  reseller — cermin App\Models\Transaction::RESELLER_ORDER_MARKER (web). SENGAJA beda dari
     *  {@link #SELF_ORDER_MARKER}: penanda itu memicu dialog wajib "Kirim Struk WA" ke pelanggan,
     *  sedangkan pesanan reseller tak mengirim apa pun ke afiliasi saat order (ringkasannya ke
     *  reseller). Cadangan HP untuk {@link #deliveryProofRequired} — foto bukti Selesai WAJIB. */
    public static final String RESELLER_ORDER_MARKER = "[ORDER RESELLER]";

    /** Prefiks delivery_dest_name saat reseller memilih lokasinya SENDIRI sebagai tujuan antar
     *  pesanan afiliasinya ("Reseller: Kediaman") — cermin web; pelanggannya tetap si afiliasi. */
    public static final String RESELLER_DEST_PREFIX = "Reseller:";

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
    private String paymentConfirmedAt; // stempel waktu pelunasan cash bon (HUTANG) dikonfirmasi — null/kosong = belum lunas
    private String paymentProofUrl;    // URL foto bukti pelunasan cash bon yang sudah diunggah
    private long resellerId;        // reseller afiliasi yg dapat komisi (0 = tidak ada)
    private String tanggal;
    private String editedAt; // stempel sinkron (UTC ISO) — kunci urutan kronologis yang konsisten
    private String catatan;
    // Antrian Delivery (diisi untuk JUAL): status + waktu antri + waktu selesai.
    private String deliveryStatus;
    private String deliveryQueuedAt;
    private String deliveryTertundaAt;      // saat order ditandai TERTUNDA
    private String sourceWa;                // akun WA tempat agen menerima pesanan (pull-only)
    private String orderedAt;               // saat order ASLINYA dibuat (tak bergeser saat ditunda)
    private String deliveryTertundaResumeAt; // jadwal lanjut otomatis (Pesanan Tertunda)
    /** Alasan ditunda, sync_uuid baris, dan "belum terdorong" (synced=0) — HANYA terisi dari
     *  TransactionDao.getTertundaQueue (Antrean Tertunda menggabungkannya dengan daftar server per
     *  uuid; baris lokal yang tak dikenal server hanya ditampilkan bila belum terdorong). Query lain
     *  selalu null/false di sini. */
    private String deliveryTertundaReason;
    private String syncUuid;
    private boolean syncPending;
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
    /** Badge 😠 "Komplain" — non-null bila pelanggan pernah komplain soal order ini via WhatsApp
     *  (Agen AI, Api\Agent\ComplaintController di server). Server-authoritative, pull-only -- HP
     *  tak pernah menulisnya sendiri. Log percakapannya sendiri diambil on-demand (lihat
     *  SyncApi.complaintLog / ChatLogActivity), bukan disinkron sebagai kolom. */
    private String complainedAt;
    /** Tombol "💬 Chat Pesanan" — non-null bila order ini punya sesi chat WA aktif di server
     *  (order dari FREZ AI Agent). Server-authoritative, pull-only; isi chat-nya diambil on-demand
     *  (SyncApi.orderChat / ChatLogActivity mode order). */
    private String chatSessionAt;
    /** Foto bukti Selesai WAJIB untuk order INI (pesanan link reseller), apa pun setelan cabang.
     *  Server-authoritative, pull-only — lihat DatabaseHelper.COL_DELIVERY_PROOF_REQUIRED. */
    private boolean deliveryProofRequired;
    private String deliveryDoneAt;
    /** Nama kurir yang menekan "Selesai" — bisa BEDA dari pembuat order (lihat markDelivered). */
    private String completedByName;
    /** Foto bukti selesai: berkas lokal perangkat ini, dan/atau URL hasil unggah. */
    private String proofPath;
    private String proofUrl;
    private String deliveryToken;    // token link lacak publik (web /track/{token})
    // Lokasi tujuan pengiriman terpilih (multi-lokasi pelanggan). Persisted & disinkron.
    // 0/null = tidak dipilih → navigasi fallback ke koordinat pelanggan.
    private String deliveryDestName;
    private double deliveryDestLat;
    private double deliveryDestLng;
    // "Perangkat yang ditugaskan" (marketing/SPV): uuid perangkat lain yang ditugaskan menangani
    // transaksi ini. null/kosong = perangkat sendiri (tanpa penugasan). Server yang menerjemahkannya.
    private String assignedDeviceUuid;
    /** Perangkat tujuan hasil RUTE server (delivery_device_uuid; pull-only, server-authoritative).
     *  null/kosong = belum di-route (tetap di HP asal). HANYA terisi dari query yang memetakannya
     *  (TransactionDao.getByCheckoutUuid) — baris dari query lain selalu null di sini. */
    private String deliveryDeviceUuid;
    /** ID transaksi struk (<KODE>-<DDMMYYHHMM>-<5 karakter acak>) — cermin App\Support\ReceiptNumber (web).
     *  Ditetapkan SEKALI di TransactionDao.insert() untuk baris JUAL, tak pernah diubah. */
    private String receiptNo;
    /** 🧺 Checkout multi-lokasi: uuid bersama semua leg JUAL satu checkout (raw uuid, seperti
     *  customer_debts.transaction_uuid — bukan Ref lokal), urutan leg (1 = UTAMA) & jumlah legnya.
     *  null/0 = order biasa. Ditetapkan SEKALI saat insert, imutabel. Lihat DatabaseHelper.COL_CHECKOUT_UUID
     *  — HP bisa hanya memegang leg ini saja, jadi jangan menganggap saudaranya ada lokal. */
    private String checkoutUuid;
    private int checkoutSeq;
    private int checkoutSize;
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

    public String getPaymentConfirmedAt() { return paymentConfirmedAt; }
    public void setPaymentConfirmedAt(String v) { this.paymentConfirmedAt = v; }

    public String getPaymentProofUrl() { return paymentProofUrl; }
    public void setPaymentProofUrl(String v) { this.paymentProofUrl = v; }

    /** True kalau cash bon (HUTANG) ini sudah dilunasi — ditandai lewat pengisian paymentConfirmedAt. */
    public boolean isPaymentConfirmed() {
        return paymentConfirmedAt != null && !paymentConfirmedAt.isEmpty();
    }

    /** Label ramah metode pembayaran ("Tunai"/"QRIS"/"Transfer"/"Hutang"), "" kalau kosong; tambah "· LUNAS" kalau cash bon sudah dikonfirmasi. */
    public String getPaymentMethodLabel() {
        String label;
        if (PAY_TUNAI.equals(paymentMethod)) {
            label = "Tunai";
        } else if (PAY_QRIS.equals(paymentMethod)) {
            label = "QRIS";
        } else if (PAY_TRANSFER.equals(paymentMethod)) {
            label = "Transfer";
        } else if (PAY_HUTANG.equals(paymentMethod)) {
            label = "Hutang";
        } else {
            return "";
        }
        return isPaymentConfirmed() ? label.concat(" · LUNAS") : label;
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

    public String getSourceWa() { return sourceWa; }
    public void setSourceWa(String v) { this.sourceWa = v; }

    public String getOrderedAt() { return orderedAt; }
    public void setOrderedAt(String v) { this.orderedAt = v; }

    public String getDeliveryTertundaAt() { return deliveryTertundaAt; }
    public void setDeliveryTertundaAt(String v) { this.deliveryTertundaAt = v; }

    public String getDeliveryTertundaResumeAt() { return deliveryTertundaResumeAt; }
    public void setDeliveryTertundaResumeAt(String v) { this.deliveryTertundaResumeAt = v; }

    public String getDeliveryTertundaReason() { return deliveryTertundaReason; }
    public void setDeliveryTertundaReason(String v) { this.deliveryTertundaReason = v; }

    public String getSyncUuid() { return syncUuid; }
    public void setSyncUuid(String v) { this.syncUuid = v; }

    public boolean isSyncPending() { return syncPending; }
    public void setSyncPending(boolean v) { this.syncPending = v; }

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

    public String getComplainedAt() { return complainedAt; }
    public void setComplainedAt(String v) { this.complainedAt = v; }

    /** Badge 😠 — pelanggan pernah komplain soal order ini via WhatsApp. */
    public boolean isComplained() {
        return complainedAt != null && !complainedAt.trim().isEmpty();
    }

    public String getChatSessionAt() { return chatSessionAt; }
    public void setChatSessionAt(String v) { this.chatSessionAt = v; }

    /** Tombol "💬 Chat Pesanan" — order ini punya sesi chat WA (dibuat FREZ AI Agent). */
    public boolean hasChatSession() {
        return chatSessionAt != null && !chatSessionAt.trim().isEmpty();
    }

    /** Order ini dibuat pelanggan sendiri via halaman Order Online (kampanye jenis ORDER)? Pesanan
     *  reseller TIDAK pernah dihitung di sini walau catatannya kebetulan memuat kedua penanda —
     *  kalau iya, kurir dipaksa mengirim struk WA ke afiliasi yang tak memesan sendiri. */
    public boolean isSelfOrder() {
        return catatan != null && catatan.contains(SELF_ORDER_MARKER) && !isResellerOrder();
    }

    /** Order ini dipesan reseller untuk afiliasinya lewat link pemesanan reseller? */
    public boolean isResellerOrder() {
        return catatan != null && catatan.contains(RESELLER_ORDER_MARKER);
    }

    public boolean isDeliveryProofRequired() { return deliveryProofRequired; }
    public void setDeliveryProofRequired(boolean v) { this.deliveryProofRequired = v; }

    /** Foto bukti Selesai wajib KHUSUS order ini: flag server ATAU penanda catatan. Penanda ikut
     *  dihitung karena tiba lewat catatan yang sudah tersinkron sejak lama — order reseller tetap
     *  tergerbang di HP yang belum menarik flag-nya (mis. baris lama sebelum migrasi v101). Setelan
     *  cabang & Cash Bon diputuskan terpisah (DeliveryProofPolicy). */
    public boolean requiresDeliveryProof() {
        return deliveryProofRequired || isResellerOrder();
    }

    /** Tujuan antar order ini lokasi milik RESELLER (bukan lokasi afiliasi)? */
    public boolean isResellerDestination() {
        return isResellerDestName(deliveryDestName);
    }

    /** "Reseller: Kediaman" (huruf besar/kecil bebas, spasi tepi diabaikan) → true. */
    public static boolean isResellerDestName(String destName) {
        if (destName == null) return false;
        String s = destName.trim();
        return s.regionMatches(true, 0, RESELLER_DEST_PREFIX, 0, RESELLER_DEST_PREFIX.length());
    }

    /** Nama lokasi reseller tanpa prefiks: "Reseller: Kediaman" → "Kediaman" ("" bila kosong). */
    public static String resellerDestLocationName(String destName) {
        if (!isResellerDestName(destName)) return "";
        return destName.trim().substring(RESELLER_DEST_PREFIX.length()).trim();
    }

    /** Label tujuan untuk kurir: "Dikirim ke lokasi reseller: Kediaman" — supaya tidak mencari
     *  rumah afiliasi (nama pelanggan di kartu) padahal galonnya diantar ke rumah reseller. */
    public static String resellerDestLabel(String destName) {
        String loc = resellerDestLocationName(destName);
        return "Dikirim ke lokasi reseller" + (loc.isEmpty() ? "" : ": " + loc);
    }

    public String getCompletedByName() { return completedByName; }
    public void setCompletedByName(String v) { this.completedByName = v; }

    public String getProofPath() { return proofPath; }
    public void setProofPath(String v) { this.proofPath = v; }

    public String getProofUrl() { return proofUrl; }
    public void setProofUrl(String v) { this.proofUrl = v; }

    public String getDeliveryDoneAt() { return deliveryDoneAt; }
    public void setDeliveryDoneAt(String v) { this.deliveryDoneAt = v; }

    public String getDeliveryToken() { return deliveryToken; }
    public void setDeliveryToken(String v) { this.deliveryToken = v; }

    public String getDeliveryDestName() { return deliveryDestName; }
    public void setDeliveryDestName(String v) { this.deliveryDestName = v; }

    public String getAssignedDeviceUuid() { return assignedDeviceUuid; }
    public void setAssignedDeviceUuid(String v) { this.assignedDeviceUuid = v; }

    public String getDeliveryDeviceUuid() { return deliveryDeviceUuid; }
    public void setDeliveryDeviceUuid(String v) { this.deliveryDeviceUuid = v; }

    /**
     * Order/leg ini ditangani perangkat LAIN, bukan {@code thisDeviceUuid}? Benar bila NIAT penugasan
     * (assigned_device_uuid) ATAU rute server (delivery_device_uuid) terisi dan bukan perangkat ini.
     * Untuk "Simpan & Selesaikan" checkout: hanya leg milik perangkat ini yang boleh diselesaikan
     * di sini, supaya Selesai & kredit galon jatuh ke kurir yang benar-benar mengantar.
     * thisDeviceUuid kosong (HP belum terhubung) → penugasan apa pun dianggap perangkat lain.
     */
    public boolean isHandledByOtherDevice(String thisDeviceUuid) {
        String me = thisDeviceUuid != null ? thisDeviceUuid.trim() : "";
        return isOtherDevice(assignedDeviceUuid, me) || isOtherDevice(deliveryDeviceUuid, me);
    }

    private static boolean isOtherDevice(String uuid, String me) {
        if (uuid == null) return false;
        String u = uuid.trim();
        return !u.isEmpty() && !u.equalsIgnoreCase(me);
    }

    public double getDeliveryDestLat() { return deliveryDestLat; }
    public void setDeliveryDestLat(double v) { this.deliveryDestLat = v; }

    public double getDeliveryDestLng() { return deliveryDestLng; }
    public void setDeliveryDestLng(double v) { this.deliveryDestLng = v; }

    public String getCheckoutUuid() { return checkoutUuid; }
    public void setCheckoutUuid(String v) { this.checkoutUuid = v; }

    public int getCheckoutSeq() { return checkoutSeq; }
    public void setCheckoutSeq(int v) { this.checkoutSeq = v; }

    public int getCheckoutSize() { return checkoutSize; }
    public void setCheckoutSize(int v) { this.checkoutSize = v; }

    /** Baris ini salah satu leg checkout multi-lokasi? Trio-nya harus utuh & sah (aturan yang sama
     *  dengan sanitasi server) — trio setengah jadi diperlakukan sebagai order biasa. */
    public boolean isCheckoutLeg() {
        return com.crowja.damiupos.checkout.CheckoutConstants.isValidTrio(checkoutUuid, checkoutSeq, checkoutSize);
    }

    /** Leg UTAMA (seq 1) — pemegang efek sekali-per-checkout: bayar hutang, gift, sesi chat. */
    public boolean isCheckoutPrimary() {
        return isCheckoutLeg() && checkoutSeq == 1;
    }

    public List<TransactionItem> getItems() { return items; }
    public void setItems(List<TransactionItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
}
