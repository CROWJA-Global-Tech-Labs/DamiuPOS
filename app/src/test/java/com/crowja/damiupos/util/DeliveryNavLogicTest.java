package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.DeliveryNavLogic.PayKind;
import com.crowja.damiupos.util.DeliveryNavLogic.StopState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Navigasi pengiriman + jendela melayang (NavigationPipActivity) — keputusan murni. */
public class DeliveryNavLogicTest {

    private static final StopState P = StopState.PENDING;
    private static final StopState D = StopState.DONE;
    private static final StopState G = StopState.GONE;

    private static List<double[]> pts(int n) {
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(new double[]{-6.9 - i / 1000.0, 110.4 + i / 1000.0});
        return out;
    }

    // ---------------------------------------------------------------- URL rute

    @Test public void singleStopRouteHasDestinationOnlyAndStartsNavigation() {
        String url = DeliveryNavLogic.buildDirUrl(Collections.singletonList(new double[]{-6.9932, 110.4203}), true);
        assertEquals("https://www.google.com/maps/dir/?api=1&travelmode=driving&dir_action=navigate"
                + "&destination=-6.9932,110.4203", url);
    }

    @Test public void lastStopIsDestinationOthersAreEncodedWaypointsInRitOrder() {
        List<double[]> stops = Arrays.asList(new double[]{-6.1, 110.1}, new double[]{-6.2, 110.2}, new double[]{-6.3, 110.3});
        String url = DeliveryNavLogic.buildDirUrl(stops, true);
        assertTrue(url.contains("&destination=-6.3,110.3"));
        assertTrue(url.endsWith("&waypoints=-6.1%2C110.1%7C-6.2%2C110.2"));
    }

    @Test public void previewModeOmitsDirAction() {
        String url = DeliveryNavLogic.buildDirUrl(Collections.singletonList(new double[]{-6.5, 110.5}), false);
        assertFalse(url.contains("dir_action"));
    }

    @Test public void routeIsCappedAtTenStops() {
        String url = DeliveryNavLogic.buildDirUrl(pts(13), true);
        // destination = titik ke-10 (bukan ke-13), 9 waypoint.
        assertTrue(url, url.contains("&destination=-6.91,110.41&"));
        String wp = url.substring(url.indexOf("&waypoints=") + "&waypoints=".length());
        assertEquals(9, wp.split("%7C").length);
        assertFalse(url.contains("110.411"));
    }

    @Test public void emptyRouteIsNull() {
        assertNull(DeliveryNavLogic.buildDirUrl(new ArrayList<double[]>(), true));
        assertNull(DeliveryNavLogic.buildDirUrl(null, true));
    }

