package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class TakeRemainderTest {
    @Test public void totalLeft() {
        assertEquals(27, TakeRemainder.total(80, 53));
        assertEquals(0, TakeRemainder.total(80, 90));
        assertEquals(0, TakeRemainder.total(0, 5));
    }

    @Test public void perSlugLeft() {
        Map<String, Integer> targets = new LinkedHashMap<>();
        targets.put("BIO", 30);
        targets.put("MIN", 40);
        targets.put("OXY", 0);
        Map<String, Integer> taken = new HashMap<>();
        taken.put("BIO", 30);
        taken.put("MIN", 15);
        Map<String, Integer> left = TakeRemainder.perSlug(targets, taken);
        assertEquals(Integer.valueOf(0), left.get("BIO"));
        assertEquals(Integer.valueOf(25), left.get("MIN"));
        assertEquals(Integer.valueOf(0), left.get("OXY"));
        assertEquals(25, TakeRemainder.sum(left));
    }
}
