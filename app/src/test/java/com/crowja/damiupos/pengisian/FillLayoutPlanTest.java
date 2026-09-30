package com.crowja.damiupos.pengisian;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FillLayoutPlanTest {

    @Test
    public void threeProductsOnATypicalPhoneAllFitInlineWithoutScrolling() {
        // ±480 dp tersisa untuk kartu di HP 360x780: 3 kartu × (152 + jarak 8)
        FillLayoutPlan.Result r = FillLayoutPlan.choose(480f, 1, 2, false);
        assertTrue(r.inlineAll);
        assertFalse(r.scrolls);
        assertEquals(FillLayoutPlan.TIER_MEDIUM, r.tier);
        assertEquals(152f, r.cardDp, 0.01f);
    }

    @Test
    public void aSingleProductNeverStretchesBeyondTheCap() {
        FillLayoutPlan.Result r = FillLayoutPlan.choose(480f, 1, 0, false);
        assertTrue(r.inlineAll);
        assertEquals(FillLayoutPlan.TIER_FULL, r.tier);
        assertEquals(FillLayoutPlan.CARD_MAX_DP, r.cardDp, 0.01f);
    }

    @Test
    public void tallScreensGetTheFullTierForThreeProducts() {
        FillLayoutPlan.Result r = FillLayoutPlan.choose(660f, 2, 1, false);
        assertTrue(r.inlineAll);
        assertEquals(FillLayoutPlan.TIER_FULL, r.tier);
        assertFalse(r.scrolls);
    }

    @Test
    public void idleProductsFoldAwayWhenEverythingCannotFit() {
        // 5 produk × (118+8) = 630 > 480 → lipat yang tak aktif; 2 aktif + pelipat muat.
        FillLayoutPlan.Result r = FillLayoutPlan.choose(480f, 2, 3, false);
        assertFalse(r.inlineAll);
        assertFalse(r.scrolls);
        assertEquals(FillLayoutPlan.TIER_FULL, r.tier);
        assertTrue(r.cardDp >= FillLayoutPlan.FULL_MIN_DP);
    }

    @Test
    public void tooManyActiveProductsFallBackToScrollingCompactCards() {
        FillLayoutPlan.Result r = FillLayoutPlan.choose(300f, 4, 0, false);
        assertTrue(r.scrolls);
        assertEquals(FillLayoutPlan.TIER_COMPACT, r.tier);
        assertEquals(0f, r.cardDp, 0.01f);
    }

    @Test
    public void expandedOthersAreCountedInTheFit() {
        // Dibuka manual: 2 aktif + 3 lain = 5 kartu, tak muat di 480 → gulir.
        FillLayoutPlan.Result r = FillLayoutPlan.choose(480f, 2, 3, true);
        assertFalse(r.inlineAll);
        assertTrue(r.scrolls);
    }

    @Test
    public void unmeasuredAreaFallsBackToNaturalCompactCards() {
        FillLayoutPlan.Result r = FillLayoutPlan.choose(0f, 1, 2, false);
        assertEquals(FillLayoutPlan.TIER_COMPACT, r.tier);
        assertEquals(0f, r.cardDp, 0.01f);
    }

    @Test
    public void noProductsIsHarmless() {
        FillLayoutPlan.Result r = FillLayoutPlan.choose(480f, 0, 0, false);
        assertTrue(r.inlineAll);
        assertFalse(r.scrolls);
    }

    @Test
    public void largeSystemFontsPickDenserTiersSoContentIsNotClipped() {
        // 3 kartu di 480 dp: kartu 152 dp. Dengan font 1.3x ≈ setara 117 dp → tak muat inline → lipat.
        FillLayoutPlan.Result normal = FillLayoutPlan.choose(480f, 1, 2, false, 1f);
        FillLayoutPlan.Result big = FillLayoutPlan.choose(480f, 1, 2, false, 1.3f);
        assertTrue(normal.inlineAll);
        assertFalse(big.inlineAll);
        assertEquals(FillLayoutPlan.TIER_COMPACT, FillLayoutPlan.tierFor(152f, 1.3f));
        assertEquals(FillLayoutPlan.TIER_MEDIUM, FillLayoutPlan.tierFor(152f, 1f));
    }

    @Test
    public void tierThresholds() {
        assertEquals(FillLayoutPlan.TIER_FULL, FillLayoutPlan.tierFor(200f));
        assertEquals(FillLayoutPlan.TIER_MEDIUM, FillLayoutPlan.tierFor(199.9f));
        assertEquals(FillLayoutPlan.TIER_MEDIUM, FillLayoutPlan.tierFor(152f));
        assertEquals(FillLayoutPlan.TIER_COMPACT, FillLayoutPlan.tierFor(151.9f));
    }
}