    @Test public void capRouteKeepsOrderAndDoesNotTouchShortLists() {
        List<Integer> in = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), DeliveryNavLogic.capRoute(in));
        assertEquals(Arrays.asList(3, 1), DeliveryNavLogic.capRoute(Arrays.asList(3, 1)));
        assertTrue(DeliveryNavLogic.capRoute(null).isEmpty());
    }

    @Test public void coordinatesNeverUseScientificNotation() {
        // Pontianak, tepat di khatulistiwa: Double.toString(0.0005) = "5.0E-4".
        assertEquals("0.0005,109.3333", DeliveryNavLogic.coord(0.0005, 109.3333));
        assertEquals("0", DeliveryNavLogic.coordNum(0.0));
        assertEquals("0", DeliveryNavLogic.coordNum(-0.0));
        assertEquals("110", DeliveryNavLogic.coordNum(110.0));
        assertEquals("-6.1234568", DeliveryNavLogic.coordNum(-6.123456789));
    }

    @Test public void singleNavigationUris() {
        assertEquals("google.navigation:q=-6.9932,110.4203", DeliveryNavLogic.navigationUri(-6.9932, 110.4203));
        assertEquals("https://www.google.com/maps?q=-6.9932,110.4203", DeliveryNavLogic.webPinUrl(-6.9932, 110.4203));
    }

    @Test public void hasGeoTreatsZeroZeroAsMissing() {
        assertFalse(DeliveryNavLogic.hasGeo(0, 0));
        assertTrue(DeliveryNavLogic.hasGeo(0, 109.3));
        assertTrue(DeliveryNavLogic.hasGeo(-6.9, 110.4));
    }

    // ---------------------------------------------------------------- perhentian aktif

    @Test public void activeStopIsFirstPendingInRitOrder() {
        List<StopState> s = Arrays.asList(D, G, P, P);
        assertEquals(2, DeliveryNavLogic.activeIndex(s));
        assertEquals(3, DeliveryNavLogic.nextPendingIndex(s, 2));
        assertEquals(-1, DeliveryNavLogic.nextPendingIndex(s, 3));
    }

    @Test public void outOfOrderCompletionStillShowsEarliestPendingStop() {
        // Kurir menyelesaikan perhentian ke-2 lebih dulu: aktif tetap yang pertama, kemajuan 2/3.
        List<StopState> s = Arrays.asList(P, D, P);
        assertEquals(0, DeliveryNavLogic.activeIndex(s));
        assertEquals(2, DeliveryNavLogic.progressNumber(s));
        assertEquals(3, DeliveryNavLogic.trackedCount(s));
    }

    @Test public void progressIgnoresStopsThatLeftTheRit() {
        List<StopState> s = Arrays.asList(D, G, P, P);
        assertEquals(3, DeliveryNavLogic.trackedCount(s));
        assertEquals(2, DeliveryNavLogic.progressNumber(s));
        assertFalse(DeliveryNavLogic.isRitFinished(s));
    }

    @Test public void allDoneMeansRitFinished() {
        List<StopState> s = Arrays.asList(D, D, G);
        assertEquals(-1, DeliveryNavLogic.activeIndex(s));
        assertEquals(0, DeliveryNavLogic.progressNumber(s));
        assertTrue(DeliveryNavLogic.isRitFinished(s));
    }

    @Test public void ritStoppedWithoutDeliveriesIsNotFinished() {
        List<StopState> s = Arrays.asList(G, G);
        assertEquals(-1, DeliveryNavLogic.activeIndex(s));
        assertFalse(DeliveryNavLogic.isRitFinished(s));
        assertFalse(DeliveryNavLogic.isRitFinished(new ArrayList<StopState>()));
        assertEquals(-1, DeliveryNavLogic.activeIndex(null));
    }

    // ---------------------------------------------------------------- uang & tagihan

    @Test public void rupiahUsesThousandDotsAndRounds() {
        assertEquals("Rp 0", DeliveryNavLogic.rupiah(0));
        assertEquals("Rp 500", DeliveryNavLogic.rupiah(500));
        assertEquals("Rp 45.000", DeliveryNavLogic.rupiah(45000));
        assertEquals("Rp 1.234.567", DeliveryNavLogic.rupiah(1234567));
        assertEquals("Rp 12.001", DeliveryNavLogic.rupiah(12000.6));
        assertEquals("-Rp 5.000", DeliveryNavLogic.rupiah(-5000));
        assertEquals("Rp 0", DeliveryNavLogic.rupiah(Double.NaN));
    }

    @Test public void payKindPrecedence() {
        assertEquals(PayKind.COLLECT, DeliveryNavLogic.payKind(45000, "TUNAI", false, false));
        assertEquals(PayKind.COLLECT, DeliveryNavLogic.payKind(45000, "QRIS", false, false));
        assertEquals(PayKind.COLLECT, DeliveryNavLogic.payKind(45000, null, false, false));
        assertEquals(PayKind.PAID, DeliveryNavLogic.payKind(45000, "TRANSFER", true, false));
        // Hutang yang sudah dilunasi → tak ada yang ditagih lagi.
        assertEquals(PayKind.PAID, DeliveryNavLogic.payKind(45000, "HUTANG", true, false));
        assertEquals(PayKind.HUTANG, DeliveryNavLogic.payKind(45000, "hutang", false, false));
        assertEquals(PayKind.CASH_BON, DeliveryNavLogic.payKind(45000, "TUNAI", false, true));
        assertEquals(PayKind.FREE, DeliveryNavLogic.payKind(0, "TUNAI", false, false));
    }

    @Test public void payLinesForFloatingWindow() {
        assertEquals("Rp 45.000 · Tunai", DeliveryNavLogic.payLine(PayKind.COLLECT, 45000, "Tunai"));
        assertEquals("Rp 45.000", DeliveryNavLogic.payLine(PayKind.COLLECT, 45000, ""));
        assertEquals("Lunas · Rp 45.000", DeliveryNavLogic.payLine(PayKind.PAID, 45000, "QRIS · LUNAS"));
        assertEquals("Hutang (tidak ditagih)", DeliveryNavLogic.payLine(PayKind.HUTANG, 45000, "Hutang"));
        assertEquals("Cash Bon (tidak ditagih)", DeliveryNavLogic.payLine(PayKind.CASH_BON, 45000, "Tunai"));
        assertEquals("Tanpa tagihan", DeliveryNavLogic.payLine(PayKind.FREE, 0, "Tunai"));
    }

    @Test public void payHints() {
        assertEquals("Tagih Rp 45.000 dari pelanggan.", DeliveryNavLogic.payHint(PayKind.COLLECT, 45000));
        assertTrue(DeliveryNavLogic.payHint(PayKind.HUTANG, 45000).startsWith("Rp 45.000 dicatat hutang"));
        assertEquals("", DeliveryNavLogic.payHint(PayKind.FREE, 0));
    }

    // ---------------------------------------------------------------- uang di pintu (refund & hutang lama)

    @Test public void refundSaldoIsNotCollectedAgain() {
        // Rp 45.000 dengan saldo refund Rp 20.000 → antrean "Total setelah refund: Rp 25.000".
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(45000, 20000, 0, true);
        assertEquals(25000, DeliveryNavLogic.dueAtDoor(PayKind.COLLECT, m), 0.001);
        assertEquals("Rp 25.000 · Tunai", DeliveryNavLogic.payLine(PayKind.COLLECT, m, "Tunai"));
        assertEquals("Tagih Rp 25.000 dari pelanggan.\nTotal pesanan Rp 45.000 − saldo refund Rp 20.000",
                DeliveryNavLogic.payHint(PayKind.COLLECT, m));
        assertEquals(PayKind.COLLECT, DeliveryNavLogic.displayKind(PayKind.COLLECT, m));
    }

    @Test public void oldDebtAtThisDoorIsAddedToTheCollectAmount() {
        // Antrean: "Hutang sebelumnya: Rp 15.000 / Total harus diterima: Rp 60.000".
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(45000, 0, 15000, true);
        assertEquals("Rp 60.000 · Tunai", DeliveryNavLogic.payLine(PayKind.COLLECT, m, "Tunai"));
        assertEquals("Tagih Rp 60.000 dari pelanggan.\n+ hutang lama Rp 15.000",
                DeliveryNavLogic.payHint(PayKind.COLLECT, m));
    }

    @Test public void refundAndOldDebtTogether() {
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(45000, 20000, 15000, true);
        assertEquals(40000, DeliveryNavLogic.dueAtDoor(PayKind.COLLECT, m), 0.001);
        assertEquals("Tagih Rp 40.000 dari pelanggan.\nTotal pesanan Rp 45.000 − saldo refund Rp 20.000"
                + "\n+ hutang lama Rp 15.000", DeliveryNavLogic.payHint(PayKind.COLLECT, m));
    }

    @Test public void oldDebtCollectedAtAnotherCheckoutDoorIsNotAdded() {
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(45000, 0, 15000, false);
        assertEquals(0, m.debtDue(), 0.001);
        assertEquals("Rp 45.000 · Tunai", DeliveryNavLogic.payLine(PayKind.COLLECT, m, "Tunai"));
        assertEquals("Tagih Rp 45.000 dari pelanggan.", DeliveryNavLogic.payHint(PayKind.COLLECT, m));
    }

    @Test public void orderFullyCoveredByRefundShowsAsPaid() {
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(45000, 50000, 0, true);
        assertEquals(0, DeliveryNavLogic.dueAtDoor(PayKind.COLLECT, m), 0.001);
        assertEquals("Lunas (saldo refund)", DeliveryNavLogic.payLine(PayKind.COLLECT, m, "Tunai"));
        assertEquals("Sudah tertutup saldo refund Rp 45.000 — jangan ditagih.",
                DeliveryNavLogic.payHint(PayKind.COLLECT, m));
        assertEquals(PayKind.PAID, DeliveryNavLogic.displayKind(PayKind.COLLECT, m));
    }

    @Test public void paidOrFreeOrderStillCollectsOldDebtHere() {
        DeliveryNavLogic.DoorMoney paid = new DeliveryNavLogic.DoorMoney(45000, 0, 15000, true);
        assertEquals("Rp 15.000 · hutang lama", DeliveryNavLogic.payLine(PayKind.PAID, paid, "QRIS · LUNAS"));
        assertEquals("Pesanan sudah dibayar. Tagih hutang lama Rp 15.000.", DeliveryNavLogic.payHint(PayKind.PAID, paid));
        assertEquals(PayKind.COLLECT, DeliveryNavLogic.displayKind(PayKind.PAID, paid));

        DeliveryNavLogic.DoorMoney free = new DeliveryNavLogic.DoorMoney(0, 0, 15000, true);
        assertEquals("Rp 15.000 · hutang lama", DeliveryNavLogic.payLine(PayKind.FREE, free, "Tunai"));
        assertEquals("Tagih hutang lama Rp 15.000.", DeliveryNavLogic.payHint(PayKind.FREE, free));

        // Pintu lain leg checkout → tetap lunas.
        DeliveryNavLogic.DoorMoney elsewhere = new DeliveryNavLogic.DoorMoney(45000, 0, 15000, false);
        assertEquals("Lunas · Rp 45.000", DeliveryNavLogic.payLine(PayKind.PAID, elsewhere, ""));
        assertEquals(PayKind.PAID, DeliveryNavLogic.displayKind(PayKind.PAID, elsewhere));
    }

    @Test public void hutangOrderCollectsNothingEvenWithOldDebt() {
        DeliveryNavLogic.DoorMoney m = new DeliveryNavLogic.DoorMoney(45000, 20000, 15000, true);
        assertEquals(0, DeliveryNavLogic.dueAtDoor(PayKind.HUTANG, m), 0.001);
        assertEquals("Hutang (tidak ditagih)", DeliveryNavLogic.payLine(PayKind.HUTANG, m, "Hutang"));
        assertEquals("Rp 25.000 dicatat hutang pelanggan — jangan ditagih sekarang.",
                DeliveryNavLogic.payHint(PayKind.HUTANG, m));
        assertEquals(PayKind.HUTANG, DeliveryNavLogic.displayKind(PayKind.HUTANG, m));
    }

    @Test public void plainMoneyMatchesTheTotalOnlyOverloads() {
        DeliveryNavLogic.DoorMoney m = DeliveryNavLogic.DoorMoney.plain(45000);
        for (PayKind k : PayKind.values()) {
            assertEquals(DeliveryNavLogic.payLine(k, 45000, "Tunai"), DeliveryNavLogic.payLine(k, m, "Tunai"));
            assertEquals(DeliveryNavLogic.payHint(k, 45000), DeliveryNavLogic.payHint(k, m));
        }
        // Angka rusak dari DB tak boleh jadi "NaN".
        DeliveryNavLogic.DoorMoney bad = new DeliveryNavLogic.DoorMoney(Double.NaN, Double.NaN, -5, true);
        assertEquals(0, bad.total, 0.001);
        assertEquals(0, bad.refundUsed, 0.001);
        assertEquals(0, bad.oldDebt, 0.001);
    }

    // ---------------------------------------------------------------- produk

    @Test public void productSummaryMergesSameLabelInFirstSeenOrder() {
        String s = DeliveryNavLogic.productSummary(
                Arrays.asList("MIN", "RO", "MIN", " ", "HEX"),
                Arrays.asList(2, 1, 1, 5, 0));
        assertEquals("MIN ×3 · RO ×1", s);
        assertEquals("", DeliveryNavLogic.productSummary(null, null));
    }
}
