package com.crowja.damiupos.model;

/**
 * Konfigurasi gaji per staf untuk slip gaji cut-off. Diatur admin. Nilai
 * dinamis (tunjangan harian × hari hadir, lembur × jam, bonus galon × galon
 * terjual) dihitung saat pembuatan slip, bukan disimpan di sini.
 */
public class SalaryConfig {

    private long userId;
    private boolean gajiPokokEnabled;
    private double gajiPokok;
    private double tunjanganHarian;   // per hari hadir
    private double lemburRate;        // per jam lembur
    private double angsuranSisa;      // sisa hutang ("Bulan Lalu")
    private double angsuranBulan;     // cicilan per bulan
    private String jabatan;
    private String status;            // mis. "Full Time"
    private String bankName;          // mis. "BSI"
    private String bankNo;
    private String bankHolder;

    public long getUserId() { return userId; }
    public void setUserId(long v) { this.userId = v; }

    public boolean isGajiPokokEnabled() { return gajiPokokEnabled; }
    public void setGajiPokokEnabled(boolean v) { this.gajiPokokEnabled = v; }

    public double getGajiPokok() { return gajiPokok; }
    public void setGajiPokok(double v) { this.gajiPokok = v; }

    public double getTunjanganHarian() { return tunjanganHarian; }
    public void setTunjanganHarian(double v) { this.tunjanganHarian = v; }

    public double getLemburRate() { return lemburRate; }
    public void setLemburRate(double v) { this.lemburRate = v; }

    public double getAngsuranSisa() { return angsuranSisa; }
    public void setAngsuranSisa(double v) { this.angsuranSisa = v; }

    public double getAngsuranBulan() { return angsuranBulan; }
    public void setAngsuranBulan(double v) { this.angsuranBulan = v; }

    public String getJabatan() { return jabatan; }
    public void setJabatan(String v) { this.jabatan = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }

    public String getBankNo() { return bankNo; }
    public void setBankNo(String v) { this.bankNo = v; }

    public String getBankHolder() { return bankHolder; }
    public void setBankHolder(String v) { this.bankHolder = v; }
}
