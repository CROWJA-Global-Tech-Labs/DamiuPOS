package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.model.OrderInbox;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD untuk pesanan masuk dari WA. Bisa dipanggil dari thread mana saja
 * (SQLite akses thread-safe via {@link DatabaseHelper}).
 */
public class OrderInboxDao {

    private final DatabaseHelper dbHelper;

    public OrderInboxDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public long insert(OrderInbox inbox) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_SENDER_NAME, inbox.getSenderName());
        v.put(DatabaseHelper.COL_INBOX_SENDER_PHONE, inbox.getSenderPhone());
        v.put(DatabaseHelper.COL_INBOX_CUSTOMER_ID, inbox.getCustomerId());
        v.put(DatabaseHelper.COL_INBOX_RAW, inbox.getRawMessage());
        v.put(DatabaseHelper.COL_INBOX_PARSED_JSON, inbox.getParsedJson());
        v.put(DatabaseHelper.COL_INBOX_PARSER, inbox.getParserUsed());
        v.put(DatabaseHelper.COL_INBOX_STATUS,
                inbox.getStatus() != null ? inbox.getStatus() : OrderInbox.STATUS_PENDING);
        return dbHelper.syncInsert(db, DatabaseHelper.TABLE_ORDER_INBOX, v);
    }

    public int updateStatus(long id, String status, long trxId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_STATUS, status);
        v.put(DatabaseHelper.COL_INBOX_TRX_ID, trxId);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_ORDER_INBOX, v,
                DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Auto-arsip semua row APPROVED/REJECTED yang lebih lama dari
     * {@code thresholdHours}. Dipanggil saat user buka Inbox supaya item
     * lama tidak menumpuk.
     *
     * @return jumlah row yang diarsipkan
     */
    public int autoArchiveOld(int thresholdHours) {
        if (thresholdHours <= 0) return 0;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_STATUS, OrderInbox.STATUS_ARCHIVED);
        String where = "(" + DatabaseHelper.COL_INBOX_STATUS + "=? OR "
                + DatabaseHelper.COL_INBOX_STATUS + "=?) AND "
                + DatabaseHelper.COL_INBOX_RECEIVED_AT
                + " < datetime('now','localtime','-" + thresholdHours + " hours')";
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_ORDER_INBOX, v, where,
                new String[]{OrderInbox.STATUS_APPROVED, OrderInbox.STATUS_REJECTED});
    }

    /** Tandai bahwa user sudah klik "Balas" pada item ini. */
    public int setReplied(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_REPLIED, 1);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_ORDER_INBOX, v,
                DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /**
     * Cari inbox TERBARU dari sender_name yang sama, exclude ARCHIVED.
     * Status apa pun (PENDING / APPROVED / REJECTED).
     *
     * <p>Dipakai untuk strip prefix pesan yg sudah pernah di-handle —
     * kalau pelanggan kirim "msg A" lalu user mark SELESAI lalu pelanggan
     * kirim "msg B", WA notif sering re-bundle "msg A\nmsg B"; kita
     * harus hilangkan prefix "msg A" supaya tidak duplikat.
     */
    public OrderInbox findLatestFromSender(String senderName) {
        if (senderName == null || senderName.isEmpty()) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ORDER_INBOX, null,
                "LOWER(" + DatabaseHelper.COL_INBOX_SENDER_NAME + ")=LOWER(?) "
                        + "AND " + DatabaseHelper.COL_INBOX_STATUS + "<>?",
                new String[]{senderName, OrderInbox.STATUS_ARCHIVED},
                null, null,
                DatabaseHelper.COL_INBOX_RECEIVED_AT + " DESC", "1");
        OrderInbox o = null;
        if (c.moveToFirst()) o = cursorToInbox(c);
        c.close();
        return o;
    }

    /**
     * Cari inbox PENDING terbaru dari sender_name yang sama dalam window waktu.
     * Dipakai untuk merge multi-message dari pelanggan yg sama ke 1 row.
     *
     * @param senderName  nama pengirim WA (case-insensitive match)
     * @param withinSeconds jarak waktu max dari now (mis. 300 = 5 menit)
     */
    public OrderInbox findRecentPendingFromSender(String senderName, int withinSeconds) {
        if (senderName == null || senderName.isEmpty()) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String where = "LOWER(" + DatabaseHelper.COL_INBOX_SENDER_NAME + ")=LOWER(?) "
                + "AND " + DatabaseHelper.COL_INBOX_STATUS + "=? "
                + "AND " + DatabaseHelper.COL_INBOX_RECEIVED_AT
                + " >= datetime('now','localtime','-" + withinSeconds + " seconds')";
        Cursor c = db.query(DatabaseHelper.TABLE_ORDER_INBOX, null,
                where,
                new String[]{senderName, OrderInbox.STATUS_PENDING},
                null, null,
                DatabaseHelper.COL_INBOX_RECEIVED_AT + " DESC",
                "1");
        OrderInbox o = null;
        if (c.moveToFirst()) o = cursorToInbox(c);
        c.close();
        return o;
    }

    /**
     * Update isi pesan + hasil parse untuk row yang sudah ada.
     * Reset {@code replied} ke 0 karena user perlu re-konfirmasi balasan
     * untuk pesan baru yang masuk.
     */
    public int updateContent(long id, String rawMessage, String parsedJson, String parserUsed) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_RAW, rawMessage);
        v.put(DatabaseHelper.COL_INBOX_PARSED_JSON, parsedJson);
        v.put(DatabaseHelper.COL_INBOX_PARSER, parserUsed);
        v.put(DatabaseHelper.COL_INBOX_REPLIED, 0);
        return dbHelper.syncUpdate(db, DatabaseHelper.TABLE_ORDER_INBOX, v,
                DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return dbHelper.syncDelete(db, DatabaseHelper.TABLE_ORDER_INBOX, "order_inbox",
                DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** Newest first. Includes ALL statuses including archived. */
    public List<OrderInbox> getAll() {
        return queryList(null, null);
    }

    /** Inbox utama: PENDING + APPROVED + REJECTED, exclude ARCHIVED. */
    public List<OrderInbox> getActive() {
        return queryList(DatabaseHelper.COL_INBOX_STATUS + "<>?",
                new String[]{OrderInbox.STATUS_ARCHIVED});
    }

    /** Hanya item yang sudah diarsipkan. */
    public List<OrderInbox> getArchived() {
        return queryList(DatabaseHelper.COL_INBOX_STATUS + "=?",
                new String[]{OrderInbox.STATUS_ARCHIVED});
    }

    public List<OrderInbox> getPending() {
        return queryList(DatabaseHelper.COL_INBOX_STATUS + "=?",
                new String[]{OrderInbox.STATUS_PENDING});
    }

    public int countPending() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_ORDER_INBOX
                        + " WHERE " + DatabaseHelper.COL_INBOX_STATUS + "=?",
                new String[]{OrderInbox.STATUS_PENDING});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public OrderInbox getLatestPending() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ORDER_INBOX, null,
                DatabaseHelper.COL_INBOX_STATUS + "=?",
                new String[]{OrderInbox.STATUS_PENDING},
                null, null,
                DatabaseHelper.COL_INBOX_RECEIVED_AT + " DESC",
                "1");
        OrderInbox o = null;
        if (c.moveToFirst()) o = cursorToInbox(c);
        c.close();
        return o;
    }

    private List<OrderInbox> queryList(String where, String[] args) {
        List<OrderInbox> list = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_ORDER_INBOX, null, where, args,
                null, null,
                DatabaseHelper.COL_INBOX_RECEIVED_AT + " DESC");
        while (c.moveToNext()) list.add(cursorToInbox(c));
        c.close();
        return list;
    }

    private OrderInbox cursorToInbox(Cursor c) {
        OrderInbox o = new OrderInbox();
        o.setId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_ID)));
        o.setSenderName(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_SENDER_NAME)));
        o.setSenderPhone(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_SENDER_PHONE)));
        o.setCustomerId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_CUSTOMER_ID)));
        o.setRawMessage(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_RAW)));
        o.setParsedJson(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_PARSED_JSON)));
        o.setParserUsed(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_PARSER)));
        o.setStatus(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_STATUS)));
        o.setTrxId(c.getLong(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_TRX_ID)));
        // replied column hanya ada di DB v11+; pakai getColumnIndex (boleh -1)
        int repliedIdx = c.getColumnIndex(DatabaseHelper.COL_INBOX_REPLIED);
        o.setReplied(repliedIdx >= 0 && c.getInt(repliedIdx) == 1);
        int schedIdx = c.getColumnIndex(DatabaseHelper.COL_INBOX_SCHED_INTERVAL);   // DB v41+
        o.setSchedIntervalDays(schedIdx >= 0 && !c.isNull(schedIdx) ? c.getInt(schedIdx) : 0);
        o.setReceivedAt(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_RECEIVED_AT)));
        return o;
    }

    /**
     * Redam pengingat "Pesanan Terjadwal (tiap N hari)" yang sudah TIDAK berlaku: kalau pelanggannya
     * ternyata sudah melakukan order (JUAL) dalam N hari terakhir menurut data LOKAL — yang lebih
     * mutakhir daripada data server saat pengingat di-generate (04:30) — pengingat itu ditandai
     * ARCHIVED supaya tidak lagi terhitung PENDING (tidak membunyikan alarm). Perubahan disinkron
     * balik (syncUpdate) sehingga web & perangkat lain ikut bersih. Hanya menyentuh pengingat
     * ber-parser 'scheduled' dengan sched_interval_days &gt; 0; jadwal mingguan & order WA biasa
     * dibiarkan. Dipanggil tiap sync (lihat SyncEngine) → cek berkala.
     *
     * @return jumlah pengingat yang diarsipkan.
     */
    public int pruneStaleScheduledReminders() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // 1) Pengingat interval yang masih PENDING + nomor pelanggannya.
        List<long[]> reminders = new ArrayList<>();   // {inboxId, custId, N}
        List<String> phones = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT oi." + DatabaseHelper.COL_INBOX_ID + ", oi." + DatabaseHelper.COL_INBOX_CUSTOMER_ID
                        + ", oi." + DatabaseHelper.COL_INBOX_SCHED_INTERVAL + ", c." + DatabaseHelper.COL_PHONE
                        + " FROM " + DatabaseHelper.TABLE_ORDER_INBOX + " oi"
                        + " LEFT JOIN " + DatabaseHelper.TABLE_CUSTOMERS + " c"
                        + " ON c." + DatabaseHelper.COL_ID + " = oi." + DatabaseHelper.COL_INBOX_CUSTOMER_ID
                        + " WHERE oi." + DatabaseHelper.COL_INBOX_STATUS + "='" + OrderInbox.STATUS_PENDING + "'"
                        + " AND oi." + DatabaseHelper.COL_INBOX_PARSER + "='scheduled'"
                        + " AND oi." + DatabaseHelper.COL_INBOX_SCHED_INTERVAL + " > 0", null);
        while (c.moveToNext()) {
            reminders.add(new long[]{c.getLong(0), c.getLong(1), c.getLong(2)});
            phones.add(c.isNull(3) ? null : c.getString(3));
        }
        c.close();
        if (reminders.isEmpty()) return 0;

        // tanggal dinormalkan ke waktu LOKAL (baris UTC "…Z" hasil sinkron dikonversi dulu) supaya
        // "dalam N hari terakhir" benar — samakan dengan filter tanggal di TransactionDao.
        String localDt = "(CASE WHEN t." + DatabaseHelper.COL_TANGGAL + " LIKE '%Z' THEN datetime(t."
                + DatabaseHelper.COL_TANGGAL + ",'localtime') ELSE t." + DatabaseHelper.COL_TANGGAL + " END)";

        int archived = 0;
        for (int i = 0; i < reminders.size(); i++) {
            long[] r = reminders.get(i);
            long inboxId = r[0], custId = r[1];
            int n = (int) r[2];
            List<Long> ids = siblingCustomerIds(db, custId, phones.get(i));
            if (ids.isEmpty()) continue;

            // Ada JUAL dalam N hari terakhir (waktu lokal)? → pelanggan sudah order, pengingat basi.
            String sql = "SELECT 1 FROM " + DatabaseHelper.TABLE_TRANSACTIONS + " t"
                    + " WHERE t." + DatabaseHelper.COL_TYPE + "='JUAL'"
                    + " AND t." + DatabaseHelper.COL_CUSTOMER_ID + " IN (" + joinIds(ids) + ")"
                    + " AND date(" + localDt + ") > date('now','localtime','-" + n + " days') LIMIT 1";
            Cursor rc = db.rawQuery(sql, null);
            boolean orderedRecently = rc.moveToFirst();
            rc.close();

            if (orderedRecently) {
                ContentValues v = new ContentValues();
                v.put(DatabaseHelper.COL_INBOX_STATUS, OrderInbox.STATUS_ARCHIVED);
                dbHelper.syncUpdate(db, DatabaseHelper.TABLE_ORDER_INBOX, v,
                        DatabaseHelper.COL_INBOX_ID + "=?", new String[]{String.valueOf(inboxId)});
                archived++;
            }
        }
        return archived;
    }

    /** Id semua salinan pelanggan yang nomornya kanonik-sama (duplikat lintas-perangkat); tanpa
     *  nomor → hanya id itu sendiri. Meniru penghitungan "order terakhir lintas salinan" di server. */
    private List<Long> siblingCustomerIds(SQLiteDatabase db, long custId, String phone) {
        List<Long> ids = new ArrayList<>();
        String canon = canonicalPhone(phone);
        if (canon == null) {
            if (custId > 0) ids.add(custId);
            return ids;
        }
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_PHONE},
                null, null, null, null, null);
        while (c.moveToNext()) {
            String p = c.isNull(1) ? null : c.getString(1);
            if (canon.equals(canonicalPhone(p))) ids.add(c.getLong(0));
        }
        c.close();
        if (ids.isEmpty() && custId > 0) ids.add(custId);
        return ids;
    }

    /** Digit saja, 08xx→628xx (samakan dengan dedup nomor di server). null bila kosong. */
    private static String canonicalPhone(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D+", "");
        if (d.isEmpty()) return null;
        if (d.startsWith("0")) d = "62" + d.substring(1);
        return d;
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.length() == 0 ? "0" : sb.toString();
    }
}
