package com.crowja.damiupos.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.Transaction;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * getById memetakan delivery_status (+ jadwal lanjut tertunda). Dulu tak terpetakan → gift PRODUK yang
 * dipilih di struk / Transaksi Baru untuk order yang MASIH ANTRE langsung "diberikan" (bukan dilekatkan
 * sampai Selesai), tanggal kampanye order tertunda tak pernah dipakai, dan "Simpan &amp; Selesaikan"
 * order tunggal tak pernah menandai Selesai.
 */
public class GetByIdDeliveryStatusTest {

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

    @Test public void mapsStatusAndResumeAt() {
        Map<String, Object> cols = new HashMap<>();
        cols.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_TERTUNDA);
        cols.put(DatabaseHelper.COL_DELIVERY_TERTUNDA_RESUME_AT, "2026-10-10 08:00:00");
        Transaction t = new Transaction();
        TransactionDao.mapByIdDelivery(t, row(cols));
        assertEquals(Transaction.DELIVERY_TERTUNDA, t.getDeliveryStatus());
        assertEquals("2026-10-10 08:00:00", t.getDeliveryTertundaResumeAt());
    }

    @Test public void walkInSaleStaysNull() {
        Transaction t = new Transaction();
        TransactionDao.mapByIdDelivery(t, row(new HashMap<>()));
        assertNull(t.getDeliveryStatus());
        assertNull(t.getDeliveryTertundaResumeAt());
    }

    @Test public void productGiftAttachesOnlyWhileQueued() {
        assertTrue(TransactionDao.isQueuedForDelivery(Transaction.DELIVERY_PENDING));
        assertTrue(TransactionDao.isQueuedForDelivery(Transaction.DELIVERY_TERTUNDA));
        assertFalse(TransactionDao.isQueuedForDelivery(Transaction.DELIVERY_DONE));
        assertFalse(TransactionDao.isQueuedForDelivery(null));   // jual langsung (bukan delivery)
        assertFalse(TransactionDao.isQueuedForDelivery(""));
    }

    @Test public void queuedOrderReadThroughGetByIdMappingAttachesGift() {
        // Rantai yang dipakai ReceiptActivity: baris getById → status → keputusan "queued".
        Map<String, Object> cols = new HashMap<>();
        cols.put(DatabaseHelper.COL_DELIVERY_STATUS, Transaction.DELIVERY_PENDING);
        Transaction t = new Transaction();
        TransactionDao.mapByIdDelivery(t, row(cols));
        assertTrue(TransactionDao.isQueuedForDelivery(t.getDeliveryStatus()));
    }
}
