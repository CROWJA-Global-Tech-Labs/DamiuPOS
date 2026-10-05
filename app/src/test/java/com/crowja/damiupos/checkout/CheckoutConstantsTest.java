package com.crowja.damiupos.checkout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Aturan murni checkout multi-lokasi di HP: validasi trio (sama dengan sanitasi server saat insert),
 * tanggal leg +1 detik per seq (pasangan JUAL↔KEMBALI butuh tanggal leg yang beda), dan badge k/N.
 */
public class CheckoutConstantsTest {

    private static final String UUID = "3f2b8c1e-9a4d-4e6f-8b2a-1c0d9e8f7a6b";

    @Test public void constantsMirrorServer() {
        assertEquals("checkout_uuid", CheckoutConstants.EXTRA_CHECKOUT_UUID);
        assertEquals(2, CheckoutConstants.MIN_LEGS);
        assertEquals(10, CheckoutConstants.MAX_LEGS);
        assertEquals(1000L, CheckoutConstants.LEG_TANGGAL_STEP_MS);
    }

    @Test public void validTrio() {
        assertTrue(CheckoutConstants.isValidTrio(UUID, 1, 2));
        assertTrue(CheckoutConstants.isValidTrio(UUID, 2, 2));
        assertTrue(CheckoutConstants.isValidTrio(UUID, 10, 10));
        assertTrue(CheckoutConstants.isValidTrio("  " + UUID + " ", 1, 3));
        assertTrue(CheckoutConstants.isValidTrio(UUID.toUpperCase(), 1, 2));
    }

    @Test public void invalidTrioIsAPlainOrder() {
        assertFalse(CheckoutConstants.isValidTrio(null, 1, 2));
        assertFalse(CheckoutConstants.isValidTrio("", 1, 2));
        assertFalse(CheckoutConstants.isValidTrio("bukan-uuid", 1, 2));
        assertFalse(CheckoutConstants.isValidTrio(UUID.replace("-", ""), 1, 2));
        // size 1 = order biasa; > 10 melewati batas server.
        assertFalse(CheckoutConstants.isValidTrio(UUID, 1, 1));
        assertFalse(CheckoutConstants.isValidTrio(UUID, 1, 11));
        // seq di luar 1..size.
        assertFalse(CheckoutConstants.isValidTrio(UUID, 0, 2));
        assertFalse(CheckoutConstants.isValidTrio(UUID, 3, 2));
        // Kolom NULL terbaca 0 dari cursor.
        assertFalse(CheckoutConstants.isValidTrio(UUID, 0, 0));
    }

    @Test public void newUuidIsValidAndUnique() {
        String a = CheckoutConstants.newCheckoutUuid();
        String b = CheckoutConstants.newCheckoutUuid();
        assertTrue(CheckoutConstants.isValidTrio(a, 1, 2));
        assertNotEquals(a, b);
    }

    @Test public void legTanggalAddsOneSecondPerSeq() {
        String base = "2026-10-05 09:15:30";
        assertEquals(base, CheckoutConstants.legTanggal(base, 1));
        assertEquals("2026-10-05 09:15:31", CheckoutConstants.legTanggal(base, 2));
        assertEquals("2026-10-05 09:15:39", CheckoutConstants.legTanggal(base, 10));
    }

    @Test public void legTanggalRollsOverMinuteHourAndDay() {
        assertEquals("2026-10-05 10:00:00", CheckoutConstants.legTanggal("2026-10-05 09:59:59", 2));
        assertEquals("2026-10-06 00:00:01", CheckoutConstants.legTanggal("2026-10-05 23:59:59", 3));
        assertEquals("2027-01-01 00:00:00", CheckoutConstants.legTanggal("2026-12-31 23:59:59", 2));
    }

    @Test public void legTanggalIgnoresFractionalTail() {
        assertEquals("2026-10-05 09:15:31", CheckoutConstants.legTanggal("2026-10-05 09:15:30.123", 2));
    }

    @Test public void legTanggalRejectsUnparseableBase() {
        assertNull(CheckoutConstants.legTanggal(null, 1));
        assertNull(CheckoutConstants.legTanggal("", 1));
        assertNull(CheckoutConstants.legTanggal("2026-10-05", 2));
        assertNull(CheckoutConstants.legTanggal("2026-13-45 99:99:99", 2));
        assertNull(CheckoutConstants.legTanggal("2026-10-05 09:15:30", 0));
    }

    @Test public void legTanggalAreDistinctForEveryLeg() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int seq = 1; seq <= CheckoutConstants.MAX_LEGS; seq++) {
            assertTrue(seen.add(CheckoutConstants.legTanggal("2026-10-05 09:15:30", seq)));
        }
    }

    @Test public void badgeFromThisRowOnly() {
        assertEquals("🧺 1/2 · Rumah", CheckoutConstants.badge(1, 2, "Rumah"));
        assertEquals("🧺 2/2 · Kedai", CheckoutConstants.badge(2, 2, "  Kedai "));
        assertEquals("🧺 2/3", CheckoutConstants.badge(2, 3, null));
        assertEquals("🧺 2/3", CheckoutConstants.badge(2, 3, "  "));
    }

    @Test public void badgeEmptyForPlainOrders() {
        assertEquals("", CheckoutConstants.badge(0, 0, "Rumah"));
        assertEquals("", CheckoutConstants.badge(1, 1, "Rumah"));
        assertEquals("", CheckoutConstants.badge(3, 2, "Rumah"));
        assertEquals("", CheckoutConstants.badge(1, 11, "Rumah"));
    }
}
