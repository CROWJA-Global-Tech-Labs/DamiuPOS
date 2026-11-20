package com.crowja.damiupos.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QuietHoursTest {
    private static int at(int h, int m) {
        return h * 60 + m;
    }

    @Test
    public void emptyOrBrokenSettingIsNeverQuiet() {
        assertFalse(QuietHours.isQuietAt(null, at(23, 0)));
        assertFalse(QuietHours.isQuietAt("", at(23, 0)));
        assertFalse(QuietHours.isQuietAt("not json", at(23, 0)));
        assertFalse(QuietHours.isQuietAt("[{\"start\":\"25:00\",\"end\":\"05:00\"}]", at(1, 0)));
        assertFalse(QuietHours.isQuietAt("[{\"start\":\"10:00\",\"end\":\"10:00\"}]", at(10, 0)));
    }

    @Test
    public void rangeWithinOneDayIncludesStartExcludesEnd() {
        String j = "[{\"start\":\"12:00\",\"end\":\"13:00\"}]";
        assertFalse(QuietHours.isQuietAt(j, at(11, 59)));
        assertTrue(QuietHours.isQuietAt(j, at(12, 0)));
        assertTrue(QuietHours.isQuietAt(j, at(12, 59)));
        assertFalse(QuietHours.isQuietAt(j, at(13, 0)));
    }

    @Test
    public void rangePastMidnightAndMultipleRanges() {
        String j = "[{\"start\":\"21:00\",\"end\":\"05:00\"},{\"start\":\"12:00\",\"end\":\"13:00\"}]";
        assertTrue(QuietHours.isQuietAt(j, at(23, 30)));
        assertTrue(QuietHours.isQuietAt(j, at(0, 0)));
        assertTrue(QuietHours.isQuietAt(j, at(4, 59)));
        assertFalse(QuietHours.isQuietAt(j, at(5, 0)));
        assertTrue(QuietHours.isQuietAt(j, at(12, 30)));
        assertFalse(QuietHours.isQuietAt(j, at(15, 0)));
    }
}
