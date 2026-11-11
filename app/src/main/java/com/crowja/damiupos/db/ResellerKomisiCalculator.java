package com.crowja.damiupos.db;

import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mesin hitung komisi reseller dengan dukungan rate per jenis air minum.
 *
 * <p>Rate resolusi per item: override (reseller_rates) → fallback rate global
 * ({@link SettingsDao#getResellerKomisi()}). Transaksi botol-kosong
 * ([JUAL BOTOL KOSONG]) tidak menghasilkan komisi (bukan air minum).
 *
 * <p>Hasil termasuk breakdown per transaksi — dipakai untuk "Riwayat Komisi"
 * di detail reseller.
 */
public final class ResellerKomisiCalculator {

    /** Satu baris riwayat komisi (per transaksi JUAL). */
    public static class Entry {
        public long trxId;
        public String tanggal;
        public int totalGalon;
        public double komisi;
        /** Ringkasan item, mis. "3× Galon RO @1.500 + 2× Mineral @1.000". */
        public String detail;
        /** Affiliate yang memesan (pelanggan transaksi); sama dengan reseller = pembelian sendiri. */
        public long customerId;
        public String customerName = "";
        public String customerPhone = "";
        public boolean selfPurchase;
        public String receiptNo = "";
        public String payment = "";
        /** Rincian per baris: "2× FREZOXY @1.000 = Rp 2.000". */
        public final List<String> lines = new ArrayList<>();
    }

    /** Hasil perhitungan keseluruhan. */
    public static class Result {
        public double totalKomisi;
        public final List<Entry> entries = new ArrayList<>();
    }

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));

    private ResellerKomisiCalculator() {}

    public static Result hitung(DatabaseHelper dbHelper, Customer reseller) {
        Result result = new Result();
        if (reseller == null || !reseller.isReseller()) return result;

        SettingsDao settings = new SettingsDao(dbHelper);
        ResellerRateDao rateDao = new ResellerRateDao(dbHelper);
        TransactionDao trxDao = new TransactionDao(dbHelper);

        double globalRate = settings.getResellerKomisi();
        Map<Long, Double> rates = rateDao.getRates(reseller.getId());

        // Komisi mencakup transaksi yang di-afiliasikan ke reseller ini
        // (reseller_id) maupun pembelian langsung reseller (customer_id) tanpa
        // afiliasi eksplisit — lihat getJualForResellerSince.
        List<Transaction> trxs = trxDao.getJualForResellerSince(
                reseller.getId(), reseller.getResellerSince());

        CustomerDao customerDao = new CustomerDao(dbHelper);
        Map<Long, Customer> custCache = new java.util.HashMap<>();
        for (Transaction t : trxs) {
            // Lewati transaksi yang BUKAN sumber komisi:
            //  • botol kosong (bukan air minum)
            //  • pencairan/penggunaan saldo komisi (beli air dibayar dari komisi) →
            //    hindari komisi berputar / circular (komisi tidak menghasilkan komisi lagi).
            String cat = t.getCatatan();
            if (cat != null && (cat.contains("[JUAL BOTOL KOSONG]")
                    || cat.contains("[PENCAIRAN KOMISI]")
                    || cat.contains("[SALDO KOMISI"))) {
                continue;
            }

            Entry e = new Entry();
            e.trxId = t.getId();
            e.tanggal = t.getTanggal();
            e.customerId = t.getCustomerId();
            e.selfPurchase = t.getCustomerId() == reseller.getId();
            Customer buyer = custCache.get(t.getCustomerId());
            if (buyer == null && t.getCustomerId() > 0) {
                buyer = customerDao.getById(t.getCustomerId());
                custCache.put(t.getCustomerId(), buyer);
            }
            if (buyer != null) {
                e.customerName = buyer.getName() != null ? buyer.getName() : "";
                e.customerPhone = buyer.getPhone() != null ? buyer.getPhone() : "";
            } else if (t.getCustomerName() != null) {
                e.customerName = t.getCustomerName();
            }
            e.receiptNo = t.getReceiptNo() != null ? t.getReceiptNo() : "";
            e.payment = t.getPaymentMethodLabel() != null ? t.getPaymentMethodLabel() : "";

            List<TransactionItem> items = t.getItems();
            if (items != null && !items.isEmpty()) {
                StringBuilder detail = new StringBuilder();
                for (TransactionItem it : items) {
                    double rate = rates.containsKey(it.productId)
                            ? rates.get(it.productId) : globalRate;
                    e.komisi += it.jumlah * rate;
                    e.totalGalon += it.jumlah;
                    e.lines.add(it.jumlah + "× " + (it.productName != null ? it.productName : "Air")
                            + " @" + NF.format(Math.round(rate)) + " = Rp " + NF.format(Math.round(it.jumlah * rate)));
                    if (detail.length() > 0) detail.append(" + ");
                    detail.append(it.jumlah).append("× ")
                            .append(it.productName != null ? it.productName : "Air")
                            .append(" @").append(NF.format(Math.round(rate)));
                }
                e.detail = detail.toString();
            } else {
                // Legacy single-product row
                double rate = rates.containsKey(t.getProductId())
                        ? rates.get(t.getProductId()) : globalRate;
                e.komisi = t.getJumlahGalon() * rate;
                e.totalGalon = t.getJumlahGalon();
                e.detail = t.getJumlahGalon() + "× galon @" + NF.format(Math.round(rate));
                e.lines.add(e.detail + " = Rp " + NF.format(Math.round(e.komisi)));
            }

            if (e.komisi > 0 || e.totalGalon > 0) {
                result.totalKomisi += e.komisi;
                result.entries.add(e);
            }
        }
        return result;
    }
}
