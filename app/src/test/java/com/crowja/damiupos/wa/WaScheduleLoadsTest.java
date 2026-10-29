package com.crowja.damiupos.wa;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Penahan simpan form Broadcast Terjadwal: jangan kirim daftar penerima selagi pelanggan wilayah belum termuat. */
public class WaScheduleLoadsTest {

    @Test
    public void tracksOverlappingLoadsPerKey() {
        WaScheduleLoads l = new WaScheduleLoads();
        assertFalse(l.busy());
        l.begin("id:5");
        l.begin("id:5");   // form (muat awal) + peta memuat aturan yang sama
        l.begin("kec|");
        assertTrue(l.busy());
        assertTrue(l.loading("id:5"));
        l.end("id:5");
        assertTrue(l.loading("id:5"));   // satu lagi masih berjalan
        l.end("id:5");
        assertFalse(l.loading("id:5"));
        assertTrue(l.busy());
        l.end("kec|");
        l.end("kec|");   // akhir ganda tak membuat hitungan negatif
        assertFalse(l.busy());
        l.begin("kec|");
        assertTrue(l.loading("kec|"));
    }

    @Test
    public void saveIsBlockedWhileLoadingOrWhenAnAutoAreaIsUnloaded() {
        assertNull(WaScheduleLoads.saveBlocker(false, 0));
        assertNotNull(WaScheduleLoads.saveBlocker(true, 0));
        String m = WaScheduleLoads.saveBlocker(false, 2);
        assertNotNull(m);
        assertTrue(m.contains("2 wilayah otomatis"));
        assertTrue(WaScheduleLoads.saveBlocker(true, 3).startsWith("Masih memuat"));
    }
}
