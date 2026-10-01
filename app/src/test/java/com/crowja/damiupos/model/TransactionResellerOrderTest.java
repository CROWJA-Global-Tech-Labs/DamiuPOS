package com.crowja.damiupos.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pesanan dari link pemesanan reseller di HP: penanda [ORDER RESELLER], flag per-order
 * delivery_proof_required, dan tujuan "Reseller: …". Menjaga supaya penanda reseller TIDAK pernah
 * terbaca sebagai Order Online (yang memaksa dialog "Kirim Struk WA" ke pelanggan).
 */
public class TransactionResellerOrderTest {

    private static Transaction note(String catatan) {
        Transaction t = new Transaction();
        t.setCatatan(catatan);
        return t;
    }

    @Test public void markerMirrorsServer() {
        assertEquals("[ORDER RESELLER]", Transaction.RESELLER_ORDER_MARKER);
    }

    @Test public void resellerMarkerIsResellerOrderButNotSelfOrder() {
        Transaction t = note("[ORDER RESELLER] Dibuat di Web oleh Reseller: Bu Ani · Catatan: taruh depan pintu");
        assertTrue(t.isResellerOrder());
        assertFalse(t.isSelfOrder());
    }

    @Test public void onlineOrderStaysSelfOrder() {
        Transaction t = note("[ORDER ONLINE] Catatan: cepat ya");
        assertTrue(t.isSelfOrder());
        assertFalse(t.isResellerOrder());
        assertFalse(t.requiresDeliveryProof());
    }

    @Test public void bothMarkersNeverTriggerTheOnlineOrderWaDialog() {
        Transaction t = note("[ORDER RESELLER] [ORDER ONLINE]");
        assertTrue(t.isResellerOrder());
        assertFalse(t.isSelfOrder());
    }

    @Test public void plainOrderNeedsNoProof() {
        assertFalse(note(null).requiresDeliveryProof());
        assertFalse(note("antar sore").requiresDeliveryProof());
        assertFalse(note(null).isResellerOrder());
    }

    @Test public void serverFlagAloneRequiresProof() {
        Transaction t = note("antar sore");
        t.setDeliveryProofRequired(true);
        assertTrue(t.isDeliveryProofRequired());
        assertTrue(t.requiresDeliveryProof());
        assertFalse(t.isResellerOrder());
    }

    @Test public void markerAloneRequiresProofBeforeFlagIsPulled() {
        Transaction t = note("[ORDER RESELLER]");
        assertFalse(t.isDeliveryProofRequired());
        assertTrue(t.requiresDeliveryProof());
    }

    @Test public void resellerDestinationIsDetectedByPrefix() {
        assertTrue(Transaction.isResellerDestName("Reseller: Kediaman"));
        assertTrue(Transaction.isResellerDestName("  reseller:Kediaman 2 "));
        assertFalse(Transaction.isResellerDestName("Kediaman"));
        assertFalse(Transaction.isResellerDestName("Rumah Reseller: lama"));
        assertFalse(Transaction.isResellerDestName("Resellerku"));
        assertFalse(Transaction.isResellerDestName(null));

        Transaction t = new Transaction();
        t.setDeliveryDestName("Reseller: Kediaman");
        assertTrue(t.isResellerDestination());
        t.setDeliveryDestName("Kantor");
        assertFalse(t.isResellerDestination());
    }

    @Test public void resellerDestinationLabelForCourier() {
        assertEquals("Kediaman", Transaction.resellerDestLocationName("Reseller: Kediaman"));
        assertEquals("Dikirim ke lokasi reseller: Kediaman", Transaction.resellerDestLabel("Reseller: Kediaman"));
        assertEquals("Dikirim ke lokasi reseller: Gudang 2", Transaction.resellerDestLabel(" Reseller:  Gudang 2 "));
        assertEquals("Dikirim ke lokasi reseller", Transaction.resellerDestLabel("Reseller:"));
        assertEquals("", Transaction.resellerDestLocationName("Kediaman"));
    }
}
