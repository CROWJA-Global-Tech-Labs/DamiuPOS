package com.crowja.damiupos;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionGuardTest {
    @Test
    public void shiftFromYesterdayEndsAtMidnight() {
        assertEquals(SessionGuard.MIDNIGHT, SessionGuard.decide("IN", "2026-11-27", "2026-11-28"));
        assertEquals(SessionGuard.MIDNIGHT, SessionGuard.decide("BREAK", "2026-11-27", "2026-11-28"));
    }

    @Test
    public void shiftFromTodayKeepsRunning() {
        assertEquals(SessionGuard.NONE, SessionGuard.decide("IN", "2026-11-28", "2026-11-28"));
        assertEquals(SessionGuard.NONE, SessionGuard.decide(null, null, "2026-11-28"));
    }

    @Test
    public void outAlwaysClosesTheSession() {
        assertEquals(SessionGuard.REMOTE_OUT, SessionGuard.decide("OUT", "2026-11-27", "2026-11-28"));
        assertEquals(SessionGuard.REMOTE_OUT, SessionGuard.decide("OUT", "2026-11-28", "2026-11-28"));
    }
}
