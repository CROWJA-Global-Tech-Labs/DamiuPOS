package com.crowja.damiupos.wa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals(640, r.longSide);
        assertEquals(1920, r.passLongSide);
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

    @Test
    public void outputKeepsTheAspectRatioInsteadOfASquareFrame() {
        WaStatusVideoRules r = new WaStatusVideoRules(60, 10, 16L * 1048576, 1280, 0);
        assertArrayEquals(new int[]{720, 1280}, r.outputSize(1080, 1920, 0));      // potret 9:16 → 720×1280, bukan 1280×1280
        assertArrayEquals(new int[]{1280, 720}, r.outputSize(1920, 1080, 0));      // lanskap
        assertArrayEquals(new int[]{720, 1280}, r.outputSize(1920, 1080, 90));     // rekaman HP berotasi = potret
        assertArrayEquals(new int[]{1072, 1072}, r.outputSize(1080, 1080, 0));     // persegi tetap persegi, tak diperbesar
        assertArrayEquals(new int[]{352, 640}, r.outputSize(360, 640, 0));         // kecil: tak diperbesar, kelipatan 16
    }

    @Test
    public void fullHdMp4WithinTheLimitsIsUsedAsIs() {
        WaStatusVideoRules r = new WaStatusVideoRules(60, 10, 16L * 1048576, 1280, 0);
        assertTrue(r.usableAsIs("video/mp4", 12_273_555, 45_845, 1080, 1920));
        assertFalse(r.usableAsIs("video/mp4", 12_273_555, 45_845, 2160, 3840));    // 4K → kompres
        assertFalse(r.usableAsIs("video/quicktime", 1_000_000, 10_000, 1080, 1920));
        assertFalse(r.usableAsIs("video/mp4", 17L * 1048576, 45_845, 1080, 1920)); // kebesaran
        assertFalse(r.usableAsIs("video/mp4", 1_000_000, 75_000, 1080, 1920));     // > satu potongan
    }
}
