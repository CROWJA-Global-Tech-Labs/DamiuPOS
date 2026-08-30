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

    /**
     * Apakah user yang SEDANG login berperan marketing? Dipakai untuk kebijakan notifikasi:
     * karyawan marketing TIDAK menerima broadcast pesanan/operasional (order baru dari web,
     * follow-up, dll) — hanya "Pesan & Pembaruan" (pesan admin). Tanpa login staf → false.
     */
    public static boolean isCurrentUserMarketing(android.content.Context ctx) {
        try {
            DatabaseHelper db = DatabaseHelper.getInstance(ctx);
            long uid = new SettingsDao(db).getCurrentUserId();
            if (uid <= 0) return false;
            User u = new UserDao(db).getById(uid);
            return u != null && u.isMarketing();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Apakah user yang SEDANG login berperan admin? Dipakai menggerbangi aksi yang setara dengan
     * kewenangan dashboard — mis. menandai "Kunjungi Urgent" dari Follow Up. Tanpa login staf →
     * false (perangkat tanpa login tidak boleh menandai atas nama siapa pun).
     */
    public static boolean isCurrentUserAdmin(android.content.Context ctx) {
        try {
            DatabaseHelper db = DatabaseHelper.getInstance(ctx);
            long uid = new SettingsDao(db).getCurrentUserId();
            if (uid <= 0) return false;
            User u = new UserDao(db).getById(uid);
            return u != null && u.isAdmin();
        } catch (Throwable t) {
            return false;
        }
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

    /**
     * Set PIN LOKAL (fallback) staf — disimpan SHA-256. Dipakai saat staf yang ditambah di web
     * belum diberi PIN dari web (pin_hash null) → buat sendiri di HP saat login pertama. Bila web
     * sudah set PIN (pin_hash terisi), itu yang menang (lihat authenticate).
     */
    public int setPin(long id, String pin) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_USER_PIN, User.sha256Hex(pin));
        return db.update(DatabaseHelper.TABLE_USERS, v,
                DatabaseHelper.COL_USER_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** Hapus user beserta log absensinya (FK cascade). */
    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_USERS, "staff",
                DatabaseHelper.COL_USER_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** sync_uuid staf (kunci server) untuk satu id lokal; null bila belum tersinkron. */
    public String getSyncUuidById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_USER_ID + "=?", new String[]{String.valueOf(id)},
                null, null, null, "1");
        String uuid = null;
        if (c.moveToFirst()) uuid = c.getString(0);
        c.close();
        return uuid;
    }

    /** Baris ringkas staf untuk pemilih Alokasi Galon (dipakai sebagai item Spinner). */
    public static class StaffPick {
        public final long id;
        public final String name;
        public final String uuid;

        public StaffPick(long id, String name, String uuid) {
            this.id = id;
            this.name = name;
            this.uuid = uuid;
        }

        @Override
        public String toString() {
            return name != null ? name : "-";
        }
    }

    /**
     * Staf AKTIF yang sudah punya sync_uuid (bisa dialokasikan galon; server mengenalinya lewat
     * uuid). Diurut nama. Staf yang belum tersinkron sengaja dikecualikan agar usulan tak menunjuk
     * karyawan yang tak dikenal server.
     */
    public List<StaffPick> getActiveForAllocation() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_USER_ID, DatabaseHelper.COL_USER_NAME, DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_USER_ACTIVE + "=1 AND " + DatabaseHelper.COL_SYNC_UUID
                        + " IS NOT NULL AND " + DatabaseHelper.COL_SYNC_UUID + "<>''",
                null, null, null, DatabaseHelper.COL_USER_NAME + " COLLATE NOCASE ASC");
        List<StaffPick> out = new ArrayList<>();
        while (c.moveToNext()) {
            out.add(new StaffPick(c.getLong(0), c.getString(1), c.getString(2)));
        }
        c.close();
        return out;
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

    /**
     * Cek PIN: return user kalau cocok & aktif, null kalau salah. PIN dari WEB (pin_hash, SHA-256)
     * OTORITATIF bila terisi; selain itu pakai PIN LOKAL (fallback). Dual-mode pada lokal: cocok bila
     * sudah hash ATAU masih plaintext lama (jaring anti-terkunci saat transisi migrasi hash).
     */
    public User authenticate(long userId, String pin) {
        User u = getById(userId);

        return matchesPin(u, pin) ? u : null;
    }

    /**
     * SATU-SATUNYA aturan pencocokan PIN — dipakai {@link #authenticate} (login) DAN
     * {@link #isAdminPin} (gerbang Pengaturan). JANGAN membandingkan kolom pin langsung di SQL:
     * kolom {@code pin} menyimpan SHA-256 (lihat {@link #setPin} + migrasi hash di DatabaseHelper),
     * sehingga {@code WHERE pin = <ketikan>} TAK PERNAH cocok — itulah bug "PIN admin salah terus".
     */
    private boolean matchesPin(User u, String pin) {
        if (u == null || !u.isActive() || pin == null || pin.isEmpty()) return false;
        String h = User.sha256Hex(pin);
        String web = u.getPinHash();
        if (web != null && !web.isEmpty()) {
            return h.equalsIgnoreCase(web);   // PIN dari WEB otoritatif bila terisi
        }
        String local = u.getPin();
        if (local == null || local.isEmpty()) return false;
        // Dual-mode: sudah ter-hash ATAU masih plaintext lama (jaring anti-terkunci saat transisi).
        return h.equalsIgnoreCase(local) || pin.equals(local);
    }

    /**
     * Staf aktif yang BOLEH login di perangkat ini (whitelist device_staff_logins, diatur web). Tanpa
     * whitelist untuk perangkat ini → semua staf aktif (backward-compatible). Admin SELALU disertakan &
     * daftar TAK PERNAH dikosongkan — jaring anti-terkunci: whitelist salah konfigurasi tak boleh
     * mengunci HP.
     */
    public List<User> getActiveForLogin(String deviceUuid) {
        List<User> all = getActive();
        if (deviceUuid == null || deviceUuid.isEmpty()) return all;

        java.util.Set<String> allowed = new java.util.HashSet<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.TABLE_DEVICE_STAFF_LOGINS,
                new String[]{DatabaseHelper.COL_DSL_STAFF_UUID},
                DatabaseHelper.COL_DSL_DEVICE_UUID + "=?", new String[]{deviceUuid}, null, null, null)) {
            while (c.moveToNext()) {
                String s = c.getString(0);
                if (s != null && !s.isEmpty()) allowed.add(s);
            }
        } catch (Exception ignored) {}
        if (allowed.isEmpty()) return all;   // tanpa whitelist → semua boleh

        List<User> out = new ArrayList<>();
        for (User u : all) {
            if (u.isAdmin()) { out.add(u); continue; }   // admin selalu boleh (jangan kunci owner)
            String su = syncUuidOf(u.getId());
            if (su != null && allowed.contains(su)) out.add(u);
        }
        return out.isEmpty() ? all : out;   // jangan pernah kosong
    }

    /** sync_uuid (uuid server) sebuah user lokal, atau null. */
    private String syncUuidOf(long userId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor c = db.query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_USER_ID + "=?", new String[]{String.valueOf(userId)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Boleh melakukan Promosi (Input Promosi Galon): Marketing & Admin selalu; staf lain hanya bila
     * salary_config.promo_enabled=1 (disinkron dari dashboard). Mirror aturan insentif promosi server.
     */
    public boolean canPromosi(long userId) {
        User u = getById(userId);
        if (u == null) return false;
        if (u.isMarketing() || u.isAdmin()) return true;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SALARY,
                new String[]{DatabaseHelper.COL_SAL_PROMO_ENABLED},
                DatabaseHelper.COL_SAL_USER_ID + "=?", new String[]{String.valueOf(userId)},
                null, null, null);
        boolean enabled = c.moveToFirst() && c.getInt(0) == 1;
        c.close();
        return enabled;
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

    /**
     * Apakah {@code pin} cocok dengan salah satu admin yang aktif — gerbang Pengaturan.
     *
     * <p>Memakai {@link #matchesPin} (aturan yang SAMA dengan login), BUKAN perbandingan kolom di
     * SQL: kolom {@code pin} berisi SHA-256, dan admin yang PIN-nya diatur dari web menyimpannya di
     * {@code pin_hash} (kolom {@code pin} bisa kosong). Perbandingan mentah lama membuat PIN yang
     * BENAR pun selalu ditolak sehingga Pengaturan tak bisa dibuka sama sekali.
     */
    public boolean isAdminPin(String pin) {
        if (pin == null || pin.isEmpty()) return false;
        for (User u : getActive()) {
            if (u.isAdmin() && matchesPin(u, pin)) return true;
        }

        return false;
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
        int idxHash = c.getColumnIndex(DatabaseHelper.COL_USER_PIN_HASH);
        if (idxHash >= 0) u.setPinHash(c.getString(idxHash));
        u.setRole(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_ROLE)));
        u.setActive(c.getInt(c.getColumnIndexOrThrow(DatabaseHelper.COL_USER_ACTIVE)) == 1);
        int idx = c.getColumnIndex(DatabaseHelper.COL_USER_CREATED_AT);
        if (idx >= 0) u.setCreatedAt(c.getString(idx));
        return u;
    }
}
