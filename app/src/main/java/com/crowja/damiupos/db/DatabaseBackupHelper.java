package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Helpers to copy the SQLite database file out for backup and to swap
 * it back in for restore. The on-disk file is the entire app state, so
 * an export is a full snapshot and a full import is a full overwrite.
 *
 * <p>Restore offers two modes:
 * <ul>
 *   <li>{@link #importFrom} — replace the whole database with the backup.</li>
 *   <li>{@link #importTransactionsForExistingCustomers} — merge: only add the
 *       backup's transactions whose customer already exists in the current
 *       database (matched by sync_uuid, then phone, then name). Nothing else is
 *       touched. Useful to pull old history back in without losing current data.</li>
 * </ul>
 *
 * <p>A full restore closes the {@link DatabaseHelper} singleton via
 * {@link DatabaseHelper#resetInstance()} so the next DAO call reopens the new
 * file. The merge keeps the live database open and inserts into it.
 */
public final class DatabaseBackupHelper {

    public static final String BACKUP_FILE_PREFIX = "DAMIU-POS-backup-";
    public static final String BACKUP_FILE_EXT = ".db";
    public static final String BACKUP_MIME = "application/octet-stream";
    private static final String TAG = "DAMIU_RESTORE";

    /**
     * Helper table embedded in the exported db that carries each customer "Foto Rumah" image as a
     * BLOB (name = photo file basename, data = bytes). Photos live OUTSIDE the db (customers only
     * store their path), so a plain db backup loses them; bundling them as a table keeps the backup a
     * single VALID SQLite .db — so the web "Import dari perangkat" (CustomerImportController /
     * StaffImportController, which read it as SQLite) still works and just ignores this extra table.
     * Restore writes the blobs back to image files, then drops the table so the live db isn't bloated.
     */
    private static final String TABLE_BACKUP_MEDIA = "_backup_media";

    private DatabaseBackupHelper() {}

    /** Generate a filename like "DAMIU-POS-backup-20260424_153012.db". */
    public static String defaultBackupFileName() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return BACKUP_FILE_PREFIX + ts + BACKUP_FILE_EXT;
    }

    /**
     * Write a full backup to {@code out}: a self-contained SQLite .db that, beyond the normal tables,
     * carries each customer "Foto Rumah" image as a BLOB in {@link #TABLE_BACKUP_MEDIA}. Photos live
     * OUTSIDE the db (customers only store their path), so a plain db backup loses them. The bytes are
     * embedded in a STAGING COPY so the live db is never polluted. Caller closes the OutputStream.
     */
    public static void exportTo(Context ctx, OutputStream out) throws IOException {
        // Force any pending WAL pages back into the main db file so the copy is self-contained.
        try {
            SQLiteDatabase db = DatabaseHelper.getInstance(ctx).getWritableDatabase();
            db.execSQL("PRAGMA wal_checkpoint(FULL);");
        } catch (SQLiteException ignored) {
            // Not in WAL mode → nothing to do
        }
        File src = ctx.getDatabasePath(DatabaseHelper.getDatabaseFileName());
        File staged = new File(src.getParentFile(), DatabaseHelper.getDatabaseFileName() + ".export-tmp");
        deleteDbWithSidecars(staged);
        try {
            copyFile(src, staged);
            embedCustomerPhotos(ctx, staged);   // adds TABLE_BACKUP_MEDIA blobs to the COPY
            try (FileInputStream in = new FileInputStream(staged)) {
                copyStream(in, out);
            }
        } finally {
            deleteDbWithSidecars(staged);
        }
    }

    /** Copy the customers' "Foto Rumah" image files into {@code dbFile} as {@link #TABLE_BACKUP_MEDIA} blobs. */
    private static void embedCustomerPhotos(Context ctx, File dbFile) {
        List<File> photos = collectCustomerPhotoFiles(ctx);
        if (photos.isEmpty()) return;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BACKUP_MEDIA + " (name TEXT PRIMARY KEY, data BLOB)");
            db.execSQL("DELETE FROM " + TABLE_BACKUP_MEDIA);
            db.beginTransaction();
            try {
                Set<String> added = new HashSet<>();
                for (File photo : photos) {
                    String name = photo.getName();
                    if (!added.add(name)) continue;     // app names photos uniquely
                    byte[] bytes = readAllBytes(photo);
                    if (bytes == null) continue;
                    ContentValues cv = new ContentValues();
                    cv.put("name", name);
                    cv.put("data", bytes);
                    db.insertWithOnConflict(TABLE_BACKUP_MEDIA, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            try { db.execSQL("PRAGMA wal_checkpoint(FULL);"); } catch (SQLiteException ignored) {}
        } catch (SQLiteException e) {
            Log.w(TAG, "embedCustomerPhotos failed", e);
        } finally {
            if (db != null) { try { db.close(); } catch (Exception ignored) {} }
        }
    }

    /** Existing files referenced by {@code customers.photo_path} (locally captured "Foto Rumah"). */
    private static List<File> collectCustomerPhotoFiles(Context ctx) {
        List<File> files = new ArrayList<>();
        try {
            SQLiteDatabase db = DatabaseHelper.getInstance(ctx).getReadableDatabase();
            try (Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                    new String[]{DatabaseHelper.COL_PHOTO_PATH},
                    DatabaseHelper.COL_PHOTO_PATH + " IS NOT NULL AND "
                            + DatabaseHelper.COL_PHOTO_PATH + " <> ''",
                    null, null, null, null)) {
                while (c.moveToNext()) {
                    String p = c.getString(0);
                    if (p == null || p.trim().isEmpty()) continue;
                    File f = new File(p);
                    if (f.exists() && f.isFile()) files.add(f);
                }
            }
        } catch (SQLiteException ignored) {
            // No customers table / unreadable → no photos to bundle.
        }
        return files;
    }

    /**
     * Restore any {@link #TABLE_BACKUP_MEDIA} blobs in {@code dbFile} back to image files under
     * getExternalFilesDir(Pictures)/{basename} — the exact path {@code customers.photo_path} points to,
     * so restored rows find their photo again — then DROP the table so it doesn't bloat / re-export.
     *
     * <p>Backward compatibility: older backups predate the photo table. They are detected with a
     * READ-ONLY probe and left completely untouched (never opened read-write, never modified), so a
     * legacy .db restores exactly as before — just without photos. Tolerant of errors: best-effort.
     */
    private static void restoreAndStripMedia(Context ctx, File dbFile) {
        if (!hasTable(dbFile, TABLE_BACKUP_MEDIA)) {
            return;   // legacy backup without bundled photos → leave the file untouched
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            File picturesDir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (picturesDir != null && !picturesDir.exists()) picturesDir.mkdirs();
            if (picturesDir != null) {
                try (Cursor c = db.query(TABLE_BACKUP_MEDIA, new String[]{"name", "data"},
                        null, null, null, null, null)) {
                    while (c.moveToNext()) {
                        String name = c.getString(0);
                        byte[] data = c.getBlob(1);
                        if (name == null || data == null) continue;
                        String base = new File(name).getName();   // guards path traversal
                        if (base.isEmpty()) continue;
                        try (FileOutputStream fos = new FileOutputStream(new File(picturesDir, base))) {
                            fos.write(data);
                        } catch (IOException ioe) {
                            Log.w(TAG, "restore photo failed: " + base, ioe);
                        }
                    }
                }
            }
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_BACKUP_MEDIA);
            // Collapse WAL into the main file so a subsequent full-restore file swap keeps the drop.
            try { db.execSQL("PRAGMA wal_checkpoint(FULL);"); } catch (SQLiteException ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "restoreAndStripMedia failed", e);
        } finally {
            if (db != null) { try { db.close(); } catch (Exception ignored) {} }
        }
    }

    /** READ-ONLY probe: does {@code dbFile} contain a table named {@code table}? False on any error
     *  (e.g. legacy backup). Never opens the file read-write, so legacy backups stay byte-untouched. */
    private static boolean hasTable(File dbFile, String table) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{table})) {
                return c.moveToFirst();
            }
        } catch (SQLiteException e) {
            return false;
        } finally {
            if (db != null) { try { db.close(); } catch (Exception ignored) {} }
        }
    }

    private static byte[] readAllBytes(File f) {
        try (FileInputStream in = new FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            copyStream(in, bos);
            return bos.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "read photo failed: " + f.getName(), e);
            return null;
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            copyStream(in, out);
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void deleteDbWithSidecars(File db) {
        deleteIfExists(db);
        deleteIfExists(new File(db.getAbsolutePath() + "-journal"));
        deleteIfExists(new File(db.getAbsolutePath() + "-wal"));
        deleteIfExists(new File(db.getAbsolutePath() + "-shm"));
    }

    /**
     * Replace the live SQLite db file with the contents read from {@code in}.
     * Closes the cached DAO instance so subsequent DB calls reopen against
     * the new file.
     */
    public static void importFrom(Context ctx, InputStream in)
            throws IOException, InvalidBackupException {
        File dbFile = ctx.getDatabasePath(DatabaseHelper.getDatabaseFileName());
        File tmp = drainToValidatedTemp(ctx, in, ".import-tmp");

        // Close the live DB so we can overwrite the file safely.
        DatabaseHelper.resetInstance();

        // Wipe sidecar files (journal/WAL) of the OLD db.
        deleteIfExists(new File(dbFile.getAbsolutePath() + "-journal"));
        deleteIfExists(new File(dbFile.getAbsolutePath() + "-wal"));
        deleteIfExists(new File(dbFile.getAbsolutePath() + "-shm"));

        // Replace the file (rename if possible, copy as fallback).
        if (dbFile.exists() && !dbFile.delete()) {
            tmp.delete();
            throw new IOException("Gagal menghapus database lama");
        }
        if (!tmp.renameTo(dbFile)) {
            try (FileInputStream fin = new FileInputStream(tmp);
                 FileOutputStream fout = new FileOutputStream(dbFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fin.read(buf)) > 0) {
                    fout.write(buf, 0, n);
                }
                fout.flush();
            }
            tmp.delete();
        }

        // Reopen — triggers onUpgrade() if the backup was from an older schema.
        DatabaseHelper.getInstance(ctx).getWritableDatabase();
    }

    /**
     * Merge-restore: add the backup's transactions for customers that already
     * exist in the current database. Nothing else (customers, products, settings,
     * users…) is changed. Customers/products are matched by sync_uuid, then phone
     * (customers) / name. Local FK ids are remapped to the current rows; each
     * imported transaction is marked dirty so it syncs. Duplicates (same sync_uuid
     * already present) are skipped.
     */
    public static ImportResult importTransactionsForExistingCustomers(Context ctx, InputStream in)
            throws IOException, InvalidBackupException {
        File tmp = drainToValidatedTemp(ctx, in, ".merge-tmp");
        SQLiteDatabase backup = null;
        ImportResult res = new ImportResult();
        try {
            backup = SQLiteDatabase.openDatabase(
                    tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            SQLiteDatabase live = DatabaseHelper.getInstance(ctx).getWritableDatabase();

            Map<Long, Long> custMap = buildCustomerMap(live, backup);
            Map<Long, Long> prodMap = buildProductMap(live, backup);
            Set<String> existingTrx = collectTrxUuids(live);

            Cursor bt = backup.query(DatabaseHelper.TABLE_TRANSACTIONS,
                    null, null, null, null, null, null);
            int iCust = bt.getColumnIndex(DatabaseHelper.COL_CUSTOMER_ID);
            int iProd = bt.getColumnIndex(DatabaseHelper.COL_TRX_PRODUCT_ID);
            int iRes = bt.getColumnIndex(DatabaseHelper.COL_TRX_RESELLER_ID);
            int iUuid = bt.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            int iEdited = bt.getColumnIndex(DatabaseHelper.COL_EDITED_AT);
            live.beginTransaction();
            try {
                while (bt.moveToNext()) {
                    long bCust = iCust >= 0 && !bt.isNull(iCust) ? bt.getLong(iCust) : 0;
                    Long curCust = custMap.get(bCust);
                    if (curCust == null) { res.skippedNoCustomer++; continue; }

                    String uuid = (iUuid >= 0 && !bt.isNull(iUuid)) ? bt.getString(iUuid) : null;
                    if (uuid != null && existingTrx.contains(uuid)) { res.skippedDuplicate++; continue; }

                    ContentValues cv = new ContentValues();
                    DatabaseUtils.cursorRowToContentValues(bt, cv);
                    cv.remove(DatabaseHelper.COL_ID);
                    cv.put(DatabaseHelper.COL_CUSTOMER_ID, curCust);

                    long bProd = iProd >= 0 && !bt.isNull(iProd) ? bt.getLong(iProd) : 0;
                    Long curProd = bProd > 0 ? prodMap.get(bProd) : null;
                    cv.put(DatabaseHelper.COL_TRX_PRODUCT_ID, curProd != null ? curProd : 0);

                    long bRes = iRes >= 0 && !bt.isNull(iRes) ? bt.getLong(iRes) : 0;
                    Long curRes = bRes > 0 ? custMap.get(bRes) : null;
                    cv.put(DatabaseHelper.COL_TRX_RESELLER_ID, curRes != null ? curRes : 0);

                    if (uuid == null || uuid.isEmpty()) uuid = UUID.randomUUID().toString();
                    cv.put(DatabaseHelper.COL_SYNC_UUID, uuid);
                    String edited = iEdited >= 0 && !bt.isNull(iEdited) ? bt.getString(iEdited) : null;
                    cv.put(DatabaseHelper.COL_EDITED_AT,
                            edited != null && !edited.isEmpty() ? edited : DatabaseHelper.nowIso());
                    cv.put(DatabaseHelper.COL_SYNCED, 0);

                    live.insert(DatabaseHelper.TABLE_TRANSACTIONS, null, cv);
                    existingTrx.add(uuid);
                    res.imported++;
                }
                live.setTransactionSuccessful();
            } finally {
                live.endTransaction();
                bt.close();
            }
        } catch (SQLiteException e) {
            throw new InvalidBackupException("Gagal membaca backup: " + e.getMessage());
        } finally {
            if (backup != null) { try { backup.close(); } catch (Exception ignored) {} }
            tmp.delete();
        }
        return res;
    }

    /**
     * Drain + validate a backup to a temp file and return it. The caller MUST delete
     * the file when done (after listing customers and/or importing). Used by the
     * "pick customers" restore flow, which reads the same staged file twice.
     */
    public static File stageBackup(Context ctx, InputStream in)
            throws IOException, InvalidBackupException {
        return drainToValidatedTemp(ctx, in, ".pick-tmp");
    }

    /** List the customers in a staged backup file (name-sorted) for the picker UI. */
    public static java.util.List<BackupCustomer> listCustomers(File backupFile)
            throws InvalidBackupException {
        java.util.List<BackupCustomer> list = new java.util.ArrayList<>();
        SQLiteDatabase backup = null;
        try {
            backup = SQLiteDatabase.openDatabase(
                    backupFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

            // Which customers have ≥1 transaction (one pass over transactions).
            Set<Long> withTrx = new HashSet<>();
            try (Cursor tc = backup.rawQuery("SELECT DISTINCT " + DatabaseHelper.COL_CUSTOMER_ID
                    + " FROM " + DatabaseHelper.TABLE_TRANSACTIONS, null)) {
                while (tc.moveToNext()) withTrx.add(tc.getLong(0));
            } catch (Exception ignored) {}

            Cursor c = backup.query(DatabaseHelper.TABLE_CUSTOMERS,
                    null, null, null, null, null, DatabaseHelper.COL_NAME + " COLLATE NOCASE ASC");
            try {
                int iId = c.getColumnIndex(DatabaseHelper.COL_ID);
                int iName = c.getColumnIndex(DatabaseHelper.COL_NAME);
                int iPhone = c.getColumnIndex(DatabaseHelper.COL_PHONE);
                int iLat = c.getColumnIndex(DatabaseHelper.COL_LATITUDE);
                int iLng = c.getColumnIndex(DatabaseHelper.COL_LONGITUDE);
                int iPhotoPath = c.getColumnIndex(DatabaseHelper.COL_PHOTO_PATH);
                int iPhotoUrl = c.getColumnIndex(DatabaseHelper.COL_PHOTO_URL);
                while (c.moveToNext()) {
                    BackupCustomer bc = new BackupCustomer();
                    bc.id = c.getLong(iId);
                    bc.name = iName >= 0 ? c.getString(iName) : "";
                    bc.phone = iPhone >= 0 ? c.getString(iPhone) : "";
                    bc.hasTransactions = withTrx.contains(bc.id);
                    double lat = iLat >= 0 && !c.isNull(iLat) ? c.getDouble(iLat) : 0;
                    double lng = iLng >= 0 && !c.isNull(iLng) ? c.getDouble(iLng) : 0;
                    bc.hasGeo = lat != 0 || lng != 0;
                    String pp = iPhotoPath >= 0 ? c.getString(iPhotoPath) : null;
                    String pu = iPhotoUrl >= 0 ? c.getString(iPhotoUrl) : null;
                    bc.hasPhoto = (pp != null && !pp.trim().isEmpty())
                            || (pu != null && !pu.trim().isEmpty());
                    list.add(bc);
                }
            } finally { c.close(); }
        } catch (SQLiteException e) {
            throw new InvalidBackupException("Gagal membaca daftar pelanggan: " + e.getMessage());
        } finally {
            if (backup != null) { try { backup.close(); } catch (Exception ignored) {} }
        }
        return list;
    }

    /**
     * Restore the chosen customers (by their backup row id) from a staged backup:
     * insert each one that isn't already present (matched by sync_uuid/phone/name),
     * then import all of their transactions. Existing customers are not overwritten.
     */
    public static ImportResult importSelectedCustomers(Context ctx, File backupFile,
                                                       Set<Long> selectedBackupIds)
            throws InvalidBackupException {
        SQLiteDatabase backup = null;
        ImportResult res = new ImportResult();
        if (selectedBackupIds == null || selectedBackupIds.isEmpty()) return res;
        try {
            backup = SQLiteDatabase.openDatabase(
                    backupFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            SQLiteDatabase live = DatabaseHelper.getInstance(ctx).getWritableDatabase();

            // Current customer lookups for matching.
            Map<String, Long> curByUuid = new HashMap<>();
            Map<String, Long> curByPhone = new HashMap<>();
            Map<String, Long> curByName = new HashMap<>();
            Cursor cc = live.query(DatabaseHelper.TABLE_CUSTOMERS, null, null, null, null, null, null);
            try {
                int iId = cc.getColumnIndex(DatabaseHelper.COL_ID);
                int iUuid = cc.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
                int iPhone = cc.getColumnIndex(DatabaseHelper.COL_PHONE);
                int iName = cc.getColumnIndex(DatabaseHelper.COL_NAME);
                while (cc.moveToNext()) {
                    long id = cc.getLong(iId);
                    if (iUuid >= 0 && !cc.isNull(iUuid)) {
                        String u = cc.getString(iUuid);
                        if (u != null && !u.isEmpty()) curByUuid.put(u, id);
                    }
                    if (iPhone >= 0) {
                        String p = normPhone(cc.getString(iPhone));
                        if (!p.isEmpty() && !curByPhone.containsKey(p)) curByPhone.put(p, id);
                    }
                    if (iName >= 0) {
                        String n = normName(cc.getString(iName));
                        if (!n.isEmpty() && !curByName.containsKey(n)) curByName.put(n, id);
                    }
                }
            } finally { cc.close(); }

            Map<Long, Long> prodMap = buildProductMap(live, backup);
            Map<Long, Long> expenseMap = buildSyncUuidMap(live, backup, DatabaseHelper.TABLE_EXPENSES);
            Set<String> existingTrx = collectUuids(live, DatabaseHelper.TABLE_TRANSACTIONS);
            Map<Long, Long> selectedMap = new HashMap<>();   // backup custId → current custId (selected)
            Map<Long, Long> matchMap = new HashMap<>();        // any backup custId → current (for reseller links)
            Map<Long, Long> trxIdMap = new HashMap<>();        // backup trx _id → current trx _id (for order_inbox)

            live.beginTransaction();
            try {
                // Pass 1: customers — match or insert the selected ones.
                Cursor bc = backup.query(DatabaseHelper.TABLE_CUSTOMERS, null, null, null, null, null, null);
                int iId = bc.getColumnIndex(DatabaseHelper.COL_ID);
                int iUuid = bc.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
                int iPhone = bc.getColumnIndex(DatabaseHelper.COL_PHONE);
                int iName = bc.getColumnIndex(DatabaseHelper.COL_NAME);
                int iEdited = bc.getColumnIndex(DatabaseHelper.COL_EDITED_AT);
                try {
                    while (bc.moveToNext()) {
                        long bId = bc.getLong(iId);
                        String bUuid = (iUuid >= 0 && !bc.isNull(iUuid)) ? bc.getString(iUuid) : null;
                        Long cur;
                        if (bUuid != null && !bUuid.isEmpty()) {
                            // A distinct sync_uuid is a distinct customer — match ONLY by uuid so
                            // two customers that happen to share a phone aren't conflated.
                            cur = curByUuid.get(bUuid);
                        } else {
                            // Old backup without a uuid → best-effort match by phone then name.
                            cur = null;
                            if (iPhone >= 0) {
                                String p = normPhone(bc.getString(iPhone));
                                if (!p.isEmpty()) cur = curByPhone.get(p);
                            }
                            if (cur == null && iName >= 0) {
                                String n = normName(bc.getString(iName));
                                if (!n.isEmpty()) cur = curByName.get(n);
                            }
                        }
                        if (cur != null) matchMap.put(bId, cur);

                        if (!selectedBackupIds.contains(bId)) continue;
                        if (cur == null) {
                            // Insert the customer from the backup (re-add a missing one).
                            ContentValues cv = new ContentValues();
                            DatabaseUtils.cursorRowToContentValues(bc, cv);
                            cv.remove(DatabaseHelper.COL_ID);
                            String u = (iUuid >= 0 && !bc.isNull(iUuid)) ? bc.getString(iUuid) : null;
                            if (u == null || u.isEmpty()) u = UUID.randomUUID().toString();
                            cv.put(DatabaseHelper.COL_SYNC_UUID, u);
                            String ed = iEdited >= 0 && !bc.isNull(iEdited) ? bc.getString(iEdited) : null;
                            cv.put(DatabaseHelper.COL_EDITED_AT,
                                    ed != null && !ed.isEmpty() ? ed : DatabaseHelper.nowIso());
                            cv.put(DatabaseHelper.COL_SYNCED, 0);
                            try {
                                long newId = live.insert(DatabaseHelper.TABLE_CUSTOMERS, null, cv);
                                if (newId > 0) { cur = newId; res.customersAdded++; }
                            } catch (Exception ex) {
                                Log.w(TAG, "customer insert failed bId=" + bId, ex);
                            }
                        }
                        if (cur != null) { selectedMap.put(bId, cur); matchMap.put(bId, cur); }
                    }
                } finally { bc.close(); }

                // Pass 2: transactions of the selected customers.
                Cursor bt = backup.query(DatabaseHelper.TABLE_TRANSACTIONS, null, null, null, null, null, null);
                int tId = bt.getColumnIndex(DatabaseHelper.COL_TRX_ID);
                int tCust = bt.getColumnIndex(DatabaseHelper.COL_CUSTOMER_ID);
                int tProd = bt.getColumnIndex(DatabaseHelper.COL_TRX_PRODUCT_ID);
                int tRes = bt.getColumnIndex(DatabaseHelper.COL_TRX_RESELLER_ID);
                int tUuid = bt.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
                int tEdited = bt.getColumnIndex(DatabaseHelper.COL_EDITED_AT);
                try {
                    while (bt.moveToNext()) {
                        long bCust = tCust >= 0 && !bt.isNull(tCust) ? bt.getLong(tCust) : 0;
                        Long curCust = selectedMap.get(bCust);
                        if (curCust == null) continue;   // not a chosen customer
                        String uuid = (tUuid >= 0 && !bt.isNull(tUuid)) ? bt.getString(tUuid) : null;
                        if (uuid != null && existingTrx.contains(uuid)) {
                            // The transaction is already on the device — it's THIS customer's
                            // transaction (same uuid), so re-attach it to the restored customer
                            // (it may currently point to a missing/wrong customer).
                            ContentValues up = new ContentValues();
                            up.put(DatabaseHelper.COL_CUSTOMER_ID, curCust);
                            up.put(DatabaseHelper.COL_SYNCED, 0);
                            up.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
                            try {
                                int upd = live.update(DatabaseHelper.TABLE_TRANSACTIONS, up,
                                        DatabaseHelper.COL_SYNC_UUID + "=? AND "
                                                + DatabaseHelper.COL_CUSTOMER_ID + "<>?",
                                        new String[]{uuid, String.valueOf(curCust)});
                                if (upd > 0) res.relinked++;
                            } catch (Exception ex) {
                                Log.w(TAG, "relink failed uuid=" + uuid, ex);
                            }
                            res.skippedDuplicate++;
                            continue;
                        }

                        ContentValues cv = new ContentValues();
                        DatabaseUtils.cursorRowToContentValues(bt, cv);
                        cv.remove(DatabaseHelper.COL_ID);
                        cv.put(DatabaseHelper.COL_CUSTOMER_ID, curCust);
                        long bProd = tProd >= 0 && !bt.isNull(tProd) ? bt.getLong(tProd) : 0;
                        Long curProd = bProd > 0 ? prodMap.get(bProd) : null;
                        cv.put(DatabaseHelper.COL_TRX_PRODUCT_ID, curProd != null ? curProd : 0);
                        long bRes = tRes >= 0 && !bt.isNull(tRes) ? bt.getLong(tRes) : 0;
                        Long curRes = bRes > 0 ? matchMap.get(bRes) : null;
                        cv.put(DatabaseHelper.COL_TRX_RESELLER_ID, curRes != null ? curRes : 0);
                        if (uuid == null || uuid.isEmpty()) uuid = UUID.randomUUID().toString();
                        cv.put(DatabaseHelper.COL_SYNC_UUID, uuid);
                        String ed = tEdited >= 0 && !bt.isNull(tEdited) ? bt.getString(tEdited) : null;
                        cv.put(DatabaseHelper.COL_EDITED_AT,
                                ed != null && !ed.isEmpty() ? ed : DatabaseHelper.nowIso());
                        cv.put(DatabaseHelper.COL_SYNCED, 0);

                        try {
                            long newId = live.insert(DatabaseHelper.TABLE_TRANSACTIONS, null, cv);
                            if (newId > 0 && tId >= 0 && !bt.isNull(tId)) trxIdMap.put(bt.getLong(tId), newId);
                            existingTrx.add(uuid);
                            res.imported++;
                        } catch (Exception ex) {
                            Log.w(TAG, "trx insert failed uuid=" + uuid, ex);
                        }
                    }
                } finally { bt.close(); }

                // Pass 3: related data for the chosen customers (reseller rates/withdrawals + WA orders).
                Map<String, Map<Long, Long>> rrFk = new HashMap<>();
                rrFk.put(DatabaseHelper.COL_RR_PRODUCT_ID, prodMap);
                res.relatedAdded += importRelated(live, backup, DatabaseHelper.TABLE_RESELLER_RATES,
                        DatabaseHelper.COL_RR_CUSTOMER_ID, selectedMap,
                        collectUuids(live, DatabaseHelper.TABLE_RESELLER_RATES), rrFk);

                Map<String, Map<Long, Long>> wdFk = new HashMap<>();
                wdFk.put(DatabaseHelper.COL_WD_EXPENSE_ID, expenseMap);
                res.relatedAdded += importRelated(live, backup, DatabaseHelper.TABLE_RESELLER_WD,
                        DatabaseHelper.COL_WD_CUSTOMER_ID, selectedMap,
                        collectUuids(live, DatabaseHelper.TABLE_RESELLER_WD), wdFk);

                Map<String, Map<Long, Long>> obFk = new HashMap<>();
                obFk.put(DatabaseHelper.COL_INBOX_TRX_ID, trxIdMap);
                res.relatedAdded += importRelated(live, backup, DatabaseHelper.TABLE_ORDER_INBOX,
                        DatabaseHelper.COL_INBOX_CUSTOMER_ID, selectedMap,
                        collectUuids(live, DatabaseHelper.TABLE_ORDER_INBOX), obFk);

                // Pass 4: re-link reseller commission. Any transaction referred by a restored
                // customer (reseller_id = selected) should point reseller_id to that customer in
                // the current DB — otherwise the reseller's komisi shows 0 because those referring
                // transactions had their reseller pointer cleared while the reseller was absent.
                Cursor rl = backup.query(DatabaseHelper.TABLE_TRANSACTIONS,
                        new String[]{DatabaseHelper.COL_SYNC_UUID, DatabaseHelper.COL_TRX_RESELLER_ID},
                        DatabaseHelper.COL_TRX_RESELLER_ID + " > 0", null, null, null, null);
                try {
                    int rlUuid = rl.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
                    int rlRes = rl.getColumnIndex(DatabaseHelper.COL_TRX_RESELLER_ID);
                    while (rl.moveToNext()) {
                        long bRes = rlRes >= 0 && !rl.isNull(rlRes) ? rl.getLong(rlRes) : 0;
                        Long curRes = selectedMap.get(bRes);
                        if (curRes == null) continue;   // reseller not among the restored customers
                        String uuid = rlUuid >= 0 && !rl.isNull(rlUuid) ? rl.getString(rlUuid) : null;
                        if (uuid == null || uuid.isEmpty()) continue;
                        ContentValues up = new ContentValues();
                        up.put(DatabaseHelper.COL_TRX_RESELLER_ID, curRes);
                        up.put(DatabaseHelper.COL_SYNCED, 0);
                        up.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
                        try {
                            int upd = live.update(DatabaseHelper.TABLE_TRANSACTIONS, up,
                                    DatabaseHelper.COL_SYNC_UUID + "=? AND "
                                            + DatabaseHelper.COL_TRX_RESELLER_ID + "<>?",
                                    new String[]{uuid, String.valueOf(curRes)});
                            if (upd > 0) res.relinked++;
                        } catch (Exception ex) {
                            Log.w(TAG, "reseller relink failed uuid=" + uuid, ex);
                        }
                    }
                } finally { rl.close(); }

                live.setTransactionSuccessful();
            } finally {
                live.endTransaction();
            }
        } catch (SQLiteException e) {
            throw new InvalidBackupException("Gagal membaca backup: " + e.getMessage());
        } finally {
            if (backup != null) { try { backup.close(); } catch (Exception ignored) {} }
        }
        return res;
    }

    /** A customer row from a backup, for the selection UI. */
    public static final class BackupCustomer {
        public long id;
        public String name;
        public String phone;
        public boolean hasTransactions;   // punya ≥1 transaksi di backup
        public boolean hasGeo;            // punya koordinat (lat/lng terisi)
        public boolean hasPhoto;          // punya foto (photo_path / photo_url)
    }

    // -------------------------------------------------------------- internals

    /** backup customer _id → current customer _id (by sync_uuid, then phone, then name). */
    private static Map<Long, Long> buildCustomerMap(SQLiteDatabase live, SQLiteDatabase backup) {
        Map<String, Long> byUuid = new HashMap<>();
        Map<String, Long> byPhone = new HashMap<>();
        Map<String, Long> byName = new HashMap<>();
        Cursor c = live.query(DatabaseHelper.TABLE_CUSTOMERS, null, null, null, null, null, null);
        try {
            int iId = c.getColumnIndex(DatabaseHelper.COL_ID);
            int iUuid = c.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            int iPhone = c.getColumnIndex(DatabaseHelper.COL_PHONE);
            int iName = c.getColumnIndex(DatabaseHelper.COL_NAME);
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                if (iUuid >= 0 && !c.isNull(iUuid)) {
                    String u = c.getString(iUuid);
                    if (u != null && !u.isEmpty()) byUuid.put(u, id);
                }
                if (iPhone >= 0) {
                    String p = normPhone(c.getString(iPhone));
                    if (!p.isEmpty() && !byPhone.containsKey(p)) byPhone.put(p, id);
                }
                if (iName >= 0) {
                    String n = normName(c.getString(iName));
                    if (!n.isEmpty() && !byName.containsKey(n)) byName.put(n, id);
                }
            }
        } finally { c.close(); }

        Map<Long, Long> map = new HashMap<>();
        Cursor b = backup.query(DatabaseHelper.TABLE_CUSTOMERS, null, null, null, null, null, null);
        try {
            int iId = b.getColumnIndex(DatabaseHelper.COL_ID);
            int iUuid = b.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            int iPhone = b.getColumnIndex(DatabaseHelper.COL_PHONE);
            int iName = b.getColumnIndex(DatabaseHelper.COL_NAME);
            while (b.moveToNext()) {
                long bId = b.getLong(iId);
                Long cur = null;
                if (iUuid >= 0 && !b.isNull(iUuid)) cur = byUuid.get(b.getString(iUuid));
                if (cur == null && iPhone >= 0) {
                    String p = normPhone(b.getString(iPhone));
                    if (!p.isEmpty()) cur = byPhone.get(p);
                }
                if (cur == null && iName >= 0) {
                    String n = normName(b.getString(iName));
                    if (!n.isEmpty()) cur = byName.get(n);
                }
                if (cur != null) map.put(bId, cur);
            }
        } finally { b.close(); }
        return map;
    }

    /** backup product _id → current product _id (by sync_uuid, then name). */
    private static Map<Long, Long> buildProductMap(SQLiteDatabase live, SQLiteDatabase backup) {
        Map<String, Long> byUuid = new HashMap<>();
        Map<String, Long> byName = new HashMap<>();
        Cursor c = live.query(DatabaseHelper.TABLE_PRODUCTS, null, null, null, null, null, null);
        try {
            int iId = c.getColumnIndex(DatabaseHelper.COL_ID);
            int iUuid = c.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            int iName = c.getColumnIndex(DatabaseHelper.COL_PRODUCT_NAME);
            while (c.moveToNext()) {
                long id = c.getLong(iId);
                if (iUuid >= 0 && !c.isNull(iUuid)) {
                    String u = c.getString(iUuid);
                    if (u != null && !u.isEmpty()) byUuid.put(u, id);
                }
                if (iName >= 0) {
                    String n = normName(c.getString(iName));
                    if (!n.isEmpty() && !byName.containsKey(n)) byName.put(n, id);
                }
            }
        } finally { c.close(); }

        Map<Long, Long> map = new HashMap<>();
        Cursor b = backup.query(DatabaseHelper.TABLE_PRODUCTS, null, null, null, null, null, null);
        try {
            int iId = b.getColumnIndex(DatabaseHelper.COL_ID);
            int iUuid = b.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            int iName = b.getColumnIndex(DatabaseHelper.COL_PRODUCT_NAME);
            while (b.moveToNext()) {
                long bId = b.getLong(iId);
                Long cur = null;
                if (iUuid >= 0 && !b.isNull(iUuid)) cur = byUuid.get(b.getString(iUuid));
                if (cur == null && iName >= 0) {
                    String n = normName(b.getString(iName));
                    if (!n.isEmpty()) cur = byName.get(n);
                }
                if (cur != null) map.put(bId, cur);
            }
        } finally { b.close(); }
        return map;
    }

    private static Set<String> collectTrxUuids(SQLiteDatabase live) {
        return collectUuids(live, DatabaseHelper.TABLE_TRANSACTIONS);
    }

    /** All non-null sync_uuids present in {@code table} (for dedup). Empty if absent. */
    private static Set<String> collectUuids(SQLiteDatabase db, String table) {
        Set<String> set = new HashSet<>();
        Cursor c;
        try {
            c = db.query(table, new String[]{DatabaseHelper.COL_SYNC_UUID},
                    DatabaseHelper.COL_SYNC_UUID + " IS NOT NULL", null, null, null, null);
        } catch (SQLiteException e) {
            return set;
        }
        try {
            while (c.moveToNext()) {
                String u = c.getString(0);
                if (u != null && !u.isEmpty()) set.add(u);
            }
        } finally { c.close(); }
        return set;
    }

    /** backup row _id → current row _id matched by sync_uuid (empty if the table/uuid is absent). */
    private static Map<Long, Long> buildSyncUuidMap(SQLiteDatabase live, SQLiteDatabase backup, String table) {
        Map<String, Long> curByUuid = new HashMap<>();
        Cursor c = live.query(table, new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_SYNC_UUID + " IS NOT NULL", null, null, null, null);
        try {
            while (c.moveToNext()) {
                String u = c.getString(1);
                if (u != null && !u.isEmpty()) curByUuid.put(u, c.getLong(0));
            }
        } finally { c.close(); }

        Map<Long, Long> map = new HashMap<>();
        Cursor b;
        try {
            b = backup.query(table, null, null, null, null, null, null);
        } catch (SQLiteException e) {
            return map;
        }
        try {
            int iId = b.getColumnIndex(DatabaseHelper.COL_ID);
            int iUuid = b.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            if (iUuid < 0 || iId < 0) return map;
            while (b.moveToNext()) {
                if (b.isNull(iUuid)) continue;
                Long cur = curByUuid.get(b.getString(iUuid));
                if (cur != null) map.put(b.getLong(iId), cur);
            }
        } finally { b.close(); }
        return map;
    }

    /**
     * Import rows of {@code table} that belong to the selected customers (by {@code custCol}),
     * remapping the customer id + the given foreign keys ({@code fkRemaps}: column → backupId→currentId).
     * Skips rows whose sync_uuid is already present; marks imported rows dirty. Returns the count.
     */
    private static int importRelated(SQLiteDatabase live, SQLiteDatabase backup, String table,
                                     String custCol, Map<Long, Long> selectedMap,
                                     Set<String> existingUuids, Map<String, Map<Long, Long>> fkRemaps) {
        int n = 0;
        Cursor c;
        try {
            c = backup.query(table, null, null, null, null, null, null);
        } catch (SQLiteException e) {
            return 0;   // table may not exist in an older backup
        }
        try {
            int iCust = c.getColumnIndex(custCol);
            int iUuid = c.getColumnIndex(DatabaseHelper.COL_SYNC_UUID);
            int iEdited = c.getColumnIndex(DatabaseHelper.COL_EDITED_AT);
            Map<Integer, Map<Long, Long>> fkByIdx = new HashMap<>();
            Map<Integer, String> fkColByIdx = new HashMap<>();
            for (Map.Entry<String, Map<Long, Long>> e : fkRemaps.entrySet()) {
                int idx = c.getColumnIndex(e.getKey());
                if (idx >= 0) { fkByIdx.put(idx, e.getValue()); fkColByIdx.put(idx, e.getKey()); }
            }
            while (c.moveToNext()) {
                long bCust = iCust >= 0 && !c.isNull(iCust) ? c.getLong(iCust) : 0;
                Long curCust = selectedMap.get(bCust);
                if (curCust == null) continue;
                String uuid = iUuid >= 0 && !c.isNull(iUuid) ? c.getString(iUuid) : null;
                if (uuid != null && existingUuids.contains(uuid)) continue;

                ContentValues cv = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(c, cv);
                cv.remove(DatabaseHelper.COL_ID);
                cv.put(custCol, curCust);
                for (Map.Entry<Integer, Map<Long, Long>> e : fkByIdx.entrySet()) {
                    int idx = e.getKey();
                    long bv = !c.isNull(idx) ? c.getLong(idx) : 0;
                    Long mapped = bv > 0 ? e.getValue().get(bv) : null;
                    cv.put(fkColByIdx.get(idx), mapped != null ? mapped : 0);
                }
                if (uuid == null || uuid.isEmpty()) uuid = UUID.randomUUID().toString();
                cv.put(DatabaseHelper.COL_SYNC_UUID, uuid);
                String ed = iEdited >= 0 && !c.isNull(iEdited) ? c.getString(iEdited) : null;
                cv.put(DatabaseHelper.COL_EDITED_AT, ed != null && !ed.isEmpty() ? ed : DatabaseHelper.nowIso());
                cv.put(DatabaseHelper.COL_SYNCED, 0);

                try {
                    live.insert(table, null, cv);
                    existingUuids.add(uuid);
                    n++;
                } catch (Exception ex) {
                    Log.w(TAG, "related insert failed table=" + table, ex);
                }
            }
        } finally { c.close(); }
        return n;
    }

    private static String normPhone(String s) {
        if (s == null) return "";
        String d = s.replaceAll("[^0-9]", "");
        if (d.startsWith("62") && d.length() > 9) d = "0" + d.substring(2);
        return d;
    }

    private static String normName(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Drain {@code in} to a temp file beside the db, validate it's a DAMIU POS backup, then restore
     * any bundled "Foto Rumah" photos ({@link #TABLE_BACKUP_MEDIA}) to image files and strip the table.
     */
    private static File drainToValidatedTemp(Context ctx, InputStream in, String suffix)
            throws IOException, InvalidBackupException {
        File dbFile = ctx.getDatabasePath(DatabaseHelper.getDatabaseFileName());
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        File tmp = new File(parent, DatabaseHelper.getDatabaseFileName() + suffix);
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            copyStream(in, fos);
        }

        SQLiteDatabase test = null;
        try {
            if (!isSqliteFile(tmp)) {
                throw new InvalidBackupException("File yang dipilih bukan database SQLite yang valid.");
            }
            test = SQLiteDatabase.openDatabase(
                    tmp.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            long matched = DatabaseUtils.longForQuery(test,
                    "SELECT COUNT(*) FROM sqlite_master "
                            + "WHERE type='table' AND name IN "
                            + "('customers','products','transactions','settings')",
                    null);
            if (matched < 4) {
                throw new InvalidBackupException(
                        "Database tidak memiliki tabel DAMIU POS yang diharapkan "
                                + "(customers, products, transactions, settings).");
            }
        } catch (SQLiteException e) {
            tmp.delete();
            throw new InvalidBackupException("Gagal membuka backup: " + e.getMessage());
        } catch (InvalidBackupException e) {
            tmp.delete();
            throw e;
        } finally {
            if (test != null) { try { test.close(); } catch (Exception ignored) {} }
        }

        // Restore bundled "Foto Rumah" photos back to image files + strip the helper table. Runs for
        // every restore mode (full/merge/pick); a no-op for legacy backups without the table.
        restoreAndStripMedia(ctx, tmp);
        return tmp;
    }

    private static boolean isSqliteFile(File f) {
        if (f.length() < 16) return false;
        byte[] magic = new byte[16];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = fis.read(magic);
            if (read < 16) return false;
        } catch (IOException e) {
            return false;
        }
        // SQLite files start with the literal "SQLite format 3\000".
        String s = new String(magic, 0, 15, StandardCharsets.US_ASCII);
        return s.startsWith("SQLite format 3");
    }

    private static void deleteIfExists(File f) {
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Result of a merge-restore. */
    public static final class ImportResult {
        public int imported;          // transactions added
        public int customersAdded;    // customers re-inserted (pick-customers mode)
        public int relatedAdded;      // reseller rates/withdrawals + order-inbox added
        public int relinked;          // existing transactions re-attached to the restored customer
        public int skippedNoCustomer;
        public int skippedDuplicate;
    }

    /** Thrown when imported bytes don't look like a DAMIU POS backup. */
    public static class InvalidBackupException extends Exception {
        public InvalidBackupException(String msg) { super(msg); }
    }
}
