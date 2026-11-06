package com.crowja.damiupos.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pesanan lunas tak ditagih di pintu: terkonfirmasi, atau QRIS/transfer bertanda di catatan. */
public class TransactionLunasTest {

    private static Transaction trx(String method, String note) {
        Transaction t = new Transaction();
        t.setPaymentMethod(method);
        t.setCatatan(note);
        return t;
    }

    @Test public void qrisOrTransferWithNoteMarkerIsLunas() {
        assertTrue(trx("QRIS", "Catatan: ✅ LUNAS (QRIS) · galon keran").isMarkedLunas());
        assertTrue(trx("TRANSFER", "✅ LUNAS (Transfer BCA)").isMarkedLunas());
    }

    @Test public void markerOnCashOrDebtIsNotLunas() {
        assertFalse(trx("TUNAI", "✅ LUNAS (QRIS)").isMarkedLunas());
        assertFalse(trx("HUTANG", "✅ LUNAS (QRIS)").isMarkedLunas());
    }

    @Test public void qrisWithoutMarkerIsNotLunas() {
        assertFalse(trx("QRIS", "galon keran").isMarkedLunas());
        assertFalse(trx("QRIS", "belum LUNAS").isMarkedLunas());
        assertFalse(trx("QRIS", null).isMarkedLunas());
    }

    @Test public void confirmedIsLunasWithoutMarker() {
        Transaction t = trx("TUNAI", "galon keran");
        t.setPaymentConfirmedAt("2026-11-06 10:00:00");
        assertTrue(t.isMarkedLunas());
    }

    @Test public void labelShowsLunasForNoteMarker() {
        assertEquals("QRIS · LUNAS", trx("QRIS", "✅ LUNAS (QRIS)").getPaymentMethodLabel());
        assertEquals("QRIS", trx("QRIS", "galon keran").getPaymentMethodLabel());
    }
}
