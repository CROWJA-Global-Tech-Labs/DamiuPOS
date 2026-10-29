package com.crowja.damiupos.wa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;

/** Teks Broadcast Terjadwal: pratinjau {nama} sama dengan form web, format waktu server bolak-balik. */
public class WaScheduleTextTest {

    @Test
    public void previewReplacesNamaAndNameCaseInsensitive() {
        assertEquals("Halo Bu Rina, promo! Bu Rina", WaScheduleText.preview("Halo {nama}, promo! {NAME}", "Bu Rina"));
        assertEquals("Halo Bu Sari", WaScheduleText.preview("Halo {nama}", null));   // belum ada penerima
        assertEquals("Halo Kak", WaScheduleText.preview("Halo {Nama}", ""));         // pelanggan tanpa nama
        assertEquals("—", WaScheduleText.preview("", "X"));
        assertEquals("Rp$5 untuk A\\B", WaScheduleText.preview("Rp$5 untuk {nama}", "A\\B"));
    }

    @Test
    public void insertNamaAtCursorAndClampsRange() {
        assertEquals("Halo {nama}, ", WaScheduleText.insertNama("Halo , ", 5, 5));
        assertEquals("{nama}", WaScheduleText.insertNama("pilih", 0, 99));
        assertEquals("ab{nama}", WaScheduleText.insertNama("ab", 7, 3));
    }

    @Test
    public void wireFormatRoundTrips() {
        Calendar c = WaScheduleText.parse("2026-10-30 08:05");
        assertNotNull(c);
        assertEquals(2026, c.get(Calendar.YEAR));
        assertEquals(Calendar.OCTOBER, c.get(Calendar.MONTH));
        assertEquals(30, c.get(Calendar.DAY_OF_MONTH));
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY));
        assertEquals("2026-10-30 08:05", WaScheduleText.wire(c));
        assertNull(WaScheduleText.parse("besok pagi"));
        assertNull(WaScheduleText.parse(null));
        assertEquals("", WaScheduleText.display(null));
        assertEquals("besok", WaScheduleText.display("besok"));
    }

    @Test
    public void smallHelpers() {
        assertEquals("07:30", WaScheduleText.hhmm(7, 30));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 80; i++) longName.append('x');
        assertEquals(60, WaScheduleText.label60(longName.toString()).length());
        assertEquals("Trip A", WaScheduleText.label60("  Trip A "));
        assertEquals("3 terkirim", WaScheduleText.countsLine(3, 0, 0));
        assertEquals("3 terkirim · 1 gagal · 2 menunggu", WaScheduleText.countsLine(3, 1, 2));
    }
}
