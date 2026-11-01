package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.RemoteCompleteLogic.Offer;
import com.crowja.damiupos.util.RemoteCompleteLogic.Pending;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

/** "✓ Selesai" dari Perangkat Lain / Pesanan Terbuka (RemoteCompleteLogic). */
public class RemoteCompleteLogicTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    @Test
    public void offerHiddenWithoutEnrollmentDeliveryDeviceOrForViewer() {
        assertEquals(Offer.HIDDEN, RemoteCompleteLogic.offer(false, true, false, true, false, false));
        assertEquals(Offer.HIDDEN, RemoteCompleteLogic.offer(true, false, false, true, false, false));
        assertEquals(Offer.HIDDEN, RemoteCompleteLogic.offer(true, true, true, true, false, false));
        assertEquals(Offer.HIDDEN, RemoteCompleteLogic.offer(true, true, false, false, false, false));
        assertNull(RemoteCompleteLogic.menuLabel(Offer.HIDDEN));
    }

    @Test
    public void offerDisabledWhileDeliveredOrAwaitingApproval() {
        assertEquals(Offer.DISABLED_IN_PROGRESS, RemoteCompleteLogic.offer(true, true, false, true, true, true));
        assertEquals(Offer.DISABLED_AWAITING_APPROVAL, RemoteCompleteLogic.offer(true, true, false, true, false, true));
        assertEquals(Offer.ENABLED, RemoteCompleteLogic.offer(true, true, false, true, false, false));
        assertEquals("✓ Selesai", RemoteCompleteLogic.menuLabel(Offer.ENABLED));
        assertTrue(RemoteCompleteLogic.menuLabel(Offer.DISABLED_IN_PROGRESS).contains("sedang diantar"));
    }

    @Test
    public void confirmMessageNamesDeviceOrFallsBack() {
        String m = RemoteCompleteLogic.confirmMessage("Bu Sari", "HP RAFI");
        assertTrue(m.contains("\"Bu Sari\""));
        assertTrue(m.contains("(dari \"HP RAFI\")"));
        assertTrue(RemoteCompleteLogic.confirmMessage(null, "null").contains("(dari perangkat lain)"));
    }

    @Test
    public void addParseAndTtlExpiry() {
        String raw = RemoteCompleteLogic.add("", "a", NOW - 11 * MIN);
        raw = RemoteCompleteLogic.add(raw, "b", NOW - 11 * MIN + 1);
        // "a" sudah kedaluwarsa saat "c" ditambahkan → dibuang dari teks.
        raw = RemoteCompleteLogic.add(raw + ",x@" + (NOW - 20 * MIN), "c", NOW);
        Pending p = RemoteCompleteLogic.parse(raw, NOW);
        assertEquals(Collections.singletonList("c"), new java.util.ArrayList<>(p.alive.keySet()));
        assertTrue(p.expired.isEmpty());

        Pending q = RemoteCompleteLogic.parse("a@" + (NOW - 11 * MIN) + ",b@" + (NOW - 9 * MIN) + ",junk,@5,z@abc", NOW);
        assertEquals(Collections.singletonList("b"), new java.util.ArrayList<>(q.alive.keySet()));
        assertEquals(Collections.singletonList("a"), q.expired);
    }

    @Test
    public void reAddRefreshesStampAndRemoveDropsUuid() {
        String raw = RemoteCompleteLogic.add("", "a", NOW - 9 * MIN);
        raw = RemoteCompleteLogic.add(raw, "a", NOW);
        assertEquals("a@" + NOW, raw);
        raw = RemoteCompleteLogic.add(raw, "b", NOW);
        raw = RemoteCompleteLogic.remove(raw, new HashSet<>(Collections.singletonList("a")), NOW);
        assertEquals("b@" + NOW, raw);
    }

    @Test
    public void touchRefreshesOnlyStaleWaitingEntries() {
        String raw = "a@" + (NOW - 6 * MIN) + ",b@" + (NOW - 2 * MIN) + ",c@" + (NOW - 6 * MIN);
        String out = RemoteCompleteLogic.touch(raw, new HashSet<>(Arrays.asList("a", "b", "zz")), NOW);
        // a (lewat separuh TTL) diperbarui, b (masih segar) tetap, c (bukan antrean tiba) tetap.
        assertEquals("a@" + NOW + ",b@" + (NOW - 2 * MIN) + ",c@" + (NOW - 6 * MIN), out);
        // Tanpa yang basi → teks identik (tak menulis ulang tiap refresh).
        assertEquals(out, RemoteCompleteLogic.touch(out, new HashSet<>(Collections.singletonList("b")), NOW));
    }

    @Test
    public void canOpenNextOnlyWhenScreenFree() {
        assertTrue(RemoteCompleteLogic.canOpenNext(true, true, false, false, false, false));
        assertFalse(RemoteCompleteLogic.canOpenNext(false, true, false, false, false, false));
        assertFalse(RemoteCompleteLogic.canOpenNext(true, false, false, false, false, false));
        assertFalse(RemoteCompleteLogic.canOpenNext(true, true, true, false, false, false));
        assertFalse(RemoteCompleteLogic.canOpenNext(true, true, false, true, false, false));
        assertFalse(RemoteCompleteLogic.canOpenNext(true, true, false, false, true, false));
        assertFalse(RemoteCompleteLogic.canOpenNext(true, true, false, false, false, true));
    }

    @Test
    public void arrivedKeepsPendingOrder() {
        Pending p = RemoteCompleteLogic.parse("b@" + NOW + ",a@" + NOW + ",c@" + NOW, NOW);
        List<String> got = RemoteCompleteLogic.arrived(p, new HashSet<>(Arrays.asList("a", "b", "zz")));
        assertEquals(Arrays.asList("b", "a"), got);
        assertTrue(RemoteCompleteLogic.arrived(null, new HashSet<>()).isEmpty());
    }
}
