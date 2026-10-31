package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeviceAdminRulesTest {

    @Test
    public void optPositiveIntTreatsEmptyAndZeroAsUnset() {
        assertNull(DeviceAdminRules.optPositiveInt(null));
        assertNull(DeviceAdminRules.optPositiveInt(""));
        assertNull(DeviceAdminRules.optPositiveInt("  "));
        assertNull(DeviceAdminRules.optPositiveInt("0"));
        assertEquals(Integer.valueOf(40), DeviceAdminRules.optPositiveInt(" 40 "));
        assertEquals(Integer.valueOf(1500), DeviceAdminRules.optPositiveInt("1.500"));
    }

    @Test
    public void ratioAcceptsCommaAndRoundsLikeServer() {
        assertEquals(0, DeviceAdminRules.ratio(""), 0);
        assertEquals(0, DeviceAdminRules.ratio("abc"), 0);
        assertEquals(0, DeviceAdminRules.ratio("-2"), 0);
        assertEquals(2.5, DeviceAdminRules.ratio("2,5"), 1e-9);
        assertEquals(1.33, DeviceAdminRules.ratio("1.3333"), 1e-9);
    }

    @Test
    public void pickupRadiusAcceptsWholeMeters10To500() {
        assertNull(DeviceAdminRules.pickupRadius(null));
        assertNull(DeviceAdminRules.pickupRadius(""));
        assertNull(DeviceAdminRules.pickupRadius("abc"));
        assertNull(DeviceAdminRules.pickupRadius("9"));
        assertNull(DeviceAdminRules.pickupRadius("501"));
        assertNull(DeviceAdminRules.pickupRadius("-50"));
        assertNull(DeviceAdminRules.pickupRadius("50.5"));
        assertEquals(Integer.valueOf(10), DeviceAdminRules.pickupRadius("10"));
        assertEquals(Integer.valueOf(50), DeviceAdminRules.pickupRadius(" 50 "));
        assertEquals(Integer.valueOf(500), DeviceAdminRules.pickupRadius("500"));
    }

    @Test
    public void receiptCodeIsUpperAlnumMaxThree() {
        assertEquals("", DeviceAdminRules.receiptCode(null));
        assertEquals("", DeviceAdminRules.receiptCode("  "));
        assertEquals("MT1", DeviceAdminRules.receiptCode("mt-1x"));
        assertEquals("AB", DeviceAdminRules.receiptCode(" ab "));
    }

    @Test
    public void marketingPricesDropEmptyAndStandard() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("p1", "");          // kosong → ikut standar
        in.put("p2", "5.000");     // sama dengan standar → tidak dikirim
        in.put("p3", "Rp 4.500");  // beda → dikirim
        in.put("p4", "7000");      // produk tanpa standar → dikirim
        Map<String, Double> std = new HashMap<>();
        std.put("p1", 5000d);
        std.put("p2", 5000d);
        std.put("p3", 5000d);
        Map<String, Long> out = DeviceAdminRules.marketingPrices(in, std);
        assertEquals(Arrays.asList("p3", "p4"), Arrays.asList(out.keySet().toArray(new String[0])));
        assertEquals(Long.valueOf(4500), out.get("p3"));
        assertEquals(Long.valueOf(7000), out.get("p4"));
    }

    @Test
    public void plainAndRupiahFormatting() {
        assertEquals("5000", DeviceAdminRules.plain(5000.0));
        assertEquals("2.5", DeviceAdminRules.plain(2.5));
        assertEquals("1.25", DeviceAdminRules.plain(1.25));
        assertEquals("Rp 15.000", DeviceAdminRules.rupiah(15000));
    }

    @Test
    public void lastSeenBuckets() {
        long now = 1_000_000_000_000L;
        assertEquals("belum pernah aktif", DeviceAdminRules.lastSeen(now, Long.MAX_VALUE));
        assertEquals("baru saja", DeviceAdminRules.lastSeen(now, now - 30_000));
        assertEquals("5 menit lalu", DeviceAdminRules.lastSeen(now, now - 5 * 60_000));
        assertEquals("3 jam lalu", DeviceAdminRules.lastSeen(now, now - 3 * 3_600_000L));
        assertEquals("2 hari lalu", DeviceAdminRules.lastSeen(now, now - 2 * 86_400_000L));
        assertEquals("baru saja", DeviceAdminRules.lastSeen(now, now + 60_000));   // jam HP sedikit tertinggal
    }

    @Test
    public void parseColor() {
        assertEquals(0xFF1565C0, DeviceAdminRules.parseColor("#1565C0", 0));
        assertEquals(0x801565C0, DeviceAdminRules.parseColor("#801565C0", 0));
        assertEquals(7, DeviceAdminRules.parseColor("biru", 7));
        assertEquals(7, DeviceAdminRules.parseColor(null, 7));
    }

    @Test
    public void extraWilayahExcludesSelfAndKeepsOrder() {
        List<String> c = DeviceAdminRules.extraWilayahCandidates("me", Arrays.asList("a", "me", "b", "a", ""));
        assertEquals(Arrays.asList("a", "b"), c);
        assertEquals(Collections.singletonList("b"),
                DeviceAdminRules.extraWilayahToSend(c, new HashSet<>(Arrays.asList("b", "gone"))));
    }
}
