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
        final String idCol;        // local primary-key column (default "_id")
        Spec(String entity, String table, String[] dataCols, Ref[] refs) {
            this(entity, table, dataCols, refs, DatabaseHelper.COL_ID);
        }
        Spec(String entity, String table, String[] dataCols, Ref[] refs, String idCol) {
            this.entity = entity; this.table = table; this.dataCols = dataCols;
            this.refs = refs; this.idCol = idCol;
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
                    DatabaseHelper.COL_FOLLOWUP_MANUAL_AT, DatabaseHelper.COL_FOLLOWUP_NOTE,
                    DatabaseHelper.COL_PRODUCT_PRICES,
                    DatabaseHelper.COL_PHOTO_URL, DatabaseHelper.COL_CREATED_AT,
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
                    DatabaseHelper.COL_PHOTO_URL,
                    DatabaseHelper.COL_EXPENSE_CREATED_AT,
            }, NO_REFS),

            new Spec("galon_stock", DatabaseHelper.TABLE_GALON_STOCK, new String[]{
                    DatabaseHelper.COL_STOCK_JUMLAH, DatabaseHelper.COL_STOCK_CATATAN,
                    DatabaseHelper.COL_STOCK_TANGGAL,
            }, NO_REFS),

            new Spec("attendance", DatabaseHelper.TABLE_ATTENDANCE, new String[]{
                    DatabaseHelper.COL_ATT_EVENT, DatabaseHelper.COL_ATT_TS,
                    DatabaseHelper.COL_PHOTO_URL,
                    DatabaseHelper.COL_ATT_LAT, DatabaseHelper.COL_ATT_LNG,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_ATT_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
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

            // salary_config is keyed locally by user_id (no _id); its sync_uuid is
            // the staff's sync_uuid (1:1). Server enforces unique(branch, staff_uuid).
            new Spec("salary_configs", DatabaseHelper.TABLE_SALARY, new String[]{
                    DatabaseHelper.COL_SAL_POKOK_ENABLED, DatabaseHelper.COL_SAL_POKOK,
                    DatabaseHelper.COL_SAL_TUNJ_HARIAN, DatabaseHelper.COL_SAL_LEMBUR_RATE,
                    DatabaseHelper.COL_SAL_ANGSURAN_SISA, DatabaseHelper.COL_SAL_ANGSURAN_BULAN,
                    DatabaseHelper.COL_SAL_JABATAN, DatabaseHelper.COL_SAL_STATUS,
                    DatabaseHelper.COL_SAL_BANK_NAME, DatabaseHelper.COL_SAL_BANK_NO,
                    DatabaseHelper.COL_SAL_BANK_HOLDER,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_SAL_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }, DatabaseHelper.COL_SAL_USER_ID),

            new Spec("transactions", DatabaseHelper.TABLE_TRANSACTIONS, new String[]{
                    DatabaseHelper.COL_TYPE, DatabaseHelper.COL_JUMLAH_GALON,
                    DatabaseHelper.COL_HARGA_PER_GALON, DatabaseHelper.COL_TOTAL_HARGA,
                    DatabaseHelper.COL_ONGKIR, DatabaseHelper.COL_ONGKIR_TYPE,
                    DatabaseHelper.COL_ITEMS_JSON, DatabaseHelper.COL_GALON_OWNERSHIP,
                    DatabaseHelper.COL_HARGA_BOTOL, DatabaseHelper.COL_PAYMENT_METHOD,
                    DatabaseHelper.COL_DELIVERY_STATUS, DatabaseHelper.COL_DELIVERY_QUEUED_AT,
                    DatabaseHelper.COL_DELIVERY_DONE_AT, DatabaseHelper.COL_DELIVERY_TOKEN,
                    DatabaseHelper.COL_CREATED_BY_NAME, DatabaseHelper.COL_CREATED_VIA,
                    DatabaseHelper.COL_COMPLETED_BY_NAME,
                    DatabaseHelper.COL_TANGGAL, DatabaseHelper.COL_CATATAN,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_TRX_PRODUCT_ID, "product_uuid", DatabaseHelper.TABLE_PRODUCTS),
                    new Ref(DatabaseHelper.COL_TRX_RESELLER_ID, "reseller_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_DELIVERY_STAFF_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),

            // After transactions so its trx_uuid ref resolves on pull.
            new Spec("order_inbox", DatabaseHelper.TABLE_ORDER_INBOX, new String[]{
                    DatabaseHelper.COL_INBOX_SENDER_NAME, DatabaseHelper.COL_INBOX_SENDER_PHONE,
                    DatabaseHelper.COL_INBOX_RAW, DatabaseHelper.COL_INBOX_PARSED_JSON,
                    DatabaseHelper.COL_INBOX_PARSER, DatabaseHelper.COL_INBOX_STATUS,
                    DatabaseHelper.COL_INBOX_REPLIED, DatabaseHelper.COL_INBOX_RECEIVED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_INBOX_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_INBOX_TRX_ID, "trx_uuid", DatabaseHelper.TABLE_TRANSACTIONS),
            }),

            // Kampanye pelanggan — authored on the web (pull-only): the phone reads title/body to
            // append to the WA struk caption and filters by target_devices. No refs.
            new Spec("campaigns", DatabaseHelper.TABLE_CAMPAIGNS, new String[]{
                    DatabaseHelper.COL_CAMP_TYPE, DatabaseHelper.COL_CAMP_TITLE,
                    DatabaseHelper.COL_CAMP_BODY, DatabaseHelper.COL_CAMP_ACTIVE,
                    DatabaseHelper.COL_CAMP_TARGETS, DatabaseHelper.COL_CREATED_AT,
            }, NO_REFS),

            // Deliveries (two-way) — created on this device when a campaign link is shared with the
            // struk (anti-rebroadcast key = campaign+customer). After campaigns so its campaign_uuid
            // ref resolves on pull; after customers for customer_uuid.
            new Spec("campaign_deliveries", DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES, new String[]{
                    DatabaseHelper.COL_CD_TOKEN, DatabaseHelper.COL_CD_SENT_AT,
                    DatabaseHelper.COL_CD_CLICKED_AT, DatabaseHelper.COL_CD_RESPONDED_AT,
                    DatabaseHelper.COL_CD_REACTION, DatabaseHelper.COL_CD_REACTED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_CD_CAMPAIGN_ID, "campaign_uuid", DatabaseHelper.TABLE_CAMPAIGNS),
                    new Ref(DatabaseHelper.COL_CD_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
            }),
    };

    public static final class Result {
        public boolean ok;
        public int pushed;
        public int pulled;
        public String error;
    }

    private final Context appCtx;
    private final DatabaseHelper dbHelper;
    private final SyncSettings cfg;
    private final SyncApi api;
    private final SettingsDao settingsDao;

    /** Key/value settings sync uses this server entity name (special path). */
    private static final String SETTINGS_ENTITY = "app_settings";

    /** Broadcast (app-internal) sent after a sync pulled changes, so open screens
     *  (e.g. the clock-in staff list) can refresh in near real-time. */
    public static final String ACTION_SYNCED = "com.crowja.damiupos.action.SYNCED";

    // Per-cycle cache: refTable -> (localId -> sync_uuid), to speed push ref lookups.
    private final Map<String, Map<Long, String>> uuidCache = new HashMap<>();

    // Follow-up MANUAL baru yang masuk lewat pull (ditambah admin di dashboard) — {nama, catatan}.
    // Diisi di applyRows, dibaca di sync() untuk memunculkan notifikasi + popup + suara.
    private final java.util.List<String[]> pulledManualFollowups = new java.util.ArrayList<>();

    // Set by push() when the server reports customers it doesn't have (referenced by pushed
    // transactions): they + their history were re-queued, so sync() pushes a second pass.
    private boolean backfillPending = false;

    public SyncEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.dbHelper = DatabaseHelper.getInstance(appCtx);
        this.settingsDao = new SettingsDao(dbHelper);
        this.cfg = new SyncSettings(settingsDao);
        this.api = new SyncApi(cfg);
    }

    public SyncSettings settings() { return cfg; }

    public Result enroll(String baseUrl, String enrollKey, String deviceName) {
        Result res = new Result();
        try {
            String deviceUuid = cfg.getDeviceUuid();
            if (deviceUuid.isEmpty()) deviceUuid = UUID.randomUUID().toString();
            // Snapshot current phone settings so the server can archive them before the
            // dashboard config takes over (recoverable from the dashboard).
            JSONObject settingsSnapshot = new JSONObject();
            for (java.util.Map.Entry<String, String> e : settingsDao.getAllShared().entrySet()) {
                if (e.getValue() != null) settingsSnapshot.put(e.getKey(), e.getValue());
            }
            JSONObject r = api.enroll(baseUrl, enrollKey, deviceUuid, deviceName,
                    BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, settingsSnapshot);
            cfg.setBaseUrl(baseUrl);
            cfg.setToken(r.getString("token"));
            cfg.setDeviceUuid(r.optString("device_uuid", deviceUuid));
            JSONObject b = r.getJSONObject("branch");
            cfg.setBranch(b.optString("code"), b.optString("uuid"), b.optString("name"));
            // MQTT broker hints in the enroll response are ignored — the app is
            // REST-only (polling). No broker credentials are stored on the device.
            cfg.setLocationIntervalSeconds(r.optInt("location_interval_seconds", 600));
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
            // Upload pending images first so freshly-stamped photo_url values ride this
            // cycle's push. Failures here are swallowed inside the uploader (retry next run).
            try { MediaUploader.uploadPending(dbHelper.getWritableDatabase(), api); }
            catch (Throwable ignored) {}
            res.pushed = push();
            // A push can reveal customers the dashboard is missing (e.g. after re-provisioning):
            // they + their full transaction history were just re-queued — push once more to seed them.
            if (backfillPending) res.pushed += push();
            pulledManualFollowups.clear();
            res.pulled = pull();
            cfg.setLastSyncAt(DatabaseHelper.nowIso());
            res.ok = true;
            // Tell open screens to refresh (e.g. clock-in staff list + badge Follow Up) when the
            // pull brought new/changed rows from the dashboard.
            if (res.pulled > 0) {
                try {
                    appCtx.sendBroadcast(new android.content.Intent(ACTION_SYNCED)
                            .setPackage(appCtx.getPackageName()));
                } catch (Throwable ignored) {}
            }
            // Follow-up MANUAL baru dari dashboard → notifikasi + popup + suara.
            for (int i = 0; i < pulledManualFollowups.size(); i++) {
                String[] f = pulledManualFollowups.get(i);
                String body = f[0] + " ditambahkan ke Follow Up dari dashboard."
                        + (f[1] != null && !f[1].isEmpty() ? " Catatan: " + f[1] : "");
                try {
                    OnlineNotifier.deliverAdminMessage(appCtx, "Follow Up Baru", body, 7910 + (i % 10));
                } catch (Throwable ignored) {}
            }
            // Kasus B (upgrade): jenis galon ganda — katalog lokal lama ber-uuid beda dari
            // katalog web. Peringatkan admin sekali (rising-edge) supaya dirapikan di dashboard.
            try {
                java.util.List<String> dupJenis =
                        new com.crowja.damiupos.db.ProductDao(dbHelper).getDuplicateJenisNames();
                if (!dupJenis.isEmpty()) {
                    if (!cfg.wasDupProductsWarned()) {
                        OnlineNotifier.deliverAdminMessage(appCtx, "Jenis Galon Ganda",
                                "Terdeteksi jenis galon ganda: "
                                        + android.text.TextUtils.join(", ", dupJenis)
                                        + ". Rapikan (hapus duplikat) dari dashboard web.", 7905);
                        cfg.setDupProductsWarned(true);
                    }
                } else if (cfg.wasDupProductsWarned()) {
                    cfg.setDupProductsWarned(false);   // sudah bersih → arm ulang
                }
            } catch (Throwable ignored) {}
        } catch (SyncApi.SyncException se) {
            res.error = se.getMessage();
            if (se.code == 401) handleRevoked();   // token deleted by dashboard "Cabut Akses"
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /** Entities the dashboard "Pull Data" imports (full upload of every row). products & staff
     *  are dashboard-managed (pull-only) but uploaded here so the dashboard can seed/align them. */
    private static final String[] EXPORT_ENTITIES = {"customers", "products", "staff", "transactions", "expenses"};

    /**
     * Full export for a dashboard "Pull Data" request ({@code pull_data} command): uploads
     * EVERY row of customers, transactions and expenses (not just dirty ones) to
     * {@code /api/sync/import}. New rows are imported server-side; rows that already exist but
     * differ become reviewable conflicts on the dashboard. Run off the main thread.
     */
    public synchronized Result fullExport() {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            return dumpAndImport(java.util.Arrays.asList(EXPORT_ENTITIES));
        } catch (SyncApi.SyncException se) {
            res.error = se.getMessage();
            if (se.code == 401) handleRevoked();
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /**
     * "Pull Settings" ({@code pull_settings} command): upload this phone's current shareable
     * settings to the dashboard, where they're archived for review & restore (mirrors the
     * enroll-time settings snapshot). Secrets are never included — only SHAREABLE_KEYS.
     */
    public synchronized Result exportSettings() {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            JSONObject settings = new JSONObject();
            for (java.util.Map.Entry<String, String> e : settingsDao.getAllShared().entrySet()) {
                if (e.getValue() != null) settings.put(e.getKey(), e.getValue());
            }
            JSONObject body = new JSONObject();
            body.put("settings", settings);
            api.uploadSettings(body);
            res.ok = true;
            res.pushed = settings.length();
        } catch (SyncApi.SyncException se) {
            res.error = se.getMessage();
            if (se.code == 401) handleRevoked();
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /**
     * Provisioning "import from device": seed the chosen categories' existing rows to the
     * server via the reviewable {@code /api/sync/import} path, then mark the ENTIRE local
     * backlog as already-synced so nothing else auto-uploads — the provisioning checklist is
     * the single source of what gets imported. Activity created after provisioning syncs
     * normally. Run off the main thread.
     */
    public synchronized Result provisioningImport(java.util.Collection<String> entityNames) {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            res = dumpAndImport(entityNames);
            if (res.ok) markBacklogSynced();
        } catch (SyncApi.SyncException se) {
            res.ok = false; res.error = se.getMessage();
            if (se.code == 401) handleRevoked();
        } catch (Exception e) {
            res.ok = false; res.error = e.getMessage();
        }
        return res;
    }

    /** Rows per import request — small enough that a big seed never exceeds the server's
     *  per-request time limit (shared hosting caps PHP execution at ~30s). */
    private static final int IMPORT_BATCH = 150;

    /** Build the import dump for the given entities (ALL rows) and POST it to
     *  {@code /api/sync/import} in batches, so a large depot (thousands of rows) never
     *  times out the request (which surfaces to the user as "500 gagal impor"). */
    private Result dumpAndImport(java.util.Collection<String> entityNames) throws Exception {
        Result res = new Result();
        uuidCache.clear();
        // Stamp pending images first so photo_url values are populated before the dump.
        try { MediaUploader.uploadPending(dbHelper.getWritableDatabase(), api); }
        catch (Throwable ignored) {}

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        JSONObject batch = new JSONObject();
        int batchCount = 0, total = 0, conflicts = 0;

        for (String name : entityNames) {
            Spec s = specFor(name);
            if (s == null) continue;
            Cursor c = db.query(s.table, null, null, null, null, null, null);   // ALL rows
            try {
                while (c.moveToNext()) {
                    String uuid = str(c, DatabaseHelper.COL_SYNC_UUID);
                    if (uuid == null) continue;
                    JSONObject row = new JSONObject();
                    row.put("uuid", uuid);
                    String edited = str(c, DatabaseHelper.COL_EDITED_AT);
                    if (edited != null) row.put("edited_at", edited);
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
                    JSONArray arr = batch.optJSONArray(name);
                    if (arr == null) { arr = new JSONArray(); batch.put(name, arr); }
                    arr.put(row);
                    batchCount++;
                    total++;
                    if (batchCount >= IMPORT_BATCH) {
                        conflicts += flushImport(batch);
                        batch = new JSONObject();
                        batchCount = 0;
                    }
                }
            } finally { c.close(); }
        }
        if (batchCount > 0) conflicts += flushImport(batch);

        res.pushed = total;
        res.pulled = conflicts;     // surface conflict count for the dashboard review
        res.ok = true;
        return res;
    }

    /** POST one import batch; returns the server's conflict count for it. */
    private int flushImport(JSONObject entities) throws Exception {
        JSONObject body = new JSONObject();
        body.put("entities", entities);
        JSONObject resp = api.importDump(body);
        return resp.optInt("conflicts", 0);
    }

    /** Mark every syncable table's dirty rows as already-synced + drop pending tombstones. */
    private void markBacklogSynced() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SYNCED, 1);
        for (Spec s : SPECS) {
            db.update(s.table, v, DatabaseHelper.COL_SYNCED + "=0", null);
        }
        db.delete(DatabaseHelper.TABLE_SYNC_TOMBSTONES, DatabaseHelper.COL_SYNCED + "=0", null);
    }

    private Spec specFor(String entity) {
        for (Spec s : SPECS) {
            if (s.entity.equals(entity)) return s;
        }
        return null;
    }

    /**
     * The server rejected our token (HTTP 401) — the dashboard revoked this device.
     * Drop enrollment so we stop retrying, stop background work, and tell the user
     * to re-enroll (provisioning).
     */
    private void handleRevoked() {
        cfg.clear();                          // forget token + disable sync
        try { SyncScheduler.cancelAll(appCtx); } catch (Throwable ignored) {}
        try { com.crowja.damiupos.LocationService.stop(appCtx); } catch (Throwable ignored) {}
        OnlineNotifier.postNotif(appCtx, "Akses dicabut",
                "Perangkat dilepas oleh admin. Daftar ulang (provisioning) untuk terhubung lagi.",
                7872);
    }

    // ------------------------------------------------------------------ PUSH

    /** Rows per push request — keeps a big backlog (e.g. thousands of freshly-imported
     *  customers + tombstones) under the server's per-request time limit (shared hosting
     *  caps PHP execution at ~30s; one giant push otherwise 500s). */
    private static final int PUSH_BATCH = 250;

    private int push() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        backfillPending = false;

        JSONObject entities = new JSONObject();
        List<String[]> sentRows = new ArrayList<>();   // {table, uuid, edited_at}
        List<String[]> sentTombs = new ArrayList<>();  // {entity, uuid}
        int batchCount = 0, total = 0;

        for (Spec s : SPECS) {
            Cursor c = db.query(s.table, null, DatabaseHelper.COL_SYNCED + "=0",
                    null, null, null, null);
            try {
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
                    appendTo(entities, s.entity, row);
                    sentRows.add(new String[]{s.table, uuid, edited});
                    total++;
                    if (++batchCount >= PUSH_BATCH) {
                        flushPush(db, entities, sentRows, sentTombs, null);
                        entities = new JSONObject();
                        sentRows = new ArrayList<>();
                        sentTombs = new ArrayList<>();
                        batchCount = 0;
                    }
                }
            } finally { c.close(); }

            Cursor t = db.query(DatabaseHelper.TABLE_SYNC_TOMBSTONES, null,
                    DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNCED + "=0",
                    new String[]{s.entity}, null, null, null);
            try {
                while (t.moveToNext()) {
                    String uuid = str(t, DatabaseHelper.COL_SYNC_UUID);
                    if (uuid == null) continue;
                    String edited = str(t, DatabaseHelper.COL_EDITED_AT);
                    JSONObject row = new JSONObject();
                    row.put("uuid", uuid);
                    row.put("edited_at", edited);
                    row.put("deleted_at", edited);
                    appendTo(entities, s.entity, row);
                    sentTombs.add(new String[]{s.entity, uuid});
                    total++;
                    if (++batchCount >= PUSH_BATCH) {
                        flushPush(db, entities, sentRows, sentTombs, null);
                        entities = new JSONObject();
                        sentRows = new ArrayList<>();
                        sentTombs = new ArrayList<>();
                        batchCount = 0;
                    }
                }
            } finally { t.close(); }
        }

        // Branch-shared settings (app_settings) — special key/value path; ride the final batch.
        List<String[]> dirtySettings = settingsDao.getDirtyShared();
        JSONArray settingsArr = new JSONArray();
        for (String[] kv : dirtySettings) {
            JSONObject o = new JSONObject();
            o.put("key", kv[0]);
            o.put("value", kv[1]);
            o.put("edited_at", kv[2]);
            settingsArr.put(o);
        }

        if (batchCount > 0 || settingsArr.length() > 0) {
            flushPush(db, entities, sentRows, sentTombs,
                    settingsArr.length() > 0 ? settingsArr : null);
            if (!dirtySettings.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (String[] kv : dirtySettings) keys.add(kv[0]);
                settingsDao.markSharedSynced(keys);
            }
        }

        return total + settingsArr.length();
    }

    /** Append a row to entities[name], creating the array on first use. */
    private static void appendTo(JSONObject entities, String name, JSONObject row) throws Exception {
        JSONArray arr = entities.optJSONArray(name);
        if (arr == null) { arr = new JSONArray(); entities.put(name, arr); }
        arr.put(row);
    }

    /** POST one push batch, then mark its rows synced / drop its tombstones / re-queue any
     *  customers the server reports missing. Throws on HTTP failure → that batch's rows
     *  stay dirty (retried next cycle); batches already flushed remain durably synced. */
    private void flushPush(SQLiteDatabase db, JSONObject entities, List<String[]> sentRows,
                           List<String[]> sentTombs, JSONArray settings) throws Exception {
        if (entities.length() == 0 && (settings == null || settings.length() == 0)) return;
        JSONObject body = new JSONObject();
        body.put("entities", entities);
        if (settings != null && settings.length() > 0) body.put("settings", settings);
        JSONObject resp = api.push(body);   // throws on failure → these rows stay dirty

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
        requeueMissingCustomers(db, resp);
    }

    /**
     * The server lists customers (referenced by transactions we just pushed) that the
     * dashboard has no row for. Re-queue each such customer AND its entire transaction
     * history as dirty so the next push seeds the full record on the dashboard.
     */
    private void requeueMissingCustomers(SQLiteDatabase db, JSONObject resp) {
        JSONObject missing = resp != null ? resp.optJSONObject("missing") : null;
        JSONArray customers = missing != null ? missing.optJSONArray("customers") : null;
        if (customers == null || customers.length() == 0) return;

        ContentValues dirty = new ContentValues();
        dirty.put(DatabaseHelper.COL_SYNCED, 0);
        for (int i = 0; i < customers.length(); i++) {
            String uuid = customers.optString(i, null);
            if (uuid == null || uuid.isEmpty()) continue;
            int n = db.update(DatabaseHelper.TABLE_CUSTOMERS, dirty,
                    DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
            if (n == 0) continue;   // customer not on this device → nothing to send
            long localId = localIdForUuid(db, DatabaseHelper.TABLE_CUSTOMERS, uuid);
            if (localId > 0) {
                db.update(DatabaseHelper.TABLE_TRANSACTIONS, dirty,
                        DatabaseHelper.COL_CUSTOMER_ID + "=?", new String[]{String.valueOf(localId)});
            }
            backfillPending = true;
        }
    }

    // ------------------------------------------------------------------ PULL

    private int pull() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // One-time recovery: re-pull staff & products from scratch so web-added rows whose insert
        // previously failed (NOT NULL pin) finally land. Runs once after this fix is installed.
        if (cfg.needsStaffRepull()) {
            cfg.setCursor("staff", "1970-01-01 00:00:00.000000");
            cfg.setCursor("products", "1970-01-01 00:00:00.000000");
            cfg.markStaffRepulled();
        }
        JSONArray names = new JSONArray();
        JSONObject cursors = new JSONObject();
        for (Spec s : SPECS) {
            names.put(s.entity);
            cursors.put(s.entity, cfg.getCursor(s.entity));
        }
        names.put(SETTINGS_ENTITY);
        cursors.put(SETTINGS_ENTITY, cfg.getCursor(SETTINGS_ENTITY));
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

        // Branch-shared settings (app_settings).
        JSONArray sa = entities != null ? entities.optJSONArray(SETTINGS_ENTITY) : null;
        if (sa != null) {
            for (int i = 0; i < sa.length(); i++) {
                JSONObject o = sa.optJSONObject(i);
                if (o == null) continue;
                String key = o.optString("key", null);
                if (key == null || key.isEmpty()) continue;
                String value = o.isNull("value") ? null : o.optString("value", null);
                String editedAt = o.optString("edited_at", "");
                boolean deleted = !o.isNull("deleted_at")
                        && !o.optString("deleted_at", "").isEmpty();
                settingsDao.applySyncedSetting(key, value, editedAt, deleted);
                applied++;
            }
            // Logo depot mungkin baru/berubah pada batch ini → unduh ke cache di background supaya
            // struk & Pengaturan langsung punya gambarnya tanpa menunggu (ensureDownloaded no-op bila URL sama).
            new Thread(() -> com.crowja.damiupos.util.DepotLogo.ensureDownloaded(appCtx)).start();
        }
        if (newCursors != null) {
            cfg.setCursor(SETTINGS_ENTITY,
                    newCursors.optString(SETTINGS_ENTITY, cfg.getCursor(SETTINGS_ENTITY)));
        }

        // Branch-authoritative gallon stock from the server → cached and shown verbatim on this
        // device, so its "Stok Galon Tersedia" is ALWAYS identical to the dashboard (per-device
        // transaction isolation can otherwise leave the local keluar/kembali behind).
        JSONObject sg = resp.optJSONObject("stok_galon");
        if (sg != null) {
            cfg.setStokGalon(sg.optInt("masuk", 0), sg.optInt("keluar", 0),
                    sg.optInt("kembali", 0), sg.optInt("tersedia", 0));
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
                    new String[]{s.idCol, DatabaseHelper.COL_SYNCED, DatabaseHelper.COL_EDITED_AT},
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
            // A staff (users) row created on the web carries no PIN — it's never synced. The local
            // pin column is NOT NULL, so without this the INSERT fails silently and the karyawan
            // never appears. Seed an empty PIN on first insert; the staff sets it on first login.
            // Updates leave pin untouched, so an existing staff keeps theirs.
            if (localId == -1 && DatabaseHelper.TABLE_USERS.equals(s.table)
                    && ! v.containsKey(DatabaseHelper.COL_USER_PIN)) {
                v.put(DatabaseHelper.COL_USER_PIN, "");
            }
            // Follow-up MANUAL baru dari dashboard: customer yang BARU ditandai
            // (followup_manual_at terisi sekarang, sebelumnya kosong) → kumpulkan untuk notif.
            if ("customers".equals(s.entity)) {
                String inManual = obj.isNull(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT) ? null
                        : obj.optString(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT, null);
                if (inManual != null && !inManual.isEmpty()) {
                    String localManual = null;
                    if (localId != -1) {
                        Cursor mc = db.query(s.table, new String[]{DatabaseHelper.COL_FOLLOWUP_MANUAL_AT},
                                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
                        if (mc.moveToFirst()) localManual = mc.getString(0);
                        mc.close();
                    }
                    if (localManual == null || localManual.isEmpty()) {
                        pulledManualFollowups.add(new String[]{
                                obj.optString(DatabaseHelper.COL_NAME, "Pelanggan"),
                                obj.isNull(DatabaseHelper.COL_FOLLOWUP_NOTE) ? ""
                                        : obj.optString(DatabaseHelper.COL_FOLLOWUP_NOTE, "")});
                    }
                }
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
