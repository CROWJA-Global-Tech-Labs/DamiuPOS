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

    /** {server entity, local table}. Both tables name the columns photo_path / photo_url. */
    private static final String[][] ENTITIES = {
            {"customers", DatabaseHelper.TABLE_CUSTOMERS},
            {"attendance", DatabaseHelper.TABLE_ATTENDANCE},
    };

    private static final int MAX_PER_CYCLE = 12;   // images uploaded per sync run
    private static final int SCAN_LIMIT = 40;      // rows examined per entity per run

    private MediaUploader() {}

    /** @return number of images uploaded + stamped this run. */
    static int uploadPending(SQLiteDatabase db, SyncApi api) {
        int uploaded = 0;
        for (String[] et : ENTITIES) {
            if (uploaded >= MAX_PER_CYCLE) break;
            String entity = et[0], table = et[1];

            Cursor c = db.query(table,
                    new String[]{DatabaseHelper.COL_SYNC_UUID, DatabaseHelper.COL_PHOTO_PATH},
                    DatabaseHelper.COL_PHOTO_PATH + " IS NOT NULL AND " + DatabaseHelper.COL_PHOTO_PATH + " <> '' "
                            + "AND (" + DatabaseHelper.COL_PHOTO_URL + " IS NULL OR "
                            + DatabaseHelper.COL_PHOTO_URL + " = '')",
                    null, null, null, null, String.valueOf(SCAN_LIMIT));
            try {
                while (c.moveToNext()) {
                    if (uploaded >= MAX_PER_CYCLE) break;
                    String uuid = c.getString(0);
                    String path = c.getString(1);
                    if (uuid == null || uuid.isEmpty() || path == null) continue;
                    File f = new File(path);
                    if (!f.exists() || f.length() == 0) continue;   // local file gone — skip
                    try {
                        JSONObject resp = api.uploadMedia(entity, uuid, f);
                        String url = resp.optString("url", "");
                        if (url.isEmpty()) continue;
                        // Stamp + mark dirty so the URL syncs to the dashboard.
                        db.execSQL("UPDATE " + table + " SET " + DatabaseHelper.COL_PHOTO_URL + "=?, "
                                        + DatabaseHelper.COL_EDITED_AT + "=?, " + DatabaseHelper.COL_SYNCED + "=0"
                                        + " WHERE " + DatabaseHelper.COL_SYNC_UUID + "=?",
                                new Object[]{url, DatabaseHelper.nowIso(), uuid});
                        uploaded++;
                    } catch (Exception e) {
                        // Network/server error — stop this run, retry whole backlog next cycle.
                        return uploaded;
                    }
                }
            } finally {
                c.close();
            }
        }
        return uploaded;
    }
}
