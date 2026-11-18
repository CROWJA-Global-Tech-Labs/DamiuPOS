package com.crowja.damiupos.wa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Teks aktif/nonaktif Status terjadwal & penampil Status tayang — sama dengan web /wa-status. */
public class WaStatusLiveRulesTest {

    @Test
    public void pauseLabelsMatchTheWebButtons() {
        assertEquals("⏸ Nonaktifkan", WaStatusRules.pauseLabel(false));
        assertEquals("▶ Aktifkan", WaStatusRules.pauseLabel(true));
    }

    @Test
    public void pauseConfirmExplainsTheScheduleIsKept() {
        assertTrue(WaStatusRules.pauseConfirm(false, "STATUS JAM BUKA").contains("\"STATUS JAM BUKA\""));
        assertTrue(WaStatusRules.pauseConfirm(false, null).contains("tetap tersimpan"));
        assertTrue(WaStatusRules.pauseConfirm(true, "").startsWith("Aktifkan lagi Status ini?"));
    }

    @Test
    public void liveMetaPrefersThePostNameThenThePhoneSource() {
        assertEquals("18 Nov 08:00 · hilang 08:00 · “Promo” · dikirim ke 740 kontak",
                WaStatusRules.liveMeta("18 Nov 08:00", "hilang 08:00", "Promo", "bridge", 740));
        assertEquals("18 Nov 08:00 · diposting dari HP", WaStatusRules.liveMeta("18 Nov 08:00", null, null, "phone", 0));
        assertEquals("", WaStatusRules.liveMeta(null, "", null, "bridge", 0));
    }

    @Test
    public void deleteConfirmNamesTheAccountAndIsIrreversible() {
        String s = WaStatusRules.liveDeleteConfirm("RAFI");
        assertTrue(s.contains("RAFI"));
        assertTrue(s.contains("Tidak bisa dibatalkan"));
    }

    @Test
    public void viewerLabelsShowCountAndMarkCustomers() {
        assertEquals("👁 Penonton", WaStatusRules.viewsButton(null));
        assertEquals("👁 4 penonton", WaStatusRules.viewsButton(4));
        assertEquals("👁 4 penonton · 2 pelanggan", WaStatusRules.viewersTitle(4, 2));
        assertEquals("Bu Sari  ✓ pelanggan\n081211112222 · WA: Sari WA · 18 Nov 09:01",
                WaStatusRules.viewerLine("Bu Sari", "081211112222", "18 Nov 09:01", true, "Sari WA"));
        assertEquals("Orang Lain\n0899 · 18 Nov 09:02", WaStatusRules.viewerLine("Orang Lain", "0899", "18 Nov 09:02", false, "Orang Lain"));
        assertEquals("Kontak tersembunyi", WaStatusRules.viewerLine("Kontak tersembunyi", null, null, false, null));
    }
}
