package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.DeliveryProofPolicy.Reason;

import org.junit.Test;

/** Gerbang foto bukti Selesai (DeliveryQueueActivity.doComplete) — keputusan murni. */
public class DeliveryProofPolicyTest {

    @Test public void ordinaryOrderOnlyOffersOptionalProof() {
        Reason r = DeliveryProofPolicy.reason(false, false, false);
        assertEquals(Reason.NONE, r);
        assertFalse(DeliveryProofPolicy.isRequired(r));
    }

    @Test public void resellerOrderRequiresProofEvenWhenBranchSettingIsOff() {
        Reason r = DeliveryProofPolicy.reason(false, false, true);
        assertEquals(Reason.RESELLER, r);
        assertTrue(DeliveryProofPolicy.isRequired(r));
    }

    @Test public void resellerExplainerWinsOverBranchSetting() {
        assertEquals(Reason.RESELLER, DeliveryProofPolicy.reason(true, false, true));
    }

    @Test public void cashBonExplainerWinsOverEverything() {
        assertEquals(Reason.CASH_BON, DeliveryProofPolicy.reason(true, true, true));
        assertEquals(Reason.CASH_BON, DeliveryProofPolicy.reason(false, true, false));
    }

    @Test public void branchSettingAloneStillRequiresProof() {
        Reason r = DeliveryProofPolicy.reason(true, false, false);
        assertEquals(Reason.BRANCH, r);
        assertTrue(DeliveryProofPolicy.isRequired(r));
    }

    @Test public void nullReasonIsNotRequired() {
        assertFalse(DeliveryProofPolicy.isRequired(null));
    }
}
