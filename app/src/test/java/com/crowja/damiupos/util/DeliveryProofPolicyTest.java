package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.DeliveryProofPolicy.Reason;

import org.junit.Test;

/** Gerbang foto bukti Selesai (DeliveryQueueActivity.doComplete) — keputusan murni.
 *  Argumen: (branchRequired, deviceRequired, cashBon, orderRequired). */
public class DeliveryProofPolicyTest {

    @Test public void ordinaryOrderOnlyOffersOptionalProof() {
        Reason r = DeliveryProofPolicy.reason(false, false, false, false);
        assertEquals(Reason.NONE, r);
        assertFalse(DeliveryProofPolicy.isRequired(r));
    }

    @Test public void resellerOrderRequiresProofEvenWhenBranchSettingIsOff() {
        Reason r = DeliveryProofPolicy.reason(false, false, false, true);
        assertEquals(Reason.RESELLER, r);
        assertTrue(DeliveryProofPolicy.isRequired(r));
    }

    @Test public void resellerExplainerWinsOverBranchAndDeviceSetting() {
        assertEquals(Reason.RESELLER, DeliveryProofPolicy.reason(true, true, false, true));
    }

    @Test public void cashBonExplainerWinsOverEverything() {
        assertEquals(Reason.CASH_BON, DeliveryProofPolicy.reason(true, true, true, true));
        assertEquals(Reason.CASH_BON, DeliveryProofPolicy.reason(false, false, true, false));
    }

    @Test public void branchSettingAloneStillRequiresProof() {
        Reason r = DeliveryProofPolicy.reason(true, false, false, false);
        assertEquals(Reason.BRANCH, r);
        assertTrue(DeliveryProofPolicy.isRequired(r));
    }

    @Test public void deviceSettingRequiresProofEvenWhenBranchSettingIsOff() {
        Reason r = DeliveryProofPolicy.reason(false, true, false, false);
        assertEquals(Reason.DEVICE, r);
        assertTrue(DeliveryProofPolicy.isRequired(r));
        assertEquals(Reason.DEVICE, DeliveryProofPolicy.reason(true, true, false, false));
    }

    @Test public void nullReasonIsNotRequired() {
        assertFalse(DeliveryProofPolicy.isRequired(null));
    }
}
