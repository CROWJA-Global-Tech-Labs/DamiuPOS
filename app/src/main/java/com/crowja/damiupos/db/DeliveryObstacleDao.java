package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;

import java.util.List;

/**
 * "Laporan Kendala Pengiriman" — kendala SE-RIT yang menghambat pengiriman (motor rusak, banjir,
 * ban bocor, …), dilaporkan kurir dari layar Antrian Delivery.
 *
 * <p>Selalu lewat {@link DatabaseHelper#syncInsert}/{@link DatabaseHelper#syncUpdate}, TIDAK PERNAH
 * db.insert langsung: keduanya yang mencetak {@code sync_uuid} + {@code edited_at} + menandai baris
 * dirty. Baris tanpa sync_uuid dilewati begitu saja oleh SyncEngine.push() — laporannya akan
 * tersimpan di HP tapi tak pernah terlihat di dashboard, tanpa pesan galat apa pun.
 */
public class DeliveryObstacleDao {

    private final DatabaseHelper dbHelper;

    public DeliveryObstacleDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Simpan laporan baru.
     *
     * @param customerUuids uuid pelanggan terdampak — SNAPSHOT saat melapor (antrean berubah terus)
     * @return _id lokal baris baru
     */
    public long insert(String reason, String photoPath, List<String> customerUuids,
                       double lat, double lng, String deviceUuid) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DO_REASON, reason != null ? reason.trim() : "");
        v.put(DatabaseHelper.COL_PHOTO_PATH, photoPath);
        v.put(DatabaseHelper.COL_DO_DEVICE_UUID, deviceUuid);
        v.put(DatabaseHelper.COL_DO_CUSTOMER_UUIDS,
                new JSONArray(customerUuids != null ? customerUuids : java.util.Collections.emptyList()).toString());
        v.put(DatabaseHelper.COL_DO_AFFECTED_COUNT, customerUuids != null ? customerUuids.size() : 0);
        v.put(DatabaseHelper.COL_DO_LAT, lat);
        v.put(DatabaseHelper.COL_DO_LNG, lng);
        v.put(DatabaseHelper.COL_DO_REPORTED_AT, DatabaseHelper.nowIso());
        v.put(DatabaseHelper.COL_CREATED_AT, DatabaseHelper.nowIso());

        SettingsDao settings = new SettingsDao(dbHelper);
        String operator = settings.getCurrentUserName();
        if (operator != null && !operator.isEmpty()) {
            v.put(DatabaseHelper.COL_DO_CREATED_BY, operator);
        }
        long staffId = settings.getCurrentUserId();
        if (staffId > 0) {
            v.put(DatabaseHelper.COL_DO_STAFF_ID, staffId);
        }

        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_DELIVERY_OBSTACLES, v);
    }

    /**
     * Stempel hasil "Beritahu Konsumen". Lewat syncUpdate supaya barisnya dirty lagi & terdorong
     * naik pada sinkron berikutnya.
     *
     * <p>CATATAN: push HP MENGHILANGKAN kolom bernilai null (lihat SyncEngine.push) — nilai bisa
     * DI-SET tapi tak pernah bisa DIKOSONGKAN lagi dari sini. Itu sebabnya stempel ini sekali-jalan;
     * bila kelak perlu "batalkan pemberitahuan", tambahkan kolom notified_cleared_at TERSENDIRI
     * (pola delivery_started_at / delivery_started_cleared_at), jangan menimpanya dengan null.
     */
    public void markNotified(long id, int notifiedCount, String resultsJson) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_DO_NOTIFIED_AT, DatabaseHelper.nowIso());
        v.put(DatabaseHelper.COL_DO_NOTIFIED_COUNT, notifiedCount);
        if (resultsJson != null) {
            v.put(DatabaseHelper.COL_DO_NOTIFY_RESULTS, resultsJson);
        }
        dbHelper.syncUpdate(db, DatabaseHelper.TABLE_DELIVERY_OBSTACLES, v,
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Path foto lokal laporan (untuk dilampirkan ke pesan WA), "" bila tak ada. */
    public String getPhotoPath(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_DELIVERY_OBSTACLES,
                new String[]{DatabaseHelper.COL_PHOTO_PATH},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(id)},
                null, null, null, "1");
        try {
            String p = c.moveToFirst() ? c.getString(0) : null;
            return p != null ? p : "";
        } finally {
            c.close();
        }
    }
}
