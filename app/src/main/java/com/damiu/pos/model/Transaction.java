package com.damiu.pos.model;

import java.util.ArrayList;
import java.util.List;

public class Transaction {
    public static final String TYPE_JUAL = "JUAL";       // galon keluar ke pelanggan
    public static final String TYPE_KEMBALI = "KEMBALI"; // galon kembali dari pelanggan

    public static final String ONGKIR_NONE = "none";
    public static final String ONGKIR_PER_GALON = "per_galon";
    public static final String ONGKIR_BORONGAN = "borongan";

    // Apakah botol galon di-pinjam (default, customer harus mengembalikan)
    // atau di-beli (milik pelanggan, tidak akan ditagih)
    public static final String OWNERSHIP_PINJAM = "PINJAM";
    public static final String OWNERSHIP_BELI = "BELI";

    private long id;
    private long customerId;
    private String customerName; // for display purposes
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
    private String tanggal;
    private String catatan;
    private List<TransactionItem> items = new ArrayList<>();

    public Transaction() {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getCustomerId() { return customerId; }
    public void setCustomerId(long customerId) { this.customerId = customerId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

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

    public String getTanggal() { return tanggal; }
    public void setTanggal(String tanggal) { this.tanggal = tanggal; }

    public String getCatatan() { return catatan; }
    public void setCatatan(String catatan) { this.catatan = catatan; }

    public List<TransactionItem> getItems() { return items; }
    public void setItems(List<TransactionItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }
}
