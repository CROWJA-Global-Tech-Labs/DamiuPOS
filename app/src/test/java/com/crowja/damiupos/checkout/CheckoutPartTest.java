package com.crowja.damiupos.checkout;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Aturan murni satu bagian checkout multi-lokasi di HP: tiap bagian dihargai persis seperti order
 * tunggal ke lokasinya (ongkir Per Botol / Borongan / Tanpa, max(1, galon), botol BELI per leg),
 * default ongkir dari tarif lokasi saja, gerbang wajib ongkir, pembagian rakus saldo/refund & galon
 * kembali, tanggal dasar (tanggal mundur dinaikkan ke sekarang), dan catatan per lokasi.
 */
public class CheckoutPartTest {

    private static final double EPS = 0.0001;

    private static Customer.Location loc(String name, double lat, double lng, Double ongkir, boolean wajib) {
        Customer.Location l = new Customer.Location(name, lat, lng, wajib);
        l.ongkir = ongkir;
        return l;
    }

    private static Product product(long id, String name, double harga) {
        Product p = new Product(name, harga, 0);
        p.setId(id);
        p.setUuid("uuid-" + id);
        return p;
    }

    private static CheckoutPart part(String ongkirType, double rate, int... qtyPricePairs) {
        CheckoutPart p = new CheckoutPart(loc("Rumah", -7.0, 110.0, null, false), 0);
        p.ongkirType = ongkirType;
        p.ongkirRate = rate;
        for (int i = 0; i + 1 < qtyPricePairs.length; i += 2) {
            long pid = i / 2 + 1;
            p.setQty(pid, qtyPricePairs[i]);
            p.setPrice(pid, qtyPricePairs[i + 1]);
        }
        return p;
    }

    // ------------------------------------------------------------------ total & ongkir

    @Test public void perGalonOngkirIsRateTimesGalon() {
        CheckoutPart p = part(Transaction.ONGKIR_PER_GALON, 2000, 1, 6000, 2, 5000);
        assertEquals(3, p.galon());
        assertEquals(16000, p.itemsSubtotal(), EPS);
        assertEquals(6000, p.ongkirTotal(), EPS);
        assertEquals(22000, p.total(false, 0), EPS);
        assertEquals(2000, p.storedOngkir(), EPS);   // kolom ongkir = TARIF, bukan total
    }

    @Test public void perGalonChargesAtLeastOneGalon() {
        CheckoutPart p = part(Transaction.ONGKIR_PER_GALON, 2000);
        assertEquals(0, p.galon());
        assertEquals(2000, p.ongkirTotal(), EPS);   // max(1, galon) — sama dengan gerbang order tunggal
    }

    @Test public void boronganIsFlatAndNoneIsZero() {
        CheckoutPart b = part(Transaction.ONGKIR_BORONGAN, 7000, 3, 6000);
        assertEquals(7000, b.ongkirTotal(), EPS);
        assertEquals(25000, b.total(false, 0), EPS);
        assertEquals(7000, b.storedOngkir(), EPS);

        CheckoutPart n = part(Transaction.ONGKIR_NONE, 9999, 3, 6000);
        assertEquals(0, n.ongkirTotal(), EPS);
        assertEquals(0, n.storedOngkir(), EPS);     // nominal sisa tak bocor ke kolom ongkir
        assertEquals(18000, n.total(false, 0), EPS);
    }

    @Test public void beliBottleIsChargedPerLegGalon() {
        CheckoutPart p = part(Transaction.ONGKIR_NONE, 0, 2, 6000);
        assertEquals(0, p.botolTotal(false, 35000), EPS);
        assertEquals(70000, p.botolTotal(true, 35000), EPS);
        assertEquals(82000, p.total(true, 35000), EPS);
    }

