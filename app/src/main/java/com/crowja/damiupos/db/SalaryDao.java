package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.SalaryConfig;
import com.crowja.damiupos.model.SalaryItem;

import java.util.ArrayList;
import java.util.List;

/** Akses konfigurasi gaji per staf + komponen tunjangan/potongan bebas. */
public class SalaryDao {

    private final DatabaseHelper dbHelper;

    public SalaryDao(DatabaseHelper dbHelper) { this.dbHelper = dbHelper; }

    // -------------------------------------------------------------- config

    /** Konfigurasi gaji user; default kosong (semua 0) kalau belum diatur. */
    public SalaryConfig getConfig(long userId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SALARY, null,
                DatabaseHelper.COL_SAL_USER_ID + "=?",
                new String[]{String.valueOf(userId)}, null, null, null);
        SalaryConfig s = new SalaryConfig();
        s.setUserId(userId);
        if (c.moveToFirst()) {
            s.setGajiPokokEnabled(c.getInt(c.getColumnIndexOrThrow(
                    DatabaseHelper.COL_SAL_POKOK_ENABLED)) == 1);
            s.setGajiPokok(c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COL_SAL_POKOK)));
            s.setTunjanganHarian(c.getDouble(c.getColumnIndexOrThrow(
                    DatabaseHelper.COL_SAL_TUNJ_HARIAN)));
            s.setLemburRate(c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COL_SAL_LEMBUR_RATE)));
            s.setAngsuranSisa(c.getDouble(c.getColumnIndexOrThrow(
                    DatabaseHelper.COL_SAL_ANGSURAN_SISA)));
            s.setAngsuranBulan(c.getDouble(c.getColumnIndexOrThrow(
                    DatabaseHelper.COL_SAL_ANGSURAN_BULAN)));
            s.setJabatan(str(c, DatabaseHelper.COL_SAL_JABATAN));
            s.setStatus(str(c, DatabaseHelper.COL_SAL_STATUS));
            s.setBankName(str(c, DatabaseHelper.COL_SAL_BANK_NAME));
            s.setBankNo(str(c, DatabaseHelper.COL_SAL_BANK_NO));
            s.setBankHolder(str(c, DatabaseHelper.COL_SAL_BANK_HOLDER));
        }
        c.close();
        return s;
    }

    /** Simpan (insert-or-replace) konfigurasi gaji. */
    public void saveConfig(SalaryConfig s) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SAL_USER_ID, s.getUserId());
        v.put(DatabaseHelper.COL_SAL_POKOK_ENABLED, s.isGajiPokokEnabled() ? 1 : 0);
        v.put(DatabaseHelper.COL_SAL_POKOK, s.getGajiPokok());
        v.put(DatabaseHelper.COL_SAL_TUNJ_HARIAN, s.getTunjanganHarian());
        v.put(DatabaseHelper.COL_SAL_LEMBUR_RATE, s.getLemburRate());
        v.put(DatabaseHelper.COL_SAL_ANGSURAN_SISA, s.getAngsuranSisa());
        v.put(DatabaseHelper.COL_SAL_ANGSURAN_BULAN, s.getAngsuranBulan());
        v.put(DatabaseHelper.COL_SAL_JABATAN, s.getJabatan());
        v.put(DatabaseHelper.COL_SAL_STATUS, s.getStatus());
        v.put(DatabaseHelper.COL_SAL_BANK_NAME, s.getBankName());
        v.put(DatabaseHelper.COL_SAL_BANK_NO, s.getBankNo());
        v.put(DatabaseHelper.COL_SAL_BANK_HOLDER, s.getBankHolder());
        // Sync-aware upsert: update bumps edited_at (keeps sync_uuid); insert
        // stamps sync_uuid = the staff's sync_uuid (deterministic 1:1 → no
        // cross-device duplicate config for the same staff).
        boolean exists = configExists(db, s.getUserId());
        if (exists) {
            dbHelper.syncUpdate(db, DatabaseHelper.TABLE_SALARY, v,
                    DatabaseHelper.COL_SAL_USER_ID + "=?",
                    new String[]{String.valueOf(s.getUserId())});
        } else {
            String staffUuid = userSyncUuid(db, s.getUserId());
            if (staffUuid != null) v.put(DatabaseHelper.COL_SYNC_UUID, staffUuid);
            dbHelper.syncInsert(db, DatabaseHelper.TABLE_SALARY, v);
        }
    }

    private boolean configExists(SQLiteDatabase db, long userId) {
        Cursor c = db.query(DatabaseHelper.TABLE_SALARY,
                new String[]{DatabaseHelper.COL_SAL_USER_ID},
                DatabaseHelper.COL_SAL_USER_ID + "=?",
                new String[]{String.valueOf(userId)}, null, null, null);
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    private String userSyncUuid(SQLiteDatabase db, long userId) {
        Cursor c = db.query(DatabaseHelper.TABLE_USERS,
                new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_ID + "=?",
                new String[]{String.valueOf(userId)}, null, null, null);
        String uuid = c.moveToFirst() ? c.getString(0) : null;
        c.close();
        return uuid;
    }

    // --------------------------------------------------------------- items

    public List<SalaryItem> getItems(long userId) {
        List<SalaryItem> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SALARY_ITEMS, null,
                DatabaseHelper.COL_SI_USER_ID + "=?",
                new String[]{String.valueOf(userId)}, null, null,
                DatabaseHelper.COL_SI_KIND + " ASC, " + DatabaseHelper.COL_SI_SORT + " ASC, "
                        + DatabaseHelper.COL_SI_ID + " ASC");
        while (c.moveToNext()) {
            SalaryItem it = new SalaryItem();
            it.setId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_SI_ID)));
            it.setUserId(userId);
            it.setKind(str(c, DatabaseHelper.COL_SI_KIND));
            it.setLabel(str(c, DatabaseHelper.COL_SI_LABEL));
            it.setQty(c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COL_SI_QTY)));
            it.setRate(c.getDouble(c.getColumnIndexOrThrow(DatabaseHelper.COL_SI_RATE)));
            int sIdx = c.getColumnIndex(DatabaseHelper.COL_SI_SORT);
            if (sIdx >= 0) it.setSortOrder(c.getInt(sIdx));
            int aIdx = c.getColumnIndex(DatabaseHelper.COL_SI_ARCHIVED);
            if (aIdx >= 0) it.setArchived(c.getInt(aIdx) == 1);
            list.add(it);
        }
        c.close();
        return list;
    }

    /** Ganti seluruh komponen user dengan daftar baru (hapus lalu insert). */
    public void replaceItems(long userId, List<SalaryItem> items) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            dbHelper.syncDelete(db, DatabaseHelper.TABLE_SALARY_ITEMS, "salary_items",
                    DatabaseHelper.COL_SI_USER_ID + "=?", new String[]{String.valueOf(userId)});
            int order = 0;
            for (SalaryItem it : items) {
                ContentValues v = new ContentValues();
                v.put(DatabaseHelper.COL_SI_USER_ID, userId);
                v.put(DatabaseHelper.COL_SI_KIND, it.getKind());
                v.put(DatabaseHelper.COL_SI_LABEL, it.getLabel());
                v.put(DatabaseHelper.COL_SI_QTY, it.getQty());
                v.put(DatabaseHelper.COL_SI_RATE, it.getRate());
                v.put(DatabaseHelper.COL_SI_SORT, order++);
                v.put(DatabaseHelper.COL_SI_ARCHIVED, it.isArchived() ? 1 : 0);
                dbHelper.syncInsert(db, DatabaseHelper.TABLE_SALARY_ITEMS, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Catat satu kali pembayaran cicilan untuk semua angsuran AKTIF berbatas
     * (sisa &gt; 0): kurangi sisa sebesar cicilan; kalau lunas → sisa 0 +
     * arsipkan. Angsuran open-ended (sisa = 0) tidak diubah. Dipanggil sekali per
     * periode cut-off saat slip resmi dibuat.
     */
    public void applyAngsuranPayment(long userId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        for (SalaryItem it : getItems(userId)) {
            if (!it.isAngsuran() || it.isArchived()) continue;
            double cicilan = it.getRate();
            double sisa = it.getQty();
            if (cicilan <= 0 || sisa <= 0) continue;     // open-ended / tak ada cicilan → lewati
            double newSisa = sisa - Math.min(cicilan, sisa);
            ContentValues v = new ContentValues();
            if (newSisa <= 0.0001) {
                v.put(DatabaseHelper.COL_SI_QTY, 0);
                v.put(DatabaseHelper.COL_SI_ARCHIVED, 1);    // lunas → arsip
            } else {
                v.put(DatabaseHelper.COL_SI_QTY, newSisa);
            }
            dbHelper.syncUpdate(db, DatabaseHelper.TABLE_SALARY_ITEMS, v,
                    DatabaseHelper.COL_SI_ID + "=?", new String[]{String.valueOf(it.getId())});
        }
    }

    private static String str(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 && !c.isNull(idx) ? c.getString(idx) : "";
    }
}
