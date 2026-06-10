package com.crowja.damiupos.model;

public class Product {
    private long id;
    private String name;
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

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