    @Test public void negativeInputsAreClamped() {
        CheckoutPart p = part(Transaction.ONGKIR_PER_GALON, -500, 1, 6000);
        p.setQty(9, -3);
        p.setPrice(9, -100);
        assertEquals(1, p.galon());
        assertEquals(0, p.ongkirTotal(), EPS);
        assertEquals(0, p.price(9), EPS);
        assertEquals(6000, p.total(true, -1), EPS);
    }

    // ------------------------------------------------------------------ default lokasi

    @Test public void defaultsFollowLocationRateOnly() {
        CheckoutPart rated = CheckoutPart.withDefaults(loc("Rumah", 1, 1, 2000.0, false), 0);
        assertEquals(Transaction.ONGKIR_PER_GALON, rated.ongkirType);
        assertEquals(2000, rated.ongkirRate, EPS);
        assertTrue(rated.wajib);

        CheckoutPart free = CheckoutPart.withDefaults(loc("Kedai", 1, 2, null, false), 1);
        assertEquals(Transaction.ONGKIR_NONE, free.ongkirType);
        assertEquals(0, free.ongkirRate, EPS);
        assertFalse(free.wajib);

        CheckoutPart nan = CheckoutPart.withDefaults(loc("Gudang", 1, 3, Double.NaN, false), 2);
        assertEquals(Transaction.ONGKIR_NONE, nan.ongkirType);
    }

    @Test public void legacyWajibFlagWithoutRateIsNotWajib() {
        // Aturan web/agen: wajib = tarif lokasi > 0 saja. Flag lama loc.wajibOngkir tanpa tarif tak
        // memicu gerbang 2-klik di HP (dulu memicu → HP & web berbeda vonis untuk lokasi yang sama).
        CheckoutPart p = CheckoutPart.withDefaults(loc("Kantor", 1, 1, null, true), 0);
        assertEquals(Transaction.ONGKIR_NONE, p.ongkirType);
        assertFalse(p.wajib);
        assertFalse(CheckoutPart.isWajib(loc("Kantor", 1, 1, null, true)));
        assertFalse(p.ongkirMissing());
        assertFalse(p.ongkirUnexpected());

        // Bertarif tetap wajib, dengan atau tanpa flag lama.
        assertTrue(CheckoutPart.isWajib(loc("Rumah", 1, 1, 2000.0, true)));
        assertTrue(CheckoutPart.isWajib(loc("Rumah", 1, 1, 2000.0, false)));
        assertFalse(CheckoutPart.isWajib(null));
    }

    @Test public void ongkirGateBothDirections() {
        CheckoutPart wajibOk = part(Transaction.ONGKIR_PER_GALON, 2000, 1, 6000);
        wajibOk.wajib = true;
        assertFalse(wajibOk.ongkirMissing());
        assertFalse(wajibOk.ongkirUnexpected());

        CheckoutPart wajibFree = part(Transaction.ONGKIR_NONE, 0, 1, 6000);
        wajibFree.wajib = true;
        assertTrue(wajibFree.ongkirMissing());

        CheckoutPart bebasCharged = part(Transaction.ONGKIR_BORONGAN, 5000, 1, 6000);
        bebasCharged.wajib = false;
        assertTrue(bebasCharged.ongkirUnexpected());
    }

    @Test public void splittableLocationsNeedCoordinatesAndUniqueNames() {
        List<Customer.Location> locs = Arrays.asList(
                loc("Rumah", -7.1, 110.1, null, false),
                loc("Tanpa Koordinat", 0, 0, 2000.0, false),
                loc("Kedai", -7.2, 110.2, 5000.0, false),
                loc(" rumah ", -7.3, 110.3, null, false),   // nama kembar → dilewati
                null);
        assertEquals(Arrays.asList(0, 2), CheckoutPart.splittableIndexes(locs));
        assertTrue(CheckoutPart.splittableIndexes(null).isEmpty());
    }

    @Test public void splittableLocationsCappedAtMaxLegs() {
        List<Customer.Location> locs = new ArrayList<>();
        for (int i = 0; i < CheckoutConstants.MAX_LEGS + 3; i++) locs.add(loc("L" + i, 1, i + 1, null, false));
        assertEquals(CheckoutConstants.MAX_LEGS, CheckoutPart.splittableIndexes(locs).size());
    }

