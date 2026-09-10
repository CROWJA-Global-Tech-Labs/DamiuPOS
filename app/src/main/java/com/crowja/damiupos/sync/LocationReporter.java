package com.crowja.damiupos.sync;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.Nullable;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONObject;

/**
 * Reports a staff GPS fix to the server (`POST /api/location/ping`). The server stores
 * the history row AND fans out the retained MQTT message that the web dashboard's live
 * map consumes — so the phone only needs to POST.
 */
public final class LocationReporter {

    private LocationReporter() {}

    /** sync_uuid of the currently clocked-in staff, or null. */
    public static String currentStaffUuid(Context ctx) {
        DatabaseHelper db = DatabaseHelper.getInstance(ctx);
        long uid = new SettingsDao(db).getCurrentUserId();
        if (uid <= 0) return null;
        String uuid = null;
        Cursor c = db.getReadableDatabase().query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_USER_ID + "=?", new String[]{String.valueOf(uid)},
                null, null, null);
        if (c.moveToFirst()) uuid = c.getString(0);
        c.close();
        return uuid;
    }

    /** POST one location fix. Returns true on success (best-effort; never throws). */
    public static boolean report(Context ctx, String staffUuid, double lat, double lng,
                                 @Nullable Float accuracy, @Nullable Float speed,
                                 @Nullable Float bearing, @Nullable Integer battery) {
        if (staffUuid == null || staffUuid.isEmpty()) return false;
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled()) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("staff_uuid", staffUuid);
            body.put("latitude", lat);
            body.put("longitude", lng);
            if (accuracy != null) body.put("accuracy", accuracy);
            if (speed != null) body.put("speed", speed);
            if (bearing != null) body.put("bearing", bearing);
            if (battery != null) body.put("battery", battery);
            body.put("recorded_at", DatabaseHelper.nowIso());
            JSONObject r = new SyncApi(cfg).locationPing(body);
            return r.optBoolean("ok", false);
        } catch (Throwable t) {
            return false;
        }
    }
}
