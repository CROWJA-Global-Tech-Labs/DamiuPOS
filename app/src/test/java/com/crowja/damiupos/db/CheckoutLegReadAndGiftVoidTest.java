package com.crowja.damiupos.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.Transaction;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 🧺 Checkout multi-lokasi di TransactionDao:
 * <ul>
 *   <li>leg dari getByCheckoutUuid membawa kolom pengirimannya (tujuan "Kirim ke", status, perangkat
 *       penangan) — struk gabungan "Bayar per lokasi" & "Simpan & Selesaikan" bergantung padanya;</li>
 *   <li>void leg tak menghilangkan gift: gift custom pindah ke leg saudara ber-seq terkecil yang masih
 *       antre, selain itu kembali pending.</li>
 * </ul>
 */
public class CheckoutLegReadAndGiftVoidTest {

    private static final String LEG1 = "11111111-1111-4111-8111-111111111111";
    private static final String LEG2 = "22222222-2222-4222-8222-222222222222";
    private static final String LEG3 = "33333333-3333-4333-8333-333333333333";

    /** Baris "cursor" palsu: kolom absen → null / 0, persis getStr/getDouble di DAO. */
    private static TransactionDao.ColumnReader row(final Map<String, Object> cols) {
        return new TransactionDao.ColumnReader() {
            @Override public String str(String col) {
                Object v = cols.get(col);
                return v != null ? String.valueOf(v) : null;
            }
            @Override public double dbl(String col) {
                Object v = cols.get(col);
                return v instanceof Number ? ((Number) v).doubleValue() : 0;
            }
        };
    }

    // ------------------------------------------------------------- kolom pengiriman leg

    @Test public void legCarriesItsDestinationStatusAndDevices() {
        Map<String, Object> cols = new HashMap<>();
        cols.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_PENDING);
        cols.put(DatabaseHelper.COL_DELIVERY_DEST_NAME, "Kedai");
        cols.put(DatabaseHelper.COL_DELIVERY_DEST_LAT, -6.2);
        cols.put(DatabaseHelper.COL_DELIVERY_DEST_LNG, 106.8);
        cols.put(DatabaseHelper.COL_DELIVERY_QUEUED_AT, "2026-10-05 09:00:01");
        cols.put(DatabaseHelper.COL_ASSIGNED_DEVICE_UUID, "dev-b");
        cols.put(DatabaseHelper.COL_DELIVERY_DEVICE_UUID, "dev-b");
        Transaction t = new Transaction();
        TransactionDao.mapLegDelivery(t, row(cols));

        assertEquals("Kedai", t.getDeliveryDestName());
        assertEquals(-6.2, t.getDeliveryDestLat(), 1e-9);
        assertEquals(106.8, t.getDeliveryDestLng(), 1e-9);
        assertEquals(Transaction.DELIVERY_PENDING, t.getDeliveryStatus());
        assertEquals("2026-10-05 09:00:01", t.getDeliveryQueuedAt());
        assertEquals("dev-b", t.getAssignedDeviceUuid());
        assertEquals("dev-b", t.getDeliveryDeviceUuid());
        assertTrue(t.isHandledByOtherDevice("dev-a"));
    }

    @Test public void tertundaLegKeepsItsResumeSchedule() {
        Map<String, Object> cols = new HashMap<>();
        cols.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_TERTUNDA);
        cols.put(DatabaseHelper.COL_DELIVERY_TERTUNDA_RESUME_AT, "2026-10-07 08:00:00");
        Transaction t = new Transaction();
        TransactionDao.mapLegDelivery(t, row(cols));
        assertEquals(Transaction.DELIVERY_TERTUNDA, t.getDeliveryStatus());
        assertEquals("2026-10-07 08:00:00", t.getDeliveryTertundaResumeAt());
    }

    @Test public void absentColumnsStayEmpty() {
        Transaction t = new Transaction();
        TransactionDao.mapLegDelivery(t, row(new HashMap<String, Object>()));
        assertNull(t.getDeliveryStatus());
        assertNull(t.getDeliveryDestName());
        assertEquals(0, t.getDeliveryDestLat(), 0);
        assertNull(t.getDeliveryDeviceUuid());
        assertFalse(t.isHandledByOtherDevice("dev-a"));
    }

    // ------------------------------------------------------------- gift saat leg di-void

    private static TransactionDao.GiftHeirCandidate leg(int seq, String uuid, String status) {
        return new TransactionDao.GiftHeirCandidate(seq, uuid, status);
    }

    @Test public void lowestSeqQueuedSiblingInheritsTheGift() {
        String heir = TransactionDao.pickGiftHeir(Arrays.asList(
                leg(3, LEG3, Transaction.DELIVERY_PENDING),
                leg(2, LEG2, Transaction.DELIVERY_TERTUNDA)), LEG1);
        assertEquals(LEG2, heir);
    }

    @Test public void deliveredOrUnqueuedSiblingIsNoHeir() {
        // Leg yang sudah Selesai (atau self-pick tanpa status) tak akan menuntaskan gift lagi.
        assertNull(TransactionDao.pickGiftHeir(Arrays.asList(
                leg(2, LEG2, Transaction.DELIVERY_DONE),
                leg(3, LEG3, null)), LEG1));
        assertEquals(LEG3, TransactionDao.pickGiftHeir(Arrays.asList(
                leg(2, LEG2, Transaction.DELIVERY_DONE),
                leg(3, LEG3, Transaction.DELIVERY_PENDING)), LEG1));
    }

    @Test public void twinCopyOfTheVoidedLegIsNoHeir() {
        assertNull(TransactionDao.pickGiftHeir(Collections.singletonList(
                leg(1, LEG1, Transaction.DELIVERY_PENDING)), LEG1));
        assertNull(TransactionDao.pickGiftHeir(Collections.singletonList(
                leg(2, "  ", Transaction.DELIVERY_PENDING)), LEG1));
    }

    @Test public void noLocalSiblingsMeansNoHeir() {
        // HP kurir sering hanya memegang leg ini sendiri.
        assertNull(TransactionDao.pickGiftHeir(Collections.<TransactionDao.GiftHeirCandidate>emptyList(), LEG1));
        assertNull(TransactionDao.pickGiftHeir(null, LEG1));
    }

    @Test public void customGiftMovesToHeirProductGiftReturnsToPending() {
        assertEquals(LEG2, TransactionDao.giftAttachmentAfterVoid(false, LEG2));
        // Wujud gift produk = baris Rp 0 di leg yang batal → tawarkan lagi di JUAL berikutnya.
        assertEquals("", TransactionDao.giftAttachmentAfterVoid(true, LEG2));
    }

    @Test public void plainOrderVoidReturnsGiftToPending() {
        // "" (bukan null) supaya ikut ter-push; server menjadikannya NULL.
        assertEquals("", TransactionDao.giftAttachmentAfterVoid(false, null));
        assertEquals("", TransactionDao.giftAttachmentAfterVoid(false, " "));
    }
}
