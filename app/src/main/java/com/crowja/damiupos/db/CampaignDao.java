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
 * a {@link #ensureDelivery delivery} is minted on this device the first time a campaign is shared
 * with a customer (alongside the struk + tracking link) and then synced up.
 *
 * The campaign keeps being RE-ATTACHED to the same customer's struk on every sale until it is
 * "fulfilled" — i.e. UNTIL EITHER the customer ENGAGED with it: FILLED the questionnaire
 * (responded_at) OR gave a 👍👎 reaction (reacted_at) — both web-set and synced down — OR the
 * campaign is removed on the web (deactivate → is_active=0 on pull; delete → tombstone removes the
 * local row, so it drops out of {@link #activeForCustomer}). Merely CLICKING the link (clicked_at)
 * does NOT stop it — we keep sending until they actually respond/react. Each re-attach reuses the
 * SAME (campaign, customer) delivery token, so it's one delivery per customer — even across the
 * branch's devices (deliveries are branch-wide, pulled & respected here).
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
     * Active campaigns that target {@code deviceUuid} and are NOT yet fulfilled for this customer —
     * i.e. the customer hasn't RESPONDED/REACTED yet (a mere click doesn't count) — so they should be
     * (re)appended to this sale's struk. A deactivated or web-deleted campaign drops out (deactivate →
     * is_active=0 on pull; delete → tombstone removes the local row), so it stops being shared too.
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
                if (isDeliveryFulfilled(db, id, customerLocalId)) continue;   // sudah mengisi/bereaksi → stop
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

    /**
     * Is this campaign FULFILLED for this customer — i.e. did they ENGAGE: FILL the questionnaire
     * ({@code responded_at}) OR give a 👍👎 reaction ({@code reacted_at})? Both are web-set and synced
     * down. A mere CLICK ({@code clicked_at}) is NOT enough — keep re-attaching until they respond/react.
     */
    private static boolean isDeliveryFulfilled(SQLiteDatabase db, long campaignLocalId, long customerLocalId) {
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES,
                new String[]{DatabaseHelper.COL_ID},
                DatabaseHelper.COL_CD_CAMPAIGN_ID + "=? AND " + DatabaseHelper.COL_CD_CUSTOMER_ID + "=? AND ("
                        + "(" + DatabaseHelper.COL_CD_RESPONDED_AT + " IS NOT NULL AND " + DatabaseHelper.COL_CD_RESPONDED_AT + " != '') OR "
                        + "(" + DatabaseHelper.COL_CD_REACTED_AT + " IS NOT NULL AND " + DatabaseHelper.COL_CD_REACTED_AT + " != ''))",
                new String[]{String.valueOf(campaignLocalId), String.valueOf(customerLocalId)},
                null, null, null, "1");
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** Token of an existing (not-yet-fulfilled) delivery for this pair, or null — to re-attach the SAME link. */
    private static String existingToken(SQLiteDatabase db, long campaignLocalId, long customerLocalId) {
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES,
                new String[]{DatabaseHelper.COL_CD_TOKEN},
                DatabaseHelper.COL_CD_CAMPAIGN_ID + "=? AND " + DatabaseHelper.COL_CD_CUSTOMER_ID + "=?",
                new String[]{String.valueOf(campaignLocalId), String.valueOf(customerLocalId)},
                null, null, DatabaseHelper.COL_CD_SENT_AT + " DESC", "1");
        try {
            return (c.moveToFirst() && c.getString(0) != null && !c.getString(0).isEmpty()) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    /**
     * Get-or-create the sharable delivery for (campaign, customer) and return its public token to
     * attach to the struk. Keeps re-attaching the SAME link on every sale until the customer
     * RESPONDS or REACTS (a click alone doesn't count):
     *   • already RESPONDED/REACTED → returns {@code null} (fulfilled, stop sharing);
     *   • an unfulfilled delivery exists → returns its existing token (re-attach the same {@code /c/{token}});
     *   • none yet → mints a new token + a dirty "sent" delivery (pushed next sync so the link resolves).
     * Reusing the token means one delivery per customer (no duplicates), and a delivery another phone
     * created/whose response synced down is respected here too.
     */
    public String ensureDelivery(long campaignLocalId, long customerLocalId) {
        if (campaignLocalId <= 0 || customerLocalId <= 0) return null;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        if (isDeliveryFulfilled(db, campaignLocalId, customerLocalId)) return null;   // sudah mengisi/bereaksi
        String existing = existingToken(db, campaignLocalId, customerLocalId);
        if (existing != null) return existing;                                       // lampirkan ulang link sama
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