    @Test public void blankNameFallsBackToDefaultLocationName() {
        assertEquals(Customer.DEFAULT_LOCATION_NAME, CheckoutPart.nameOf(loc("  ", 1, 1, null, false)));
        assertEquals("Kedai", CheckoutPart.nameOf(loc(" Kedai ", 1, 1, null, false)));
    }

    // ------------------------------------------------------------------ harga

    @Test public void unitPriceMirrorsResellerPricing() {
        // Harga khusus (lokasi/pelanggan) menang atas harga jual.
        assertEquals(5500, CheckoutPart.unitPrice(6000, 5500.0, false, null, 1000), EPS);
        assertEquals(6000, CheckoutPart.unitPrice(6000, null, false, null, 1000), EPS);
        // Komisi ke harga: override per produk, fallback rate global.
        assertEquals(6500, CheckoutPart.unitPrice(6000, null, true, 500.0, 1000), EPS);
        assertEquals(6500, CheckoutPart.unitPrice(5500, 5500.0, true, null, 1000), EPS);
    }

    @Test public void itemsInProductOrderSkipZeroQty() {
        Product a = product(1, "Galon RO", 6000);
        Product b = product(2, "Mineral", 7000);
        Product c = product(3, "Hexagonal", 9000);
        CheckoutPart p = new CheckoutPart(loc("Rumah", 1, 1, null, false), 0);
        p.setQty(3, 1); p.setPrice(3, 8500);
        p.setQty(1, 2); p.setPrice(1, 6000);
        p.setQty(2, 0); p.setPrice(2, 7000);
        List<TransactionItem> items = p.toItems(Arrays.asList(a, b, c));
        assertEquals(2, items.size());
        assertEquals("Galon RO", items.get(0).productName);
        assertEquals(2, items.get(0).jumlah);
        assertEquals("Hexagonal", items.get(1).productName);
        assertEquals(8500, items.get(1).hargaPerGalon, EPS);
        assertTrue(p.toItems(null).isEmpty());
    }

    @Test public void summaryLineForConfirmation() {
        Product a = product(1, "Galon", 6000);
        CheckoutPart p = part(Transaction.ONGKIR_PER_GALON, 2000, 1, 6000);
        assertEquals("1× Galon Rp 6.000 + ongkir Rp 2.000 = Rp 8.000",
                p.summary(Collections.singletonList(a), false, 0));
        assertEquals("1× Galon Rp 6.000 + ongkir Rp 2.000 + botol Rp 35.000 = Rp 43.000",
                p.summary(Collections.singletonList(a), true, 35000));
    }

    // ------------------------------------------------------------------ gabungan

    @Test public void activePartsAndGrandTotal() {
        CheckoutPart rumah = part(Transaction.ONGKIR_PER_GALON, 2000, 1, 6000);          // 8.000
        CheckoutPart kedai = part(Transaction.ONGKIR_PER_GALON, 5000, 2, 5000);          // 20.000
        CheckoutPart kosong = part(Transaction.ONGKIR_PER_GALON, 2000);                  // 0 galon → bukan leg
        List<CheckoutPart> parts = Arrays.asList(rumah, kosong, kedai);
        assertEquals(Arrays.asList(rumah, kedai), CheckoutPart.active(parts));
        assertEquals(28000, CheckoutPart.grandTotal(parts, false, 0), EPS);
        assertEquals(28000 + 3 * 35000, CheckoutPart.grandTotal(parts, true, 35000), EPS);
    }

