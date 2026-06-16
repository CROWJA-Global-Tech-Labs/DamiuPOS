package com.crowja.damiupos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offline-first two-way sync. Local SQLite is the source of truth; this pushes dirty
 * rows + tombstones, then pulls deltas and applies them (last-write-wins on edited_at).
 *
 * <p>Cross-row references travel as *_uuid: a transaction's local {@code customer_id}
 * is translated to the customer's {@code sync_uuid} on push, and back to the local id
 * on pull. Specs are ordered so referenced entities are applied before referencing ones.
 */
public class SyncEngine {

    /** A foreign key carried across the wire as a uuid. */
    private static final class Ref {
        final String localCol;     // e.g. "customer_id"
        final String serverField;  // e.g. "customer_uuid"
        final String refTable;     // e.g. customers
        Ref(String localCol, String serverField, String refTable) {
            this.localCol = localCol; this.serverField = serverField; this.refTable = refTable;
        }
    }

    private static final class Spec {
        final String entity;
        final String table;
        final String[] dataCols;   // scalar columns (identical name phone & server)
        final Ref[] refs;
        Spec(String entity, String table, String[] dataCols, Ref[] refs) {
            this.entity = entity; this.table = table; this.dataCols = dataCols; this.refs = refs;
        }
    }

    private static final Ref[] NO_REFS = new Ref[0];

    // Dependency order: leaf entities first so refs resolve on pull.
    private static final Spec[] SPECS = {
            new Spec("customers", DatabaseHelper.TABLE_CUSTOMERS, new String[]{
                    DatabaseHelper.COL_NAME, DatabaseHelper.COL_PHONE, DatabaseHelper.COL_ADDRESS,
                    DatabaseHelper.COL_LATITUDE, DatabaseHelper.COL_LONGITUDE,
                    DatabaseHelper.COL_IS_RESELLER, DatabaseHelper.COL_RESELLER_SINCE,
                    DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT,
                    DatabaseHelper.COL_FOLLOWUP_EXCLUDE_REASON, DatabaseHelper.COL_LAST_FOLLOWUP_AT,
                    DatabaseHelper.COL_CREATED_AT,
            }, NO_REFS),

            new Spec("products", DatabaseHelper.TABLE_PRODUCTS, new String[]{
                    DatabaseHelper.COL_PRODUCT_NAME, DatabaseHelper.COL_HARGA_JUAL,
                    DatabaseHelper.COL_HARGA_MODAL, DatabaseHelper.COL_COLOR,
                    DatabaseHelper.COL_CREATED_AT,
            }, NO_REFS),

            // Staff (PIN operators). pin is NOT synced — server pin_hash stays null;
            // cross-device PIN auth is a later, security-reviewed change.
            new Spec("staff", DatabaseHelper.TABLE_USERS, new String[]{
                    DatabaseHelper.COL_USER_NAME, DatabaseHelper.COL_USER_ROLE,
                    DatabaseHelper.COL_USER_ACTIVE, DatabaseHelper.COL_USER_CREATED_AT,
            }, NO_REFS),

            new Spec("expenses", DatabaseHelper.TABLE_EXPENSES, new String[]{
                    DatabaseHelper.COL_EXPENSE_NAME, DatabaseHelper.COL_EXPENSE_AMOUNT,
                    DatabaseHelper.COL_EXPENSE_NOTE, DatabaseHelper.COL_EXPENSE_CATEGORY,
                    DatabaseHelper.COL_EXPENSE_LITERS, DatabaseHelper.COL_EXPENSE_PCS,
                    DatabaseHelper.COL_EXPENSE_CREATED_AT,
            }, NO_REFS),

            new Spec("galon_stock", DatabaseHelper.TABLE_GALON_STOCK, new String[]{
                    DatabaseHelper.COL_STOCK_JUMLAH, DatabaseHelper.COL_STOCK_CATATAN,
                    DatabaseHelper.COL_STOCK_TANGGAL,
            }, NO_REFS),

            new Spec("attendance", DatabaseHelper.TABLE_ATTENDANCE, new String[]{
                    DatabaseHelper.COL_ATT_EVENT, DatabaseHelper.COL_ATT_TS,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_ATT_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),

            new Spec("pending_transactions", DatabaseHelper.TABLE_PENDING_TRX, new String[]{
                    DatabaseHelper.COL_PT_CUSTOMER_NAME, DatabaseHelper.COL_PT_CUSTOMER_PHONE,
                    DatabaseHelper.COL_PT_NOTE, DatabaseHelper.COL_PT_CREATED_BY_NAME,
                    DatabaseHelper.COL_PT_STATUS, DatabaseHelper.COL_PT_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_PT_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_PT_CREATED_BY, "created_by_uuid", DatabaseHelper.TABLE_USERS),
            }),

            new Spec("reseller_rates", DatabaseHelper.TABLE_RESELLER_RATES, new String[]{
                    DatabaseHelper.COL_RR_KOMISI,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_RR_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_RR_PRODUCT_ID, "product_uuid", DatabaseHelper.TABLE_PRODUCTS),
            }),

