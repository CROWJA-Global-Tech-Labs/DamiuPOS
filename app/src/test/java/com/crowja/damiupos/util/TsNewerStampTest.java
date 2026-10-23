package com.crowja.damiupos.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Kunci LWW pull: suntingan lokal yang belum terdorong vs edited_at server (kasus "bagus melati 2", 23 Okt 2026). */
public class TsNewerStampTest {

    private TimeZone prev;

    @Before public void wib() {
        prev = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jakarta"));
    }

    @After public void restore() {
        TimeZone.setDefault(prev);
    }

    @Test public void localSelesaiBeatsOlderServerEditSentAsUtcIso() {
        // ✓ Selesai 16:09:16 WIB vs edit server 15:16:21 WIB (= 08:16:21Z). String: ' ' < 'T' → dulu kalah.
        assertTrue(Ts.newerStamp("2026-10-23 16:09:16.123000", "2026-10-23T08:16:21.909808Z"));
    }

    @Test public void newerServerEditStillWins() {
        assertFalse(Ts.newerStamp("2026-10-23 16:09:16.123000", "2026-10-23T09:15:00.000000Z"));   // 16:15 WIB
        assertFalse(Ts.newerStamp("2026-10-23 16:09:16.123000", "2026-10-23 16:15:00.000000"));
    }

    @Test public void subSecondOrderIsKept() {
        assertTrue(Ts.newerStamp("2026-10-23 16:09:16.900000", "2026-10-23 16:09:16.100000"));
        assertFalse(Ts.newerStamp("2026-10-23 16:09:16.100000", "2026-10-23 16:09:16.100000"));
        assertTrue(Ts.newerStamp("2026-10-23 16:09:16.900000", "2026-10-23T09:09:16.100000Z"));
    }

    @Test public void emptyServerStampMeansLocalWins() {
        assertTrue(Ts.newerStamp("2026-10-23 16:09:16.123000", ""));
        assertFalse(Ts.newerStamp("", "2026-10-23 16:09:16.123000"));
    }
}
