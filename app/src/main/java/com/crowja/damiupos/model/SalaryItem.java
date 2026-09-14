package com.crowja.damiupos.model;

/**
 * Komponen gaji bebas per staf:
 * <ul>
 *   <li>INCOME (tunjangan): jumlah = qty × rate.</li>
 *   <li>DEDUCTION (potongan manual, legacy): jumlah = qty × rate.</li>
 *   <li>ANGSURAN: cicilan/bulan = {@code rate} (jadi potongan otomatis),
 *       {@code qty} dipakai menyimpan SISA HUTANG (untuk catatan, bukan dikali).</li>
 * </ul>
 */
public class SalaryItem {

    public static final String KIND_INCOME = "INCOME";
    public static final String KIND_DEDUCTION = "DEDUCTION";
    public static final String KIND_ANGSURAN = "ANGSURAN";

    private long id;
    private long userId;
    private String kind = KIND_INCOME;
    private String label;
    private double qty = 1;
    private double rate;
    private int sortOrder;
    private boolean archived;   // angsuran lunas → diarsipkan, tidak ditagihkan

    public SalaryItem() {}

    public SalaryItem(String kind, String label, double qty, double rate) {
        this.kind = kind;
        this.label = label;
        this.qty = qty;
        this.rate = rate;
    }

    public long getId() { return id; }
    public void setId(long v) { this.id = v; }

    public long getUserId() { return userId; }
    public void setUserId(long v) { this.userId = v; }

    public String getKind() { return kind != null ? kind : KIND_INCOME; }
    public void setKind(String v) { this.kind = v; }
    public boolean isIncome() { return KIND_INCOME.equals(getKind()); }
    public boolean isDeduction() { return KIND_DEDUCTION.equals(kind); }
    public boolean isAngsuran() { return KIND_ANGSURAN.equals(kind); }

    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }

    public double getQty() { return qty; }
    public void setQty(double v) { this.qty = v; }

    public double getRate() { return rate; }
    public void setRate(double v) { this.rate = v; }

    public double getAmount() { return qty * rate; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { this.sortOrder = v; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean v) { this.archived = v; }
}
