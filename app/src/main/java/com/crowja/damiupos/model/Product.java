package com.crowja.damiupos.model;

public class Product {
    private long id;
    private String uuid;   // sync_uuid (sama di web + semua HP) — kunci harga khusus per pelanggan
    private String name;
    /** Label PENDEK dari dashboard (mis. "RO") untuk chip berwarna di kartu Antrian Delivery.
     *  Kosong/null = pemanggil memendekkan {@link #name} sendiri. */
    private String slug;
    private double hargaJual;
    private double hargaModal;
    private String color; // hex color e.g. "#1565C0"
    private String createdAt;

    public Product() {}

    public Product(String name, double hargaJual, double hargaModal) {
        this.name = name;
        this.hargaJual = hargaJual;
        this.hargaModal = hargaModal;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public double getHargaJual() { return hargaJual; }
    public void setHargaJual(double hargaJual) { this.hargaJual = hargaJual; }

    public double getHargaModal() { return hargaModal; }
    public void setHargaModal(double hargaModal) { this.hargaModal = hargaModal; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    /** Profit per unit */
    public double getProfit() {
        return hargaJual - hargaModal;
    }
}
