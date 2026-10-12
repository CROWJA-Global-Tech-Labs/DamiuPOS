package com.crowja.damiupos.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pembaruan Wajib tak boleh memaksa HP yang sudah memasang versi sama/lebih baru (loop update). */
public class VersionUpdaterCodeTest {

    @Test public void parsesCodeFromReleaseLabel() {
        assertEquals(169, VersionUpdater.publishedVersionCode("1.4.150 (169) · 12 Oct 2026 15:25"));
        assertEquals(-1, VersionUpdater.publishedVersionCode("12 Oct 2026 15:25"));
        assertEquals(-1, VersionUpdater.publishedVersionCode(null));
    }

    @Test public void sameOrNewerInstalledIsNotForced() {
        assertTrue(VersionUpdater.installedIsSameOrNewer(169, "1.4.150 (169) · 12 Oct 2026 15:25"));
        assertTrue(VersionUpdater.installedIsSameOrNewer(170, "1.4.150 (169)"));
        assertFalse(VersionUpdater.installedIsSameOrNewer(168, "1.4.150 (169)"));
        assertFalse(VersionUpdater.installedIsSameOrNewer(500, "12 Oct 2026 15:25"));   // label lama → aturan hash
    }
}
