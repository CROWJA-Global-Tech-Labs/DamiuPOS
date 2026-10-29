package com.crowja.damiupos.wa;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Muat pelanggan per aturan wilayah yang sedang berjalan (kunci = {@link WaScheduleDraft#areaKey}), dari form
 * maupun peta — murni Java supaya bisa diuji unit.
 *
 * <p>Kenapa perlu: saat simpan, server menganggap pelanggan yang cocok wilayah otomatis tapi TIDAK ada di daftar
 * penerima sebagai "sengaja dikeluarkan" (auto_excluded) dan tak pernah mengirimi mereka. Jadi form tidak boleh
 * disimpan selama pelanggan suatu wilayah masih dimuat, atau bila ada wilayah otomatis yang pelanggannya belum
 * termuat (galat jaringan, atau peta ditutup sebelum balasan tiba).</p>
 */
final class WaScheduleLoads {

    private final Map<String, Integer> inflight = new HashMap<>();

    void begin(String key) {
        Integer n = inflight.get(key);
        inflight.put(key, n == null ? 1 : n + 1);
    }

    void end(String key) {
        Integer n = inflight.get(key);
        if (n == null) return;
        if (n <= 1) inflight.remove(key);
        else inflight.put(key, n - 1);
    }

    /** Masih ada muat yang berjalan. */
    boolean busy() {
        return !inflight.isEmpty();
    }

    /** Aturan ini sedang dimuat. */
    boolean loading(String key) {
        return inflight.containsKey(key);
    }

    /** Pesan penahan simpan (pendek: toast Android 12+ maks. dua baris), atau null bila form boleh disimpan. */
    @Nullable
    static String saveBlocker(boolean busy, int unloadedAutoAreas) {
        if (busy) return "Masih memuat pelanggan wilayah — tunggu sebentar, lalu simpan lagi.";
        if (unloadedAutoAreas > 0) {
            return "Pelanggan " + unloadedAutoAreas + " wilayah otomatis belum termuat — dimuat ulang, simpan lagi sebentar.";
        }
        return null;
    }
}
