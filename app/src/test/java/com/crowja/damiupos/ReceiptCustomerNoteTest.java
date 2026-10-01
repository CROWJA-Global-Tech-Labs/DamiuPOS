package com.crowja.damiupos;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Catatan yang layak dibaca pelanggan (WA struk / kartu antrean) — penanda internal dibuang. Cermin
 * App\Support\StrukWa::customerNote di web.
 */
public class ReceiptCustomerNoteTest {

    @Test public void resellerMarkerIsStripped() {
        assertEquals("taruh depan pintu", ReceiptActivity.customerNote("[ORDER RESELLER] taruh depan pintu"));
        assertEquals("", ReceiptActivity.customerNote("[ORDER RESELLER]"));
        assertEquals("taruh depan pintu", ReceiptActivity.customerNote("[order reseller] · taruh depan pintu"));
    }

    @Test public void onlineOrderMarkerStillStripped() {
        assertEquals("cepat ya", ReceiptActivity.customerNote("[ORDER ONLINE] cepat ya"));
    }

    @Test public void webAuditHeaderKeepsOnlyTheHumanTail() {
        assertEquals("taruh depan pintu", ReceiptActivity.customerNote(
                "[ORDER RESELLER] Dibuat di Web oleh Reseller: Bu Ani · Catatan: taruh depan pintu"));
    }
}
