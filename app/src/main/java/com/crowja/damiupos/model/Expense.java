package com.crowja.damiupos.model;

/**
 * Pengeluaran operasional depot (listrik, gaji karyawan, beli stok galon kosong,
 * bensin, dll). Setiap entry punya nominal + opsional foto struk + catatan.
 *
 * <p>Dipakai untuk:
 * <ul>
 *     <li>Tracking pengeluaran harian di {@code ExpenseListActivity}</li>
 *     <li>Hitung profit di laporan (pendapatan transaksi minus pengeluaran)</li>
 *     <li>Bukti struk di-attach via foto untuk audit trail</li>
 * </ul>
 */
public class Expense {

    /** Kategori pengeluaran. */
    public static final String CAT_AIR_BAKU = "AIR_BAKU";
    public static final String CAT_SEGEL = "SEGEL";
    public static final String CAT_TUTUP = "TUTUP";
    public static final String CAT_UMUM = "UMUM";
    public static final String CAT_KENDARAAN = "KENDARAAN";

    private long id;
    private String name;        // mis. "Bayar listrik", "Beli botol galon kosong"
    private double amount;      // nominal dalam Rupiah
    private String photoPath;   // path absolut file foto struk (nullable)
    private String note;        // catatan tambahan (nullable)
    private String category = CAT_UMUM; // AIR_BAKU | SEGEL | TUTUP | UMUM
    private double liters;      // liter air baku (untuk kategori AIR_BAKU)
    private int pcs;            // jumlah pcs (untuk kategori SEGEL / TUTUP)
    private String createdAt;   // timestamp dari SQLite

    public Expense() {}

    public Expense(String name, double amount, String photoPath, String note) {
        this.name = name;
        this.amount = amount;
        this.photoPath = photoPath;
        this.note = note;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    private String photoUrl;    // URL foto struk di server (untuk baris hasil sync tanpa file lokal)
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getCategory() { return category != null ? category : CAT_UMUM; }
    public void setCategory(String category) { this.category = category; }
    public boolean isAirBaku() { return CAT_AIR_BAKU.equals(category); }
    public boolean isSegel() { return CAT_SEGEL.equals(category); }
    public boolean isTutup() { return CAT_TUTUP.equals(category); }
    public boolean isKendaraan() { return CAT_KENDARAAN.equals(category); }
    /** True kalau kategori memakai input pcs (Segel / Tutup). */
    public boolean usesPcs() { return isSegel() || isTutup(); }
    /** True kalau kategori butuh input nama manual (Umum & Kendaraan — rincian bensin/service/oli beda-beda). */
    public boolean needsName() { return CAT_UMUM.equals(getCategory()) || CAT_KENDARAAN.equals(getCategory()); }

    public double getLiters() { return liters; }
    public void setLiters(double liters) { this.liters = liters; }

    public int getPcs() { return pcs; }
    public void setPcs(int pcs) { this.pcs = pcs; }

    /** Label kategori yang ramah untuk ditampilkan. */
    public String getCategoryLabel() {
        if (CAT_AIR_BAKU.equals(category)) return "Belanja Air Baku";
        if (CAT_SEGEL.equals(category)) return "Segel Galon";
        if (CAT_TUTUP.equals(category)) return "Tutup Galon";
        if (CAT_KENDARAAN.equals(category)) return "Kendaraan";
        return "Umum";
    }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    /** Nama operator yang menginput (atribusi; null utk baris lama/web tanpa nama). */
    private String createdByName;
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String v) { this.createdByName = v; }
}
