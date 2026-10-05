package com.crowja.damiupos.db;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 🧺 Checkout multi-lokasi di lapisan data HP:
 * <ul>
 *   <li>"Hutang sebelumnya" di pintu satu leg TIDAK memuat hutang segar leg saudaranya — tiap leg
 *       ditagih di pintunya sendiri (keputusan owner), dikenali lewat customer_debts.checkout_uuid
 *       saja (HP kurir sering hanya memegang satu leg);</li>
 *   <li>jumlah ORDER JUAL efektif menghitung checkout N leg sebagai satu order.</li>
 * </ul>
 */
public class CheckoutDebtAndOrdersTest {

    private static final String CO = "3f2b8c1e-9a4d-4e6f-8b2a-1c0d9e8f7a6b";
    private static final String OTHER_CO = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String LEG1 = "11111111-1111-4111-8111-111111111111";
    private static final String LEG2 = "22222222-2222-4222-8222-222222222222";
    private static final String OLD_TRX = "33333333-3333-4333-8333-333333333333";

    private static CustomerDebtDao.LedgerRow debt(double amt, String trx, String co) {
        return new CustomerDebtDao.LedgerRow(CustomerDebtDao.KIND_DEBT, amt, trx, co);
    }

    private static CustomerDebtDao.LedgerRow payment(double amt, String trx, String co) {
        return new CustomerDebtDao.LedgerRow(CustomerDebtDao.KIND_PAYMENT, amt, trx, co);
    }

    // ------------------------------------------------------------- hutang sebelumnya

    @Test public void plainOrderBehavesExactlyAsBefore() {
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(20000, OLD_TRX, null),
                debt(8000, LEG1, null),
                payment(5000, null, null));
        // Tanpa trx & checkout: Σdebt − Σpayment.
        assertEquals(23000, CustomerDebtDao.priorDebtOf(rows, null, null), 0.001);
        // Baris milik transaksi ini sendiri dikecualikan (idempoten).
        assertEquals(15000, CustomerDebtDao.priorDebtOf(rows, LEG1, null), 0.001);
    }

    @Test public void ownPaymentRowIsExcludedToo() {
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(20000, OLD_TRX, null),
                payment(5000, LEG1, null));   // pelunasan hutang lama saat Selesai leg ini
        // Menekan Selesai ulang tak boleh menumpuk: pembayaran milik leg INI tak dihitung lagi.
        assertEquals(20000, CustomerDebtDao.priorDebtOf(rows, LEG1, null), 0.001);
    }

    @Test public void siblingFreshHutangIsNotOldDebtAtThisDoor() {
        // Rumah Rp8.000 (leg 1) + Kedai Rp15.000 (leg 2), dua-duanya HUTANG; hutang lama Rp20.000.
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(20000, OLD_TRX, null),
                debt(8000, LEG1, CO),
                debt(15000, LEG2, CO));
        // Pintu Kedai: hutang Rumah BUKAN hutang lama.
        assertEquals(20000, CustomerDebtDao.priorDebtOf(rows, LEG2, CO), 0.001);
        // Pintu Rumah: sebaliknya.
        assertEquals(20000, CustomerDebtDao.priorDebtOf(rows, LEG1, CO), 0.001);
    }

    @Test public void siblingExclusionWorksWithoutTheSiblingTransactionLocally() {
        // HP kurir Kedai hanya memegang leg 2; baris hutang leg 1 (branch-wide) tetap dikenali
        // lewat checkout_uuid-nya — tak butuh uuid transaksi saudaranya sama sekali.
        List<CustomerDebtDao.LedgerRow> rows = Collections.singletonList(debt(8000, LEG1, CO));
        assertEquals(0, CustomerDebtDao.priorDebtOf(rows, LEG2, CO), 0.001);
    }

    @Test public void siblingPaymentOfOldDebtStillCounts() {
        // "Sekalian Lunasi Hutang" Rp5.000 dicatat pada leg utama → hutang lama di pintu leg 2 ikut turun.
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(20000, OLD_TRX, null),
                payment(5000, LEG1, null),
                debt(15000, LEG2, CO));
        assertEquals(15000, CustomerDebtDao.priorDebtOf(rows, LEG2, CO), 0.001);
    }

    @Test public void paymentRowStampedWithCheckoutIsNeverExcluded() {
        // Pertahanan bila server ikut menstempel checkout_uuid ke baris payment: tetap pelunasan.
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(20000, OLD_TRX, null),
                payment(5000, LEG1, CO));
        assertEquals(15000, CustomerDebtDao.priorDebtOf(rows, LEG2, CO), 0.001);
    }

    @Test public void anotherCheckoutsDebtIsRealOldDebt() {
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(12000, OLD_TRX, OTHER_CO),
                debt(8000, LEG1, CO));
        assertEquals(12000, CustomerDebtDao.priorDebtOf(rows, LEG2, CO), 0.001);
    }

    @Test public void blankCheckoutMeansNoSiblingExclusion() {
        List<CustomerDebtDao.LedgerRow> rows = Collections.singletonList(debt(8000, LEG1, CO));
        assertEquals(8000, CustomerDebtDao.priorDebtOf(rows, LEG2, null), 0.001);
        assertEquals(8000, CustomerDebtDao.priorDebtOf(rows, LEG2, "  "), 0.001);
    }

    @Test public void flooredOnceAtTheEnd() {
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(5000, OLD_TRX, null),
                payment(9000, null, null),
                debt(8000, LEG1, CO));
        assertEquals(0, CustomerDebtDao.priorDebtOf(rows, LEG2, CO), 0.001);
    }

    @Test public void roundsToCents() {
        List<CustomerDebtDao.LedgerRow> rows = Arrays.asList(
                debt(0.1, OLD_TRX, null),
                debt(0.2, OLD_TRX, null));
        assertEquals(0.3, CustomerDebtDao.priorDebtOf(rows, null, null), 0.0);
    }

    // ------------------------------------------------------------- jumlah order efektif

    @Test public void serverOrderCountWinsWhenHigher() {
        assertEquals(3, CustomerDao.effectiveJualOrders(1, 3, 9));
        assertEquals(2, CustomerDao.effectiveJualOrders(2, 1, 9));
    }

    @Test public void twoLegCheckoutIsOneOrder() {
        // Order pertama = checkout 2 lokasi (lokal 1 order; server agg_jual_orders juga 1) → order
        // berikutnya adalah "order kembali pertama" (effective == 1), walau srv_trx menghitung baris.
        assertEquals(1, CustomerDao.effectiveJualOrders(1, 1, 4));
    }

    @Test public void unknownServerCountFallsBackToRowCount() {
        // srv_jual_orders NULL (server lama / belum ditarik ulang) → pakai srv_trx supaya order di
        // perangkat lain tetap terlihat.
        assertEquals(4, CustomerDao.effectiveJualOrders(1, null, 4));
        assertEquals(1, CustomerDao.effectiveJualOrders(1, null, 0));
    }

    @Test public void neverNegative() {
        assertEquals(0, CustomerDao.effectiveJualOrders(-1, -2, -3));
        assertEquals(0, CustomerDao.effectiveJualOrders(0, null, 0));
    }
}
