package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StaffPopupTest {
    @Test
    public void eventFollowsTheSessionChange() {
        assertEquals(StaffPopup.LOGIN, StaffPopup.eventFor(0, 5));
        assertEquals(StaffPopup.LOGIN, StaffPopup.eventFor(3, 5));   // ganti user
        assertEquals(StaffPopup.LOGOUT, StaffPopup.eventFor(5, 0));
        assertNull(StaffPopup.eventFor(5, 5));
        assertNull(StaffPopup.eventFor(0, 0));
    }

    @Test
    public void matchesEventAndDay() {
        int[] monWed = {1, 3};
        assertTrue(StaffPopup.matches(true, false, monWed, StaffPopup.LOGIN, 1));
        assertFalse(StaffPopup.matches(true, false, monWed, StaffPopup.LOGIN, 2));
        assertFalse(StaffPopup.matches(true, false, monWed, StaffPopup.LOGOUT, 1));
        assertTrue(StaffPopup.matches(false, true, null, StaffPopup.LOGOUT, 0));   // tanpa hari = setiap hari
        assertTrue(StaffPopup.matches(true, true, new int[0], StaffPopup.LOGIN, 6));
    }
}
