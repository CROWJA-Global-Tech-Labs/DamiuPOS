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

    // Metode pembayaran (untuk transaksi JUAL).
    public static final String PAY_TUNAI = "TUNAI";
    public static final String PAY_QRIS = "QRIS";
    public static final String PAY_TRANSFER = "TRANSFER";

    // Antrian Delivery: status pemrosesan order.
    public static final String DELIVERY_PENDING = "PENDING";
    public static final String DELIVERY_DONE = "DONE";

    private long id;
    private long customerId;
    private String customerName; // for display purposes
    private String customerPhone; // for display purposes (export, struk, dll.)
    private String customerAddress; // display (antrian delivery)
    private double customerLat;     // display — navigasi antrian delivery
    private double customerLng;     // display — navigasi antrian delivery
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
    private String catatan;
    // Antrian Delivery (diisi untuk JUAL): status + waktu antri + waktu selesai.
    private String deliveryStatus;
    private String deliveryQueuedAt;
    private String deliveryDoneAt;
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

    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String v) { this.customerAddress = v; }

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

    /** Label ramah metode pembayaran ("Tunai"/"QRIS"/"Transfer"), "" kalau kosong. */
    public String getPaymentMethodLabel() {
        if (PAY_TUNAI.equals(paymentMethod)) return "Tunai";
        if (PAY_QRIS.equals(paymentMethod)) return "QRIS";
        if (PAY_TRANSFER.equals(paymentMethod)) return "Transfer";
        return "";
    }

    public long getResellerId() { return resellerId; }
    public void setResellerId(long v) { this.resellerId = v; }

    public String getTanggal() { return tanggal; }
    public void setTanggal(String tanggal) { this.tanggal = tanggal; }

    public String getCatatan() { return catatan; }
    public void setCatatan(String catatan) { this.catatan = catatan; }

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String v) { this.deliveryStatus = v; }

    public String getDeliveryQueuedAt() { return deliveryQueuedAt; }
    public void setDeliveryQueuedAt(String v) { this.deliveryQueuedAt = v; }

    public String getDeliveryDoneAt() { return deliveryDoneAt; }
    public void setDeliveryDoneAt(String v) { this.deliveryDoneAt = v; }

    public List<TransactionItem> getItems() { return items; }
    public void setItems(List<TransactionItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
}
