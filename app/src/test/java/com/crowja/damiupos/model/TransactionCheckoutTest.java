package com.crowja.damiupos.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Leg checkout multi-lokasi di model: trio harus utuh & sah, seq 1 = leg utama. */
public class TransactionCheckoutTest {

    private static final String UUID = "3f2b8c1e-9a4d-4e6f-8b2a-1c0d9e8f7a6b";

    private static Transaction leg(String uuid, int seq, int size) {
        Transaction t = new Transaction();
        t.setType(Transaction.TYPE_JUAL);
        t.setCheckoutUuid(uuid);
        t.setCheckoutSeq(seq);
        t.setCheckoutSize(size);
        return t;
    }

    @Test public void plainOrderIsNotALeg() {
        Transaction t = new Transaction();
        assertFalse(t.isCheckoutLeg());
        assertFalse(t.isCheckoutPrimary());
    }

    @Test public void primaryAndSecondaryLegs() {
        assertTrue(leg(UUID, 1, 2).isCheckoutLeg());
        assertTrue(leg(UUID, 1, 2).isCheckoutPrimary());
        assertTrue(leg(UUID, 2, 2).isCheckoutLeg());
        assertFalse(leg(UUID, 2, 2).isCheckoutPrimary());
    }

    @Test public void halfTrioIsAPlainOrder() {
        // uuid tanpa seq/size (mis. kolom NULL → 0 dari cursor) tak boleh dianggap leg.
        assertFalse(leg(UUID, 0, 0).isCheckoutLeg());
        assertFalse(leg(null, 1, 2).isCheckoutLeg());
        assertFalse(leg(UUID, 1, 1).isCheckoutLeg());
        assertFalse(leg(UUID, 1, 1).isCheckoutPrimary());
    }

    // ------------------------------------------------------------- leg milik perangkat lain

    @Test public void unassignedLegBelongsToThisDevice() {
        assertFalse(leg(UUID, 1, 2).isHandledByOtherDevice("dev-a"));
    }

    @Test public void assignedOrRoutedElsewhereIsOtherDevice() {
        Transaction assigned = leg(UUID, 2, 2);
        assigned.setAssignedDeviceUuid("dev-b");
        assertTrue(assigned.isHandledByOtherDevice("dev-a"));

        Transaction routed = leg(UUID, 2, 2);
        routed.setDeliveryDeviceUuid("dev-b");
        assertTrue(routed.isHandledByOtherDevice("dev-a"));
    }

    @Test public void assignedOrRoutedToThisDeviceIsMine() {
        Transaction t = leg(UUID, 1, 2);
        t.setAssignedDeviceUuid(" DEV-A ");
        t.setDeliveryDeviceUuid("dev-a");
        assertFalse(t.isHandledByOtherDevice("dev-a"));
        t.setDeliveryDeviceUuid("  ");
        assertFalse(t.isHandledByOtherDevice("dev-a"));
    }

    @Test public void unprovisionedDeviceTreatsAnyAssignmentAsOther() {
        Transaction t = leg(UUID, 1, 2);
        assertFalse(t.isHandledByOtherDevice(null));
        t.setAssignedDeviceUuid("dev-b");
        assertTrue(t.isHandledByOtherDevice(null));
        assertTrue(t.isHandledByOtherDevice(""));
    }
}
