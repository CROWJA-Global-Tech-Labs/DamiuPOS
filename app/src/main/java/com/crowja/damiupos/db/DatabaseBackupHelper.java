package com.crowja.damiupos.db;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Helpers to copy the SQLite database file out for backup and to swap
 * it back in for restore. The on-disk file is the entire app state, so
 * an export is a full snapshot and an import is a full overwrite.
 *
 * <p>Restoring closes the {@link DatabaseHelper} singleton via
 * {@link DatabaseHelper#resetInstance()} so the next DAO call reopens
 * the new file. Callers that hold cached references to the old helper
 * should re-fetch them via {@link DatabaseHelper#getInstance(Context)}.
 */
public final class DatabaseBackupHelper {

    public static final String BACKUP_FILE_PREFIX = "DAMIU-POS-backup-";
    public static final String BACKUP_FILE_EXT = ".db";
    public static final String BACKUP_MIME = "application/octet-stream";

    private DatabaseBackupHelper() {}

    /** Generate a filename like "DAMIU-POS-backup-20260424_153012.db". */
    public static String defaultBackupFileName() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return BACKUP_FILE_PREFIX + ts + BACKUP_FILE_EXT;
    }

    /**
     * Copy the live SQLite db file to {@code out}. Caller is responsible
     * for closing the OutputStream.
     */
    public static void exportTo(Context ctx, OutputStream out) throws IOException {
        // Force any pending WAL pages back into the main db file so the
        // copied file is self-contained.
        try {
            SQLiteDatabase db = DatabaseHelper.getInstance(ctx).getWritableDatabase();
            db.execSQL("PRAGMA wal_checkpoint(FULL);");
        } catch (SQLiteException ignored) {
            // Not in WAL mode → nothing to do
        }
        File src = ctx.getDatabasePath(DatabaseHelper.getDatabaseFileName());
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    /**
     * Replace the live SQLite db file with the contents read from {@code in}.
     * Closes the cached DAO instance so subsequent DB calls reopen against
     * the new file.
     *
     * @throws IOException             on I/O failure
     * @throws InvalidBackupException  if the bytes aren't a valid DAMIU POS backup
     */
    public static void importFrom(Context ctx, InputStream in)
            throws IOException, InvalidBackupException {
        File dbFile = ctx.getDatabasePath(DatabaseHelper.getDatabaseFileName());
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Drain into a temp file first so we can validate before touching the live db.
        File tmp = new File(parent, DatabaseHelper.getDatabaseFileName() + ".import-tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        }

        // 1. Magic bytes check
        if (!isSqliteFile(tmp)) {
            tmp.delete();
            throw new InvalidBackupException(
                    "File yang dipilih bukan database SQLite yang valid.");
        }

        // 2. Open as read-only and check that it carries our schema
        SQLiteDatabase test = null;
        try {
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
            throw new InvalidBackupException("Gagal membuka backup: " + e.getMessage());
        } finally {
            if (test != null) {
                try { test.close(); } catch (Exception ignored) {}
            }
        }

        // 3. Close the live DB so we can overwrite the file safely
        DatabaseHelper.resetInstance();

        // 4. Wipe sidecar files (journal/WAL) of the OLD db
        deleteIfExists(new File(dbFile.getAbsolutePath() + "-journal"));
        deleteIfExists(new File(dbFile.getAbsolutePath() + "-wal"));
        deleteIfExists(new File(dbFile.getAbsolutePath() + "-shm"));

        // 5. Replace the file (rename if possible, copy as fallback)
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

        // 6. Reopen — triggers onUpgrade() if the backup was from an older schema
        DatabaseHelper.getInstance(ctx).getWritableDatabase();
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

    /** Thrown when imported bytes don't look like a DAMIU POS backup. */
    public static class InvalidBackupException extends Exception {
        public InvalidBackupException(String msg) { super(msg); }
    }
}
