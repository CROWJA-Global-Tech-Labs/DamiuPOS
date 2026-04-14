package com.damiu.pos.model;

public class Customer {
    private long id;
    private String name;
    private String phone;
    private String address;
    private String photoPath;   // path foto rumah
    private double latitude;    // koordinat GPS
    private double longitude;
    private String createdAt;

    // Calculated fields (not stored in DB)
    private int galonKeluar;   // total galon yang dipinjamkan
    private int galonKembali;  // total galon yang dikembalikan
    private int totalTransaksi;

    public Customer() {}

    public Customer(String name, String phone, String address) {
        this.name = name;
        this.phone = phone;
        this.address = address;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public int getGalonKeluar() { return galonKeluar; }
    public void setGalonKeluar(int galonKeluar) { this.galonKeluar = galonKeluar; }

    public int getGalonKembali() { return galonKembali; }
    public void setGalonKembali(int galonKembali) { this.galonKembali = galonKembali; }

    public int getTotalTransaksi() { return totalTransaksi; }
    public void setTotalTransaksi(int totalTransaksi) { this.totalTransaksi = totalTransaksi; }

    /** Saldo galon yang masih berada di pelanggan */
    public int getSaldoGalon() {
        return galonKeluar - galonKembali;
    }
}