            new Spec("reseller_withdrawals", DatabaseHelper.TABLE_RESELLER_WD, new String[]{
                    DatabaseHelper.COL_WD_TYPE, DatabaseHelper.COL_WD_GALON_QTY,
                    DatabaseHelper.COL_WD_AMOUNT, DatabaseHelper.COL_WD_NOTE,
                    DatabaseHelper.COL_WD_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_WD_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_WD_EXPENSE_ID, "expense_uuid", DatabaseHelper.TABLE_EXPENSES),
            }),

            new Spec("salary_items", DatabaseHelper.TABLE_SALARY_ITEMS, new String[]{
                    DatabaseHelper.COL_SI_KIND, DatabaseHelper.COL_SI_LABEL,
                    DatabaseHelper.COL_SI_QTY, DatabaseHelper.COL_SI_RATE,
                    DatabaseHelper.COL_SI_SORT, DatabaseHelper.COL_SI_ARCHIVED,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_SI_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),

            new Spec("transactions", DatabaseHelper.TABLE_TRANSACTIONS, new String[]{
                    DatabaseHelper.COL_TYPE, DatabaseHelper.COL_JUMLAH_GALON,
                    DatabaseHelper.COL_HARGA_PER_GALON, DatabaseHelper.COL_TOTAL_HARGA,
                    DatabaseHelper.COL_ONGKIR, DatabaseHelper.COL_ONGKIR_TYPE,
                    DatabaseHelper.COL_ITEMS_JSON, DatabaseHelper.COL_GALON_OWNERSHIP,
                    DatabaseHelper.COL_HARGA_BOTOL, DatabaseHelper.COL_PAYMENT_METHOD,
                    DatabaseHelper.COL_TANGGAL, DatabaseHelper.COL_CATATAN,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_TRX_PRODUCT_ID, "product_uuid", DatabaseHelper.TABLE_PRODUCTS),
                    new Ref(DatabaseHelper.COL_TRX_RESELLER_ID, "reseller_uuid", DatabaseHelper.TABLE_CUSTOMERS),
            }),
    };

    public static final class Result {
        public boolean ok;
        public int pushed;
        public int pulled;
        public String error;
    }

    private final DatabaseHelper dbHelper;
    private final SyncSettings cfg;
    private final SyncApi api;

    // Per-cycle cache: refTable -> (localId -> sync_uuid), to speed push ref lookups.
    private final Map<String, Map<Long, String>> uuidCache = new HashMap<>();

    public SyncEngine(Context ctx) {
        this.dbHelper = DatabaseHelper.getInstance(ctx);
        this.cfg = new SyncSettings(new SettingsDao(dbHelper));
        this.api = new SyncApi(cfg);
    }

    public SyncSettings settings() { return cfg; }

    public Result enroll(String baseUrl, String enrollKey, String deviceName) {
        Result res = new Result();
        try {
            String deviceUuid = cfg.getDeviceUuid();
            if (deviceUuid.isEmpty()) deviceUuid = UUID.randomUUID().toString();
            JSONObject r = api.enroll(baseUrl, enrollKey, deviceUuid, deviceName,
                    BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME);
            cfg.setBaseUrl(baseUrl);
            cfg.setToken(r.getString("token"));
            cfg.setDeviceUuid(r.optString("device_uuid", deviceUuid));
            JSONObject b = r.getJSONObject("branch");
            cfg.setBranch(b.optString("code"), b.optString("uuid"), b.optString("name"));
            JSONObject m = r.optJSONObject("mqtt");
            if (m != null) {
                cfg.setMqtt(m.optString("host"), m.optInt("port", 8883),
                        m.optBoolean("tls", true), m.optString("username"),
                        m.optString("password"), m.optString("prefix", "damiupos"));
            }
            cfg.setEnabled(true);
            res.ok = true;
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    public synchronized Result sync() {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            uuidCache.clear();
            res.pushed = push();
            res.pulled = pull();
            cfg.setLastSyncAt(DatabaseHelper.nowIso());
            res.ok = true;
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    // ------------------------------------------------------------------ PUSH

    private int push() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        JSONObject entities = new JSONObject();
        List<String[]> sentRows = new ArrayList<>();   // {table, uuid, edited_at}
        List<String[]> sentTombs = new ArrayList<>();  // {entity, uuid}
        int total = 0;

        for (Spec s : SPECS) {
            JSONArray arr = new JSONArray();

            Cursor c = db.query(s.table, null, DatabaseHelper.COL_SYNCED + "=0",
                    null, null, null, null);
            while (c.moveToNext()) {
                String uuid = str(c, DatabaseHelper.COL_SYNC_UUID);
                if (uuid == null) continue;
                String edited = str(c, DatabaseHelper.COL_EDITED_AT);
                JSONObject row = new JSONObject();
                row.put("uuid", uuid);
                row.put("edited_at", edited);
                for (String col : s.dataCols) {
                    String v = str(c, col);
                    if (v != null) row.put(col, v);
                }
                for (Ref ref : s.refs) {
                    long localId = getLong(c, ref.localCol);
                    if (localId > 0) {
                        String refUuid = uuidForLocalId(db, ref.refTable, localId);
                        if (refUuid != null) row.put(ref.serverField, refUuid);
                    }
                }
                arr.put(row);
                sentRows.add(new String[]{s.table, uuid, edited});
            }
            c.close();

            Cursor t = db.query(DatabaseHelper.TABLE_SYNC_TOMBSTONES, null,
                    DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNCED + "=0",
                    new String[]{s.entity}, null, null, null);
            while (t.moveToNext()) {
                String uuid = str(t, DatabaseHelper.COL_SYNC_UUID);
                if (uuid == null) continue;
                String edited = str(t, DatabaseHelper.COL_EDITED_AT);
                JSONObject row = new JSONObject();
                row.put("uuid", uuid);
                row.put("edited_at", edited);
                row.put("deleted_at", edited);
                arr.put(row);
                sentTombs.add(new String[]{s.entity, uuid});
            }
            t.close();

            if (arr.length() > 0) entities.put(s.entity, arr);
            total += arr.length();
        }

        if (total == 0) return 0;

        JSONObject body = new JSONObject();
        body.put("entities", entities);
        api.push(body);   // throws on failure → keep dirty, retry next cycle

        for (String[] row : sentRows) {
            db.execSQL("UPDATE " + row[0] + " SET " + DatabaseHelper.COL_SYNCED + "=1"
                    + " WHERE " + DatabaseHelper.COL_SYNC_UUID + "=? AND "
                    + DatabaseHelper.COL_EDITED_AT + "=?", new Object[]{row[1], row[2]});
        }
        for (String[] tomb : sentTombs) {
            db.delete(DatabaseHelper.TABLE_SYNC_TOMBSTONES,
                    DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNC_UUID + "=?",
                    new String[]{tomb[0], tomb[1]});
        }
        return total;
    }

    // ------------------------------------------------------------------ PULL

    private int pull() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        JSONArray names = new JSONArray();
        JSONObject cursors = new JSONObject();
        for (Spec s : SPECS) {
            names.put(s.entity);
            cursors.put(s.entity, cfg.getCursor(s.entity));
        }
        JSONObject body = new JSONObject();
        body.put("entities", names);
        body.put("cursors", cursors);

        JSONObject resp = api.pull(body);
        JSONObject entities = resp.optJSONObject("entities");
        JSONObject newCursors = resp.optJSONObject("cursors");
        int applied = 0;

        for (Spec s : SPECS) {
            JSONArray arr = entities != null ? entities.optJSONArray(s.entity) : null;
            if (arr != null) applied += applyRows(db, s, arr);
            if (newCursors != null) {
                cfg.setCursor(s.entity, newCursors.optString(s.entity, cfg.getCursor(s.entity)));
            }
        }
        return applied;
    }

    private int applyRows(SQLiteDatabase db, Spec s, JSONArray arr) {
        int applied = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String uuid = obj.optString("uuid", null);
            if (uuid == null || uuid.isEmpty()) continue;
            String editedAt = obj.optString("edited_at", "");
            String deletedAt = obj.isNull("deleted_at") ? null : obj.optString("deleted_at", null);

            long localId = -1; int localSynced = 1; String localEdited = "";
            Cursor c = db.query(s.table,
                    new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_SYNCED, DatabaseHelper.COL_EDITED_AT},
                    DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
            if (c.moveToFirst()) {
                localId = c.getLong(0);
                localSynced = c.getInt(1);
                localEdited = c.getString(2);
            }
            c.close();

            if (deletedAt != null && !deletedAt.isEmpty()) {
                if (localId != -1) {
                    db.delete(s.table, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
                }
                db.delete(DatabaseHelper.TABLE_SYNC_TOMBSTONES,
                        DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNC_UUID + "=?",
                        new String[]{s.entity, uuid});
                applied++;
                continue;
            }

            if (localId != -1 && localSynced == 0 && localEdited != null
                    && localEdited.compareTo(editedAt) > 0) {
                continue;   // don't clobber a newer un-pushed local edit
            }

            ContentValues v = new ContentValues();
            v.put(DatabaseHelper.COL_SYNC_UUID, uuid);
            v.put(DatabaseHelper.COL_EDITED_AT, editedAt);
            v.put(DatabaseHelper.COL_SYNCED, 1);
            for (String col : s.dataCols) {
                putTyped(v, col, obj.opt(col));
            }
            for (Ref ref : s.refs) {
                String refUuid = obj.isNull(ref.serverField) ? null : obj.optString(ref.serverField, null);
                long refLocal = (refUuid != null && !refUuid.isEmpty())
                        ? localIdForUuid(db, ref.refTable, refUuid) : 0;
                v.put(ref.localCol, refLocal);
            }
            if (localId != -1) {
                db.update(s.table, v, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
            } else {
                db.insert(s.table, null, v);
            }
            applied++;
        }
        return applied;
    }

    // --------------------------------------------------------------- helpers

    private String uuidForLocalId(SQLiteDatabase db, String table, long localId) {
        Map<Long, String> cache = uuidCache.get(table);
        if (cache == null) { cache = new HashMap<>(); uuidCache.put(table, cache); }
        if (cache.containsKey(localId)) return cache.get(localId);
        String uuid = null;
        Cursor c = db.query(table, new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(localId)}, null, null, null);
        if (c.moveToFirst()) uuid = c.getString(0);
        c.close();
        cache.put(localId, uuid);
        return uuid;
    }

    private long localIdForUuid(SQLiteDatabase db, String table, String uuid) {
        long id = 0;
        Cursor c = db.query(table, new String[]{DatabaseHelper.COL_ID},
                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    private static void putTyped(ContentValues v, String col, Object val) {
        if (val == null || val == JSONObject.NULL) v.putNull(col);
        else if (val instanceof Boolean) v.put(col, ((Boolean) val) ? 1 : 0);
        else if (val instanceof Integer) v.put(col, (Integer) val);
        else if (val instanceof Long) v.put(col, (Long) val);
        else if (val instanceof Double) v.put(col, (Double) val);
        else v.put(col, val.toString());
    }

    private static String str(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 ? c.getString(idx) : null;
    }

    private static long getLong(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 && !c.isNull(idx) ? c.getLong(idx) : 0;
    }
}
