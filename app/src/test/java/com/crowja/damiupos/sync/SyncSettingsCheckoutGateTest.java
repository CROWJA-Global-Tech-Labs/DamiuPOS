package com.crowja.damiupos.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 🧺 Gerbang checkout multi-lokasi dari /api/me ("checkout_multi_enabled", kontrak boolean). Nilai
 * mentahnya = {@code JSONObject.opt(...)}: Boolean dari server baru, null dari server lama (kunci
 * absen) → MATI. Angka/teks "1"/"true" ikut diterima supaya server yang mengirim 1 tak mematikannya.
 */
public class SyncSettingsCheckoutGateTest {

    @Test public void booleanFromServer() {
        assertTrue(SyncSettings.parseCheckoutMultiEnabled(Boolean.TRUE));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(Boolean.FALSE));
    }

    @Test public void absentKeyOnOldServerIsOff() {
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(null));
    }

    @Test public void numericAndTextForms() {
        assertTrue(SyncSettings.parseCheckoutMultiEnabled(1));
        assertTrue(SyncSettings.parseCheckoutMultiEnabled(1L));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(0));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(2));
        assertTrue(SyncSettings.parseCheckoutMultiEnabled("1"));
        assertTrue(SyncSettings.parseCheckoutMultiEnabled(" true "));
        assertTrue(SyncSettings.parseCheckoutMultiEnabled("TRUE"));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled("0"));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(""));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled("false"));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled("yes"));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled("null"));   // JSONObject.NULL.toString()
    }

    @Test public void blockerReasonOnlyFromString() {
        assertEquals("HP kurir lama", SyncSettings.parseCheckoutMultiBlocker(" HP kurir lama "));
        assertEquals("", SyncSettings.parseCheckoutMultiBlocker(null));
        assertEquals("", SyncSettings.parseCheckoutMultiBlocker(Boolean.FALSE));
        assertEquals("", SyncSettings.parseCheckoutMultiBlocker(new Object()));   // JSONObject.NULL
    }

    @Test public void otherTypesAreOff() {
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(new Object()));
        assertFalse(SyncSettings.parseCheckoutMultiEnabled(new int[]{1}));
    }
}
