package com.crowja.damiupos.sync;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.db.DatabaseHelper;

import org.json.JSONObject;

import java.io.File;

/**
 * Uploads local images (customer photos, attendance selfies) to the server and stamps
 * the returned URL onto the row's {@code photo_url} column — marked dirty so the URL
 * rides the normal row sync to the web dashboard.
 *
 * <p>"Needs upload" = a local {@code photo_path} is set but {@code photo_url} is still
 * empty. The upload endpoint is pure file storage (it does not touch rows), so it's safe
 * to upload before the owning row has been pushed; the URL is pushed in the same cycle.
 * Bounded per cycle so a backlog of photos never makes one sync run unbounded.
 */
final class MediaUploader {

    /** {server entity, local table, upload maxDim, kuota per cycle}. Both tables name the
     *  columns photo_path / photo_url. Kuota per entitas supaya backfill foto rumah yang
     *  besar (ratusan pelanggan) tidak membuat selfie absensi / nota kelaparan. */
    private static final String[][] ENTITIES = {
            {"customers", DatabaseHelper.TABLE_CUSTOMERS, "1280", "12"},   // foto rumah → 720p
            {"attendance", DatabaseHelper.TABLE_ATTENDANCE, "1600", "4"},
            {"expenses", DatabaseHelper.TABLE_EXPENSES, "1600", "4"},   // foto nota → URL disinkron ke dashboard
    };

    private static final int SCAN_LIMIT = 40;      // rows examined per entity per run
    /** Batas aman di bawah cap server (20 MB): file tak terkompres di atas ini pasti ditolak. */
    private static final long MAX_UPLOAD_BYTES = 19L * 1024 * 1024;

    private MediaUploader() {}

    /** @return number of images uploaded + stamped this run. */
    static int uploadPending(SQLiteDatabase db, SyncApi api) {
        int uploaded = 0;
        for (String[] et : ENTITIES) {
            String entity = et[0], table = et[1];
            int maxDim = Integer.parseInt(et[2]);
            int quota = Integer.parseInt(et[3]);
            int attempts = 0;   // sukses + gagal-4xx sama-sama menghabiskan kuota (batasi bandwidth)

            // Terbaru-dulu (edited_at DESC): pelanggan yang BARU ditambah/diedit langsung
            // terunggah lebih dulu, tidak mengantre di belakang backlog foto lama.
            Cursor c = db.query(table,
                    new String[]{DatabaseHelper.COL_SYNC_UUID, DatabaseHelper.COL_PHOTO_PATH},
                    DatabaseHelper.COL_PHOTO_PATH + " IS NOT NULL AND " + DatabaseHelper.COL_PHOTO_PATH + " <> '' "
                            + "AND (" + DatabaseHelper.COL_PHOTO_URL + " IS NULL OR "
                            + DatabaseHelper.COL_PHOTO_URL + " = '')",
                    null, null, null,
                    DatabaseHelper.COL_EDITED_AT + " DESC",
                    String.valueOf(SCAN_LIMIT));
            try {
                while (c.moveToNext()) {
                    if (attempts >= quota) break;
                    String uuid = c.getString(0);
                    String path = c.getString(1);
                    if (uuid == null || uuid.isEmpty() || path == null) continue;
                    File f = new File(path);
                    if (!f.exists() || f.length() == 0) {
                        // File lokal sudah hilang (mis. DB hasil restore dari HP lain) — tidak akan
                        // pernah bisa diunggah. Bersihkan path supaya baris mati tidak menyumbat
                        // jendela scan (LIMIT 40). photo_path lokal-only → tanpa flag sync.
                        db.execSQL("UPDATE " + table + " SET " + DatabaseHelper.COL_PHOTO_PATH
                                        + "=NULL WHERE " + DatabaseHelper.COL_SYNC_UUID + "=?",
                                new Object[]{uuid});
                        continue;
                    }
                    // Downscale + recompress before upload: full-res camera/gallery photos can exceed
                    // the server's size cap (→ 422 → photo never reaches the web). Fall back to the
                    // original file if compression fails; the temp is removed after each attempt.
                    // Nama tmp unik per attempt: sync periodik & syncNow bisa berjalan BERSAMAAN
                    // (unique work berbeda) — tmp deterministik bisa saling menimpa saat streaming.
                    File tmp = new File(f.getParentFile(),
                            "upl_" + uuid + "_" + System.nanoTime() + ".jpg");
                    File toUpload = com.crowja.damiupos.util.BitmapUtils.compressForUpload(path, tmp, maxDim, 85)
                            ? tmp : f;
                    if (toUpload == f && f.length() > MAX_UPLOAD_BYTES) {
                        // Tak bisa dikompres (file tak ter-decode) DAN melebihi cap server —
                        // pasti ditolak; jangan buang bandwidth. Baris tetap pending.
                        tmp.delete();
                        continue;
                    }
                    try {
                        JSONObject resp = api.uploadMedia(entity, uuid, toUpload);
                        String url = resp.optString("url", "");
                        if (url.isEmpty()) { attempts++; continue; }
                        // Stamp + mark dirty so the URL syncs to the dashboard. Guard photo_path:
                        // kalau user sempat mengganti foto SELAMA upload berlangsung, jangan
                        // menimpanya dengan URL foto lama — biarkan tetap pending agar foto baru
                        // terunggah pada siklus berikutnya.
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(DatabaseHelper.COL_PHOTO_URL, url);
                        cv.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
                        cv.put(DatabaseHelper.COL_SYNCED, 0);
                        db.update(table, cv,
                                DatabaseHelper.COL_SYNC_UUID + "=? AND " + DatabaseHelper.COL_PHOTO_PATH + "=?",
                                new String[]{uuid, path});
                        uploaded++;
                        attempts++;
                    } catch (SyncApi.SyncException se) {
                        if (se.code >= 400 && se.code < 500) {
                            // Ditolak permanen utk file ini (413/422/…): lewati supaya SATU baris
                            // beracun tidak memblokir seluruh antrean; coba lagi siklus berikutnya.
                            attempts++;
                            continue;
                        }
                        return uploaded;   // 5xx — server bermasalah, hentikan run ini
                    } catch (Exception e) {
                        // Network error — stop this run, retry whole backlog next cycle.
                        return uploaded;
                    } finally {
                        if (tmp.exists()) tmp.delete();
                    }
                }
            } finally {
                c.close();
            }
        }
        return uploaded;
    }
}
