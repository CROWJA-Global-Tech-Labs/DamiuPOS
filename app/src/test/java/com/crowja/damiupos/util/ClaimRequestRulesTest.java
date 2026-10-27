package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.ClaimRequestRules.Entry;

import org.junit.Test;

/** Kunci tombol Ambil + teks status "Ambil Alih butuh persetujuan" (ClaimRequestRules). */
public class ClaimRequestRulesTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60L * 1000L;

    private static Entry entry(String status, long expiresAt) {
        return new Entry("trx-1", 7L, "HP RAFI", status, expiresAt, NOW);
    }

    @Test
    public void pendingWithinWindowBlocksAndShowsWaitingLine() {
        Entry e = entry("PENDING", NOW + HOUR);
        assertTrue(ClaimRequestRules.blocksClaim(e, NOW));
        String line = ClaimRequestRules.cardLine(e, NOW);
        assertNotNull(line);
        assertTrue(line.startsWith("⏳"));
        assertTrue(line.contains("HP RAFI"));
        assertTrue(line.contains("menunggu persetujuan"));
        assertTrue(line.contains("s/d " + ClaimRequestRules.hm(NOW + HOUR)));
    }

    @Test
    public void rejectedStillBlocksUntilWindowEnds() {
        Entry e = entry("rejected", NOW + 10 * 60_000L);
        assertEquals("REJECTED", e.status);
        assertTrue(ClaimRequestRules.blocksClaim(e, NOW));
        assertTrue(ClaimRequestRules.cardLine(e, NOW).startsWith("❌"));
        assertFalse(ClaimRequestRules.blocksClaim(e, NOW + 10 * 60_000L));
        assertNull(ClaimRequestRules.cardLine(e, NOW + 10 * 60_000L));
    }

    @Test
    public void expiredOrFailedDoNotBlock() {
        assertFalse(ClaimRequestRules.blocksClaim(entry("EXPIRED", NOW + HOUR), NOW));
        assertFalse(ClaimRequestRules.blocksClaim(entry("FAILED", NOW + HOUR), NOW));
        assertNotNull(ClaimRequestRules.cardLine(entry("FAILED", NOW + HOUR), NOW));
    }

    @Test
    public void staleEntryDropsAtExpiry() {
        Entry e = entry("PENDING", NOW + HOUR);
        assertFalse(ClaimRequestRules.isStale(e, NOW + HOUR - 1));
        assertTrue(ClaimRequestRules.isStale(e, NOW + HOUR));
        assertFalse(ClaimRequestRules.blocksClaim(e, NOW + HOUR));
        assertFalse(ClaimRequestRules.blocksClaim(null, NOW));
    }

    @Test
    public void ownerFallbackWhenNameMissing() {
        Entry e = new Entry("trx-1", 0L, null, null, NOW + HOUR, NOW);
        assertEquals("PENDING", e.status);
        assertTrue(ClaimRequestRules.cardLine(e, NOW).contains("perangkat pemilik"));
    }

    @Test
    public void serverTimeIsJakartaLocal() {
        // 2026-10-28 10:00:00 WIB = 03:00:00 UTC
        long wib = ClaimRequestRules.parseServerTime("2026-10-28 10:00:00", -1L);
        long utc = ClaimRequestRules.parseServerTime("2026-10-28T03:00:00.000000Z", -1L);
        assertEquals(utc, wib);
        assertTrue(wib > 0);
        assertEquals(-1L, ClaimRequestRules.parseServerTime("", -1L));
        assertEquals(-1L, ClaimRequestRules.parseServerTime("null", -1L));
        assertEquals(-1L, ClaimRequestRules.parseServerTime("bukan tanggal", -1L));
    }

    @Test
    public void withStatusKeepsOwnerAndExpiryWhenServerOmitsThem() {
        Entry e = entry("PENDING", NOW + HOUR);
        Entry next = e.withStatus("APPROVED", "", 0L);
        assertEquals("APPROVED", next.status);
        assertEquals("HP RAFI", next.ownerName);
        assertEquals(NOW + HOUR, next.expiresAtMs);
        assertTrue(ClaimRequestRules.blocksClaim(next, NOW));
    }

    @Test
    public void timeLeftText() {
        assertEquals("± 42 mnt lagi", ClaimRequestRules.timeLeft(NOW + 42 * 60_000L + 5_000L, NOW));
        assertEquals("kurang dari 1 mnt lagi", ClaimRequestRules.timeLeft(NOW + 30_000L, NOW));
        assertEquals("sudah lewat", ClaimRequestRules.timeLeft(NOW, NOW));
    }
}
