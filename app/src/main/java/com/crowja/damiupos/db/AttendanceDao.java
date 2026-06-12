package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.Attendance;

import java.util.ArrayList;
import java.util.List;

public class AttendanceDao {

    private final DatabaseHelper dbHelper;

    public AttendanceDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** Catat event absensi sekarang (timestamp dari SQLite localtime). */
    public long log(long userId, String event) {
        return log(userId, event, null);
    }

    /** Catat event absensi + path foto wajah (nullable). */
    public long log(long userId, String event, String photoPath) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_ATT_USER_ID, userId);
        v.put(DatabaseHelper.COL_ATT_EVENT, event);
        if (photoPath != null) v.put(DatabaseHelper.COL_ATT_PHOTO_PATH, photoPath);
        return db.insert(DatabaseHelper.TABLE_ATTENDANCE, null, v);
    }

    /**
     * Kosongkan kolom photo_path untuk semua event dalam rentang tanggal
     * (dipanggil setelah foto periode dihapus agar tidak ada path menggantung).
     */
    public void clearPhotoPathsBetween(String startDate, String endDate) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.putNull(DatabaseHelper.COL_ATT_PHOTO_PATH);
        db.update(DatabaseHelper.TABLE_ATTENDANCE, v,
                DatabaseHelper.COL_ATT_TS + ">=? AND " + DatabaseHelper.COL_ATT_TS + "<=?",
                new String[]{startDate + " 00:00:00", endDate + " 23:59:59"});
    }

    /** Foto IN pertama (login) shift berjalan user — null kalau tidak ada. */
    public String getCurrentShiftLoginPhoto(long userId) {
        for (Attendance a : getCurrentShiftEvents(userId)) {
            if (Attendance.EVENT_IN.equals(a.getEvent()) && a.getPhotoPath() != null) {
                return a.getPhotoPath();
            }
        }
        return null;
    }

    /** Event terakhir user (null kalau belum pernah absen). */
    public Attendance getLastEvent(long userId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ATTENDANCE, null,
                DatabaseHelper.COL_ATT_USER_ID + "=?",
                new String[]{String.valueOf(userId)},
                null, null, DatabaseHelper.COL_ATT_ID + " DESC", "1");
        Attendance a = null;
        if (c.moveToFirst()) a = fromCursor(c);
        c.close();
        return a;
    }

    /**
     * Timestamp awal shift berjalan: event IN pertama SETELAH OUT terakhir.
     * Kalau belum pernah OUT → IN pertama. Null kalau tidak ada IN.
     */
    public String getCurrentShiftStart(long userId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String sql = "SELECT MIN(" + DatabaseHelper.COL_ATT_TS + ") FROM " +
                DatabaseHelper.TABLE_ATTENDANCE +
                " WHERE " + DatabaseHelper.COL_ATT_USER_ID + "=?" +
                " AND " + DatabaseHelper.COL_ATT_EVENT + "='" + Attendance.EVENT_IN + "'" +
                " AND " + DatabaseHelper.COL_ATT_ID + " > COALESCE((SELECT MAX(" +
                DatabaseHelper.COL_ATT_ID + ") FROM " + DatabaseHelper.TABLE_ATTENDANCE +
                " WHERE " + DatabaseHelper.COL_ATT_USER_ID + "=?" +
                " AND " + DatabaseHelper.COL_ATT_EVENT + "='" + Attendance.EVENT_OUT + "'), 0)";
        Cursor c = db.rawQuery(sql, new String[]{
                String.valueOf(userId), String.valueOf(userId)});
        String ts = null;
        if (c.moveToFirst()) ts = c.getString(0);
        c.close();
        return ts;
    }

    /** Semua event user dalam shift berjalan (sejak IN pertama setelah OUT terakhir). */
    public List<Attendance> getCurrentShiftEvents(long userId) {
        String start = getCurrentShiftStart(userId);
        if (start == null) return new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ATTENDANCE, null,
                DatabaseHelper.COL_ATT_USER_ID + "=? AND " +
                        DatabaseHelper.COL_ATT_TS + ">=?",
                new String[]{String.valueOf(userId), start},
                null, null, DatabaseHelper.COL_ATT_ID + " ASC");
        List<Attendance> out = new ArrayList<>();
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    /**
     * Semua event user dalam rentang tanggal [startDate 00:00, endDate 23:59:59],
     * urut kronologis. {@code startDate}/{@code endDate} format "yyyy-MM-dd".
     */
    public List<Attendance> getEventsByUserBetween(long userId, String startDate, String endDate) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ATTENDANCE, null,
                DatabaseHelper.COL_ATT_USER_ID + "=? AND " +
                        DatabaseHelper.COL_ATT_TS + ">=? AND " +
                        DatabaseHelper.COL_ATT_TS + "<=?",
                new String[]{String.valueOf(userId), startDate + " 00:00:00",
                        endDate + " 23:59:59"},
                null, null, DatabaseHelper.COL_ATT_ID + " ASC");
        List<Attendance> out = new ArrayList<>();
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    /** Semua event user, terbaru dulu, dibatasi {@code limit} baris (0 = semua). */
    public List<Attendance> getEventsByUser(long userId, int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ATTENDANCE, null,
                DatabaseHelper.COL_ATT_USER_ID + "=?",
                new String[]{String.valueOf(userId)},
                null, null, DatabaseHelper.COL_ATT_ID + " DESC",
                limit > 0 ? String.valueOf(limit) : null);
        List<Attendance> out = new ArrayList<>();
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    private Attendance fromCursor(Cursor c) {
        Attendance a = new Attendance();
        a.setId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_ATT_ID)));
        a.setUserId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_ATT_USER_ID)));
        a.setEvent(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_ATT_EVENT)));
        a.setTs(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_ATT_TS)));
        int idxPhoto = c.getColumnIndex(DatabaseHelper.COL_ATT_PHOTO_PATH);
        if (idxPhoto >= 0) a.setPhotoPath(c.getString(idxPhoto));
        return a;
    }
}
