package com.crowja.damiupos;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * "Petugas WA Perkenalan" — logika tugas perangkat ini (dicentang admin di web, dibaca dari
 * /api/me lewat {@link SyncSettings}): pelanggan promosi mana yang jatuh ke wilayah tanggung
 * jawabnya. Satu definisi untuk DUA pemakai — badge di MainActivity dan notifikasi kedatangan di
 * SyncEngine — supaya angka badge dan notifikasi tak pernah beda aturan.
 */
public final class IntroWaDuty {

    private IntroWaDuty() {}

    /**
     * Apakah pelanggan pada koordinat ini masuk wilayah tugas perangkat?
     *
     * <p>FAIL-OPEN di semua ketidakpastian (cermin filter penugasan lain di HP): daftar sektor
     * tugas kosong = SEMUA wilayah; pelanggan tanpa koordinat, atau konfigurasi wilayah cabang
     * belum ada = tetap DIHITUNG — pelanggan perkenalan tak boleh luput disapa hanya karena
     * datanya belum lengkap. Ketat hanya saat semuanya tersedia: index sektor koordinat harus
     * ada di daftar tugas.</p>
     */
    public static boolean inMyZones(SyncSettings cfg, double lat, double lng) {
        JSONArray mine = Wilayah.parseZones(cfg.getIntroWaZones());   // array of int (index sektor)
        if (mine == null || mine.length() == 0) return true;          // kosong = semua wilayah

        double[] center = Wilayah.parseCenter(cfg.getBranchCenter());
        JSONArray zones = Wilayah.parseZones(cfg.getWilayahZones());
        if (center == null || zones == null || zones.length() < 2) return true;   // config belum ada
        if (lat == 0 && lng == 0) return true;                        // tanpa koordinat → jangan luput

        int idx = Wilayah.zoneIndex(zones, center[0], center[1], lat, lng);
        if (idx < 0) return true;
        for (int i = 0; i < mine.length(); i++) {
            if (mine.optInt(i, -1) == idx) return true;
        }
        return false;
    }

    /** Saring kandidat badge ke wilayah tugas perangkat ini (lihat {@link #inMyZones}). */
    public static List<CustomerDao.IntroPendingRow> filterPending(
            List<CustomerDao.IntroPendingRow> rows, SyncSettings cfg) {
        List<CustomerDao.IntroPendingRow> out = new ArrayList<>();
        if (rows == null) return out;
        for (CustomerDao.IntroPendingRow r : rows) {
            if (r != null && inMyZones(cfg, r.lat, r.lng)) out.add(r);
        }
        return out;
    }
}
