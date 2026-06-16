package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.User;

import java.util.ArrayList;
import java.util.List;

public class UserDao {

    private final DatabaseHelper dbHelper;

    public UserDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(User u) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_USER_NAME, u.getName());
        v.put(DatabaseHelper.COL_USER_PIN, u.getPin());
        v.put(DatabaseHelper.COL_USER_ROLE, u.getRole());
        v.put(DatabaseHelper.COL_USER_ACTIVE, u.isActive() ? 1 : 0);
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_USERS, v);
    }

    public int update(User u) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_USER_NAME, u.getName());
        v.put(DatabaseHelper.COL_USER_PIN, u.getPin());
        v.put(DatabaseHelper.COL_USER_ROLE, u.getRole());
        v.put(DatabaseHelper.COL_USER_ACTIVE, u.isActive() ? 1 : 0);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_USERS, v,
                DatabaseHelper.COL_USER_ID + "=?",
                new String[]{String.valueOf(u.getId())});
    }

    /** Hapus user beserta log absensinya (FK cascade). */
    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_USERS, "staff",
                DatabaseHelper.COL_USER_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    public User getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_USERS, null,
                DatabaseHelper.COL_USER_ID + "=?",
                new String[]{String.valueOf(id)}, null, null, null);
        User u = null;
        if (c.moveToFirst()) u = fromCursor(c);
        c.close();
        return u;
    }

    public List<User> getAll() {
        return query(null, null);
    }

    public List<User> getActive() {
        return query(DatabaseHelper.COL_USER_ACTIVE + "=1", null);
    }

    public int countActive() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_USERS +
                " WHERE " + DatabaseHelper.COL_USER_ACTIVE + "=1", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** Cek PIN: return user kalau cocok & aktif, null kalau salah. */
    public User authenticate(long userId, String pin) {
        User u = getById(userId);
        if (u == null || !u.isActive()) return null;
        if (u.getPin() == null || !u.getPin().equals(pin)) return null;
        return u;
    }

    /** Apakah ada user dengan role admin (terlepas aktif/tidak). */
    public boolean hasAdmin() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_USERS +
                " WHERE " + DatabaseHelper.COL_USER_ROLE + "=?",
                new String[]{User.ROLE_ADMIN});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n > 0;
    }

    /**
     * PIN admin utama: admin aktif yang paling awal dibuat (id terkecil).
     * Dipakai sebagai password enkripsi laporan shift. Null kalau tidak ada
     * admin aktif.
     */
    public String getPrimaryAdminPin() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_USER_PIN},
                DatabaseHelper.COL_USER_ROLE + "=? AND " +
                        DatabaseHelper.COL_USER_ACTIVE + "=1",
                new String[]{User.ROLE_ADMIN}, null, null,
                DatabaseHelper.COL_USER_ID + " ASC", "1");
        String pin = null;
        if (c.moveToFirst()) pin = c.getString(0);
        c.close();
        return pin;
    }

    /** Apakah {@code pin} cocok dengan salah satu admin yang aktif. */
    public boolean isAdminPin(String pin) {
        if (pin == null) return false;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_USER_ID},
                DatabaseHelper.COL_USER_ROLE + "=? AND " +
                        DatabaseHelper.COL_USER_ACTIVE + "=1 AND " +
                        DatabaseHelper.COL_USER_PIN + "=?",
                new String[]{User.ROLE_ADMIN, pin}, null, null, null, "1");
        boolean ok = c.moveToFirst();
        c.close();
        return ok;
    }

    /**
     * Pastikan ada admin: kalau belum ada admin sama sekali, buat default
     * {@link User#DEFAULT_ADMIN_NAME}/{@link User#DEFAULT_ADMIN_PIN}.
     * Return true kalau baru saja membuat admin default.
     */
    public boolean ensureDefaultAdmin() {
        if (hasAdmin()) return false;
        User u = new User();
        u.setName(User.DEFAULT_ADMIN_NAME);
        u.setPin(User.DEFAULT_ADMIN_PIN);
        u.setRole(User.ROLE_ADMIN);
        u.setActive(true);
        insert(u);
        return true;
    }

    private List<User> query(String where, String[] args) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_USERS, null, where, args,
                null, null,
                DatabaseHelper.COL_USER_NAME + " COLLATE NOCASE ASC");
        List<User> out = new ArrayList<>();
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    private User fromCursor(Cursor c) {
        User u = new User();
        u.setId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_ID)));
        u.setName(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_NAME)));
        u.setPin(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_PIN)));
        u.setRole(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_ROLE)));
        u.setActive(c.getInt(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_ACTIVE)) == 1);
        int idx = c.getColumnIndex(DatabaseHelper.COL_USER_CREATED_AT);
        if (idx >= 0) u.setCreatedAt(c.getString(idx));
        return u;
    }
}
