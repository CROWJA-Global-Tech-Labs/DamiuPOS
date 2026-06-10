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

    private long id;
    private String name;        // mis. "Bayar listrik", "Beli botol galon kosong"
    private double amount;      // nominal dalam Rupiah
    private String photoPath;   // path absolut file foto struk (nullable)
    private String note;        // catatan tambahan (nullable)
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

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