    @Test public void greedyAllocationNeverExceedsLegTotals() {
        double[] totals = {8000, 15000};
        assertArrayEquals(new double[]{8000, 2000}, CheckoutPart.allocate(10000, totals), EPS);
        assertArrayEquals(new double[]{3000, 0}, CheckoutPart.allocate(3000, totals), EPS);
        assertArrayEquals(new double[]{8000, 15000}, CheckoutPart.allocate(99999, totals), EPS);
        assertArrayEquals(new double[]{0, 0}, CheckoutPart.allocate(-5, totals), EPS);
        assertEquals(0, CheckoutPart.allocate(100, null).length);

        // Refund dibagi SESUDAH saldo komisi: plafon = total leg − saldo leg.
        double[] saldo = CheckoutPart.allocate(10000, totals);
        double[] refund = CheckoutPart.allocate(20000, new double[]{totals[0] - saldo[0], totals[1] - saldo[1]});
        assertArrayEquals(new double[]{0, 13000}, refund, EPS);
    }

    @Test public void defaultKembaliCappedByHeldGalon() {
        assertArrayEquals(new int[]{1, 2}, CheckoutPart.defaultKembali(new int[]{1, 2}, 5));
        assertArrayEquals(new int[]{1, 1}, CheckoutPart.defaultKembali(new int[]{1, 2}, 2));
        assertArrayEquals(new int[]{0, 0}, CheckoutPart.defaultKembali(new int[]{1, 2}, 0));
        assertArrayEquals(new int[]{0, 3}, CheckoutPart.defaultKembali(new int[]{0, 4}, 3));
    }

    @Test public void baseTanggalRebasesBackdatesButKeepsTertundaSchedule() {
        String now = "2026-10-05 10:00:00";
        // Tanggal terpilih di belakang (form lama terbuka / dimundurkan) → sekarang.
        assertEquals(now, CheckoutPart.baseTanggal("2026-10-04 08:00:00", now, false, null));
        // Tanggal maju tetap dihormati.
        assertEquals("2026-10-05 12:00:00", CheckoutPart.baseTanggal("2026-10-05 12:00:00", now, false, null));
        // Tertunda → jadwal lanjut, bukan sekarang.
        assertEquals("2026-10-06 08:00:00",
                CheckoutPart.baseTanggal("2026-10-06 08:00:00", now, true, "2026-10-06 08:00:00"));
        // Masukan rusak.
        assertEquals(now, CheckoutPart.baseTanggal(null, now, false, null));
        assertEquals("2026-10-05 12:00:00", CheckoutPart.baseTanggal("2026-10-05 12:00:00", "", false, null));
        assertNull(CheckoutPart.baseTanggal(null, null, false, null));
        // Leg 2 = dasar + 1 detik.
        assertEquals("2026-10-05 10:00:01", CheckoutConstants.legTanggal(
                CheckoutPart.baseTanggal("2026-10-04 08:00:00", now, false, null), 2));
    }

    @Test public void legNoteJoinsWithStrukSeparator() {
        assertEquals("antar pagi · titip satpam", CheckoutPart.legNote(" antar pagi ", "titip satpam "));
        assertEquals("antar pagi", CheckoutPart.legNote("antar pagi", "  "));
        assertEquals("titip satpam", CheckoutPart.legNote(null, "titip satpam"));
        assertEquals("", CheckoutPart.legNote(null, null));
    }

    @Test public void copyIsDeep() {
        CheckoutPart p = part(Transaction.ONGKIR_PER_GALON, 2000, 1, 6000);
        p.kembali = 1;
        p.assignedDeviceUuid = "dev-b";
        p.note = "titip";
        CheckoutPart c = p.copy();
        assertNotSame(p.qtyByProduct, c.qtyByProduct);
        c.setQty(1, 5);
        c.kembali = 3;
        assertEquals(1, p.qty(1));
        assertEquals(1, p.kembali);
        assertEquals("dev-b", c.assignedDeviceUuid);
        assertEquals("titip", c.note);
        assertEquals(p.total(false, 0), part(Transaction.ONGKIR_PER_GALON, 2000, 1, 6000).total(false, 0), EPS);
    }

    // Gerbang rollout checkout_multi_enabled kini vonis server dari /api/me — lihat
    // sync/SyncSettingsCheckoutGateTest.
}
