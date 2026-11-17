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
        assertEquals("18 Nov 08:00 · hilang 08:00 · “Promo” · 740 penonton",
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
}
