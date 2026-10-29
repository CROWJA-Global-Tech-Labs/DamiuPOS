package com.crowja.damiupos.wa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Aturan form Status WhatsApp di HP — harus sama dengan skrip form web (status_form.blade.php). */
public class WaStatusRulesTest {

    @Test
    public void closingKeywordsOnlySuggestOnWholeWords() {
        assertTrue(WaStatusRules.suggestsClose("Hari ini TUTUP lebih awal"));
        assertTrue(WaStatusRules.suggestsClose("Besok libur ya kak"));
        assertTrue(WaStatusRules.suggestsClose("Close order jam 3"));
        assertTrue(WaStatusRules.suggestsClose("Maaf sudah penuh"));
        assertFalse(WaStatusRules.suggestsClose("Promo galon tutupan baru"));
        assertFalse(WaStatusRules.suggestsClose("Promo akhir pekan"));
        assertFalse(WaStatusRules.suggestsClose(null));
    }

    @Test
    public void emojiCountsAsTwoUnits() {
        assertEquals(2, WaStatusRules.units("🔥"));
        assertEquals(3, WaStatusRules.units("a🔥"));
        assertEquals(0, WaStatusRules.units(null));
    }

    @Test
    public void saveLabelFollowsTheChosenSchedule() {
        assertEquals("Posting sekarang", WaStatusRules.saveLabel("now", false));
        assertEquals("Jadwalkan", WaStatusRules.saveLabel("schedule", false));
        assertEquals("Simpan draf", WaStatusRules.saveLabel("draft", false));
        assertEquals("Simpan perubahan", WaStatusRules.saveLabel("keep", true));
        assertEquals("Posting ulang sekarang", WaStatusRules.saveLabel("now", true));
        assertEquals("Jadwalkan ulang", WaStatusRules.saveLabel("schedule", true));
        assertEquals("Jadikan draf", WaStatusRules.saveLabel("draft", true));
    }

    @Test
    public void confirmationsMatchTheWebForm() {
        // Draf baru tanpa penanda → langsung simpan.
        assertNull(WaStatusRules.submitConfirm("draft", false, false, false, false, false, true, true, 2));
        // Posting sekarang Status baru → selalu dikonfirmasi (banyak penonton).
        assertEquals("Status langsung tayang di 2 akun WhatsApp. Lanjutkan?",
                WaStatusRules.submitConfirm("now", false, false, false, false, false, true, true, 2));
        // Menutup order saat dijadwalkan.
        assertEquals("Postingan ini akan MENUTUP order pada hari jadwalnya begitu terposting — order baru dijadwalkan untuk besok. Lanjutkan?",
                WaStatusRules.submitConfirm("schedule", false, false, true, false, false, true, true, 1));
        // Posting ulang Status yang tayang + menutup → dua pesan dipisah baris kosong.
        String two = WaStatusRules.submitConfirm("now", true, true, true, false, true, true, true, 1);
        assertTrue(two.startsWith("Ini memasang Status BARU"));
        assertTrue(two.contains("\n\nPostingan ini akan MENUTUP order hari ini"));
        assertTrue(two.endsWith("\n\nLanjutkan?"));
        // Simpan saja + centang baru pada Status yang tayang hari ini → order langsung ditutup.
        assertTrue(WaStatusRules.submitConfirm("keep", true, true, true, false, true, true, true, 1).contains("langsung DITUTUP"));
        // ...tapi tidak bila Status berulang / sudah bertanda / tak bisa ditutup lagi.
        assertNull(WaStatusRules.submitConfirm("keep", true, true, true, false, true, true, false, 1));
        assertNull(WaStatusRules.submitConfirm("keep", true, true, true, true, true, true, true, 1));
        assertNull(WaStatusRules.submitConfirm("keep", true, true, true, false, true, false, true, 1));
        // Jadikan draf Status yang sudah tayang → riwayat tayang hilang dari daftar.
        assertTrue(WaStatusRules.submitConfirm("draft", true, true, false, false, false, true, true, 1).startsWith("Jadikan draf"));
    }

    @Test
    public void postNowConfirmPromisesClosingOnlyWhenItWillClose() {
        assertTrue(WaStatusRules.postNowConfirm(true, true, 2).contains("akan MENUTUP order hari ini"));
        assertTrue(WaStatusRules.postNowConfirm(false, true, 2).contains("TIDAK menutup order"));
        assertEquals("Posting Status ini sekarang ke 3 akun WhatsApp?", WaStatusRules.postNowConfirm(false, false, 3));
    }
}
