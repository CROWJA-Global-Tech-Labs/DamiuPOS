package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kampanye pelanggan (Phase 2, device side). Campaigns are pulled from the web (read-only here);
 * a {@link #createDelivery delivery} is minted on this device the first time a campaign is shared
 * with a customer (alongside the struk + tracking link) and then synced up. The
 * (campaign, customer) pair is the anti-rebroadcast key, so a customer never gets the same
 * campaign twice — even across the branch's devices (deliveries are branch-wide, so a delivery
 * another phone created is pulled here and dedupes the share).
 */
public class CampaignDao {

    private static final String TOKEN_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 10;   // matches the server's Str::random(10)
    private static final SecureRandom RNG = new SecureRandom();

    private final DatabaseHelper dbHelper;

    public CampaignDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** One sharable campaign: the local id (for the delivery ref) + the text shown in the WA caption. */
    public static final class Pending {
        public final long campaignLocalId;
        public final String title;
        public final String bodyText;
        Pending(long campaignLocalId, String title, String bodyText) {
            this.campaignLocalId = campaignLocalId;
            this.title = title;
            this.bodyText = bodyText;
        }
    }

    /**
     * Active campaigns that target {@code deviceUuid} and have NOT yet been delivered to this
     * customer — i.e. exactly what should be appended to the struk message for this sale.
     * A deactivated or web-deleted campaign drops out (deactivate → is_active=0 on pull; delete →
     * tombstone removes the local row), so it can never be (re)shared.
     */
    public List<Pending> activeForCustomer(String deviceUuid, long customerLocalId) {
        List<Pending> out = new ArrayList<>();
        if (deviceUuid == null || deviceUuid.isEmpty() || customerLocalId <= 0) return out;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGNS,
                new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_CAMP_TITLE,
                        DatabaseHelper.COL_CAMP_BODY, DatabaseHelper.COL_CAMP_TARGETS},
                DatabaseHelper.COL_CAMP_ACTIVE + "=1", null, null, null, null);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String targets = c.getString(3);
                if (!targetsInclude(targets, deviceUuid)) continue;
                if (hasDelivery(db, id, customerLocalId)) continue;
                out.add(new Pending(id, c.getString(1), c.getString(2)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Does {@code target_devices} (a JSON array of device uuids) contain this device? */
    private static boolean targetsInclude(String targetsJson, String deviceUuid) {
        if (targetsJson == null || targetsJson.isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(targetsJson);
            for (int i = 0; i < arr.length(); i++) {
                if (deviceUuid.equals(arr.optString(i, null))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean hasDelivery(SQLiteDatabase db, long campaignLocalId, long customerLocalId) {
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES,
                new String[]{DatabaseHelper.COL_ID},
                DatabaseHelper.COL_CD_CAMPAIGN_ID + "=? AND " + DatabaseHelper.COL_CD_CUSTOMER_ID + "=?",
                new String[]{String.valueOf(campaignLocalId), String.valueOf(customerLocalId)},
                null, null, null, "1");
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /**
     * Record that {@code campaignLocalId} was shared with {@code customerLocalId}: mint a public
     * token and insert a dirty "sent" delivery (pushed to the server on the next sync, which is
     * what makes the {@code /c/{token}} link resolve). Returns the token, or {@code null} on
     * failure. Idempotent — a duplicate (campaign, customer) is ignored and returns null.
     */
    public String createDelivery(long campaignLocalId, long customerLocalId) {
        if (campaignLocalId <= 0 || customerLocalId <= 0) return null;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        if (hasDelivery(db, campaignLocalId, customerLocalId)) return null;
        String token = newToken();
        String now = DatabaseHelper.nowIso();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_CD_CAMPAIGN_ID, campaignLocalId);
        v.put(DatabaseHelper.COL_CD_CUSTOMER_ID, customerLocalId);
        v.put(DatabaseHelper.COL_CD_TOKEN, token);
        v.put(DatabaseHelper.COL_CD_SENT_AT, now);
        v.put(DatabaseHelper.COL_SYNC_UUID, UUID.randomUUID().toString());
        v.put(DatabaseHelper.COL_EDITED_AT, now);
        v.put(DatabaseHelper.COL_SYNCED, 0);   // dirty → pushed next sync
        long id = db.insert(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES, null, v);
        return id > 0 ? token : null;
    }

    private static String newToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(RNG.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }
}
