package com.crowja.damiupos;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SalaryDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.SalaryConfig;
import com.crowja.damiupos.model.SalaryItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Kalkulasi slip gaji satu staf untuk satu periode cut-off. Menggabungkan
 * konfigurasi gaji (admin) dengan nilai dinamis: hari hadir, jam lembur/
 * kekurangan, dan bonus penjualan galon dari periode tersebut.
 */
public final class PayslipData {

    /** Satu baris penerimaan/pengurangan. */
    public static final class Line {
        public final String label;
        public final double qty;
        public final double rate;
        public final double amount;
        public final boolean showQty;   // tampilkan "qty x rate"
        public final String note;       // catatan kecil (mis. sisa angsuran)
        Line(String label, double qty, double rate, double amount, boolean showQty, String note) {
            this.label = label; this.qty = qty; this.rate = rate;
            this.amount = amount; this.showQty = showQty; this.note = note;
        }
    }

    public String depot, depotAddress;
    public String userName, jabatan, status;
    public String periodStart, periodEnd;
    public final List<Line> incomes = new ArrayList<>();
    public final List<Line> deductions = new ArrayList<>();
    public double totalBruto, totalPengurangan, totalDiterima;
    public int workingDays;
    public double totalHours, requiredHours, overtimeHours, shortfallHours;
    public boolean hoursMet;
    public String bankName, bankNo, bankHolder;

    public static PayslipData build(DatabaseHelper db, long userId, String userName,
                                    String start, String end) {
        PayslipData p = new PayslipData();
        SettingsDao settings = new SettingsDao(db);
        SalaryDao salaryDao = new SalaryDao(db);
        SalaryConfig cfg = salaryDao.getConfig(userId);
        TransactionDao trxDao = new TransactionDao(db);

        p.depot = settings.getDepotName();
        if (p.depot == null || p.depot.isEmpty()) p.depot = "DAMIU POS";
        p.depotAddress = settings.getDepotAddress();
        p.userName = userName;
        p.jabatan = nz(cfg.getJabatan(), "Staff");
        p.status = nz(cfg.getStatus(), "-");
        p.periodStart = start;
        p.periodEnd = end;
        p.bankName = nz(cfg.getBankName(), "");
        p.bankNo = nz(cfg.getBankNo(), "");
        p.bankHolder = nz(cfg.getBankHolder(), userName);

        AttendanceRecap.PeriodSummary ps = AttendanceRecap.computePeriodSummary(
                db, userId, start, end, settings.getDailyNormalHours());
        p.workingDays = ps.workingDays;
        p.totalHours = ps.totalHours;
        p.requiredHours = ps.requiredHours;
        // Lembur = akumulasi jam kerja di atas jam ideal/hari (lembur harian),
        // dibayar berapa pun status pemenuhan jam periode.
        p.overtimeHours = ps.dailyOvertimeHours;
        p.shortfallHours = Math.max(0, -ps.diffHours);  // kekurangan jam periode
        p.hoursMet = ps.diffHours >= -0.01;

        // ---- PENERIMAAN ----
        if (cfg.isGajiPokokEnabled() && cfg.getGajiPokok() > 0) {
            p.incomes.add(new Line("Gaji Pokok", 1, cfg.getGajiPokok(),
                    cfg.getGajiPokok(), false, null));
        }
        if (cfg.getTunjanganHarian() > 0) {
            double amt = ps.workingDays * cfg.getTunjanganHarian();
            p.incomes.add(new Line("Tunjangan Kehadiran Per Hari", ps.workingDays,
                    cfg.getTunjanganHarian(), amt, true, null));
        }
        // Bonus penjualan galon (kalau diaktifkan di Pengaturan Absensi).
        if (settings.isSalesBonusEnabled() && settings.getSalesBonusPerGalon() > 0) {
            int galon = (int) trxDao.getSummaryByDateRange(start, end)[1];
            double rate = settings.getSalesBonusPerGalon();
            p.incomes.add(new Line("Bonus Penjualan Galon", galon, rate,
                    galon * rate, true, null));
        }
        // Tunjangan / komponen bebas (admin) — hanya kind INCOME.
        List<SalaryItem> items = salaryDao.getItems(userId);
        for (SalaryItem it : items) {
            if (!it.isIncome()) continue;
            p.incomes.add(new Line(nz(it.getLabel(), "Tunjangan"), it.getQty(), it.getRate(),
                    it.getAmount(), it.getQty() != 1, null));
        }
        // Lembur (per jam) — SELALU dicetak kalau ada akumulasi lembur harian,
        // berapa pun rate-nya (rate 0 → tampil 0, sebagai pengingat set tarif).
        if (p.overtimeHours > 0.01) {
            double amt = p.overtimeHours * cfg.getLemburRate();
            p.incomes.add(new Line("Lembur (per jam)", round2(p.overtimeHours),
                    cfg.getLemburRate(), amt, true, null));
        }

        for (Line l : p.incomes) p.totalBruto += l.amount;

        // ---- PENGURANGAN (otomatis dari Angsuran; + potongan manual legacy) ----
        for (SalaryItem it : items) {
            if (it.isAngsuran()) {
                if (it.isArchived()) continue;          // lunas → tidak ditagihkan lagi
                double cicilan = it.getRate();          // cicilan/bulan = potongan
                double sisa = it.getQty();
                // Bounded (sisa>0): tagih min(cicilan, sisa) supaya cicilan terakhir
                // tidak melebihi sisa. Open-ended (sisa<=0): tagih penuh cicilan.
                double bill = sisa > 0 ? Math.min(cicilan, sisa) : cicilan;
                if (bill <= 0) continue;
                String note = sisa > 0 ? "Sisa hutang: Rp " + (long) sisa : null;
                p.deductions.add(new Line(nz(it.getLabel(), "Angsuran"), 1, bill,
                        bill, false, note));
            } else if (it.isDeduction()) {              // legacy potongan manual
                p.deductions.add(new Line(nz(it.getLabel(), "Potongan"), it.getQty(),
                        it.getRate(), it.getAmount(), it.getQty() != 1, null));
            }
        }
        for (Line l : p.deductions) p.totalPengurangan += l.amount;

        p.totalDiterima = p.totalBruto - p.totalPengurangan;
        return p;
    }

    private static String nz(String v, String def) {
        return v != null && !v.trim().isEmpty() ? v.trim() : def;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
