package com.crowja.damiupos.wa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class WaStatusVideoRulesTest {

    /** Batas Bridge 1 MB (server max_bytes ±770 KB): doc → detik = min(30, floor(max_bytes*0.9*8/450000)), long_side 480. */
    private static WaStatusVideoRules small() {
        return new WaStatusVideoRules(30, 10, 788_480, 480, 0);
    }

    @Test
    public void segmentFollowsDocFormula() {
        assertEquals(12, small().segmentSeconds);   // floor(788480*0.9*8/450000) = 12
        assertEquals(480, small().longSide);
    }

    @Test
    public void bigLimitUses30sAnd720() {
        WaStatusVideoRules r = new WaStatusVideoRules(30, 10, 16L * 1048576, 720, 0);
        assertEquals(30, r.segmentSeconds);
        assertEquals(720, r.longSide);
    }

    @Test
    public void serverSegmentSecondsWins() {
        assertEquals(20, new WaStatusVideoRules(30, 10, 788_480, 480, 20).segmentSeconds);
        assertEquals(30, new WaStatusVideoRules(30, 10, 788_480, 480, 99).segmentSeconds);   // tetap ≤ max_seconds
    }

    @Test
    public void fallbacksWhenFieldsMissing() {
        WaStatusVideoRules r = WaStatusVideoRules.from(null);
        assertEquals(30, r.maxSeconds);
        assertEquals(10, r.maxParts);
        assertEquals(WaStatusVideoRules.FALLBACK_MAX_BYTES, r.maxBytes);
        assertEquals(480, r.longSide);
        assertTrue(r.segmentSeconds >= 1 && r.segmentSeconds <= 30);
    }

    @Test
    public void planSplitsInOrder() {
        List<long[]> p = small().plan(30_000);
        assertEquals(3, p.size());
        assertEquals(0, p.get(0)[0]);
        assertEquals(12_000, p.get(0)[1]);
        assertEquals(24_000, p.get(2)[0]);
        assertEquals(30_000, p.get(2)[1]);
    }

    @Test
    public void planMergesTinyTail() {
        List<long[]> p = small().plan(24_400);
        assertEquals(2, p.size());
        assertEquals(24_400, p.get(1)[1]);
    }

    @Test
    public void planCapsAtMaxParts() {
        WaStatusVideoRules r = small();
        List<long[]> p = r.plan(10 * 60_000);
        assertEquals(10, p.size());
        assertEquals(120_000, p.get(9)[1]);
        assertTrue(r.tooLong(10 * 60_000));
    }

    @Test
    public void bitrateFitsBudget() {
        WaStatusVideoRules r = small();
        int v = r.videoBitrate(12_000);
        long bytes = (long) (v + WaStatusVideoRules.AUDIO_BPS) * 12 / 8;
        assertTrue(bytes <= r.maxBytes);
    }

    @Test
    public void partCheck() {
        WaStatusVideoRules r = small();
        assertNull(r.partCheck(1, 2, 700_000, 12_000));
        String bad = r.partCheck(2, 3, 900_000, 12_000);
        assertNotNull(bad);
        assertTrue(bad.startsWith("Potongan 2/3"));
        assertNotNull(r.partCheck(1, 1, 500_000, 40_000));
    }
}
