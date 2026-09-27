package com.crowja.damiupos.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** DB v99 one-time transactions-cursor lookback (spec §10): 72 h back, wall-clock only. */
public class SyncSettingsLookbackTest {

    @Test public void tieSafeCursorGoesBack72hWithoutUuid() {
        assertEquals("2026-09-24 08:02:01.000000",
                SyncSettings.trxLookbackCursor("2026-09-27 08:02:01.123456|0b9c6c3e-6f0e-4a55-9d4c-4b1e2f1a7c11"));
    }

    @Test public void legacyCursorAndMonthBoundary() {
        assertEquals("2026-02-28 23:30:00.000000", SyncSettings.trxLookbackCursor("2026-03-03 23:30:00.000000"));
    }

    @Test public void noDstOrTimezoneShift() {
        // Pure wall-clock arithmetic: the device zone must not matter.
        java.util.TimeZone saved = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
            assertEquals("2026-03-06 12:00:00.000000", SyncSettings.trxLookbackCursor("2026-03-09 12:00:00"));
        } finally {
            java.util.TimeZone.setDefault(saved);
        }
    }

    @Test public void emptyOrGarbageLeavesCursorAlone() {
        assertNull(SyncSettings.trxLookbackCursor(null));
        assertNull(SyncSettings.trxLookbackCursor(""));
        assertNull(SyncSettings.trxLookbackCursor("garbage"));
        assertNull(SyncSettings.trxLookbackCursor("2026-13-45 99:99:99"));
    }
}
