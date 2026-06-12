package com.crowja.damiupos.model;

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
    private int galonKeluar;   // total galon JUAL PINJAM (basis saldo pinjam)
    private int galonKembali;  // total galon yang dikembalikan
    private int totalTransaksi;
    private int galonTotalOrdered; // total galon dari SEMUA transaksi JUAL (semua ownership)
    private String firstOrderDate; // tanggal transaksi JUAL pertama (untuk hitung gl/hr)

    // Reseller
    private boolean isReseller;
    private String resellerSince;   // komisi dihitung dari JUAL setelah tanggal ini
    private int komisiGalon;        // calculated: total galon JUAL sejak jadi reseller
    private boolean komisiAddToPrice = true; // default ON: komisi ditambahkan ke harga jual

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

    public int getGalonTotalOrdered() { return galonTotalOrdered; }
    public void setGalonTotalOrdered(int v) { this.galonTotalOrdered = v; }

    public String getFirstOrderDate() { return firstOrderDate; }
    public void setFirstOrderDate(String v) { this.firstOrderDate = v; }

    /**
     * Konsumsi galon per hari = total galon di-order / jumlah hari sejak order
     * pertama (minimal 1 hari supaya tidak bagi nol). 0 kalau belum pernah order.
     */
    public double getKonsumsiPerHari() {
        if (galonTotalOrdered <= 0 || firstOrderDate == null || firstOrderDate.isEmpty()) {
            return 0;
        }
        try {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            java.util.Date first = sdf.parse(firstOrderDate.length() >= 19
                    ? firstOrderDate.substring(0, 19) : firstOrderDate);
            if (first == null) return 0;
            long diffMs = System.currentTimeMillis() - first.getTime();
            double days = diffMs / (1000.0 * 60 * 60 * 24);
            if (days < 1) days = 1; // pelanggan baru order hari ini → anggap 1 hari
            return galonTotalOrdered / days;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isReseller() { return isReseller; }
    public void setReseller(boolean v) { this.isReseller = v; }

    public String getResellerSince() { return resellerSince; }
    public void setResellerSince(String v) { this.resellerSince = v; }

    /** Total galon JUAL sejak jadi reseller (basis perhitungan komisi). */
    public int getKomisiGalon() { return komisiGalon; }
    public void setKomisiGalon(int v) { this.komisiGalon = v; }

    /** True = komisi reseller ini ditambahkan ke harga air minum saat transaksi. */
    public boolean isKomisiAddToPrice() { return komisiAddToPrice; }
    public void setKomisiAddToPrice(boolean v) { this.komisiAddToPrice = v; }
}
