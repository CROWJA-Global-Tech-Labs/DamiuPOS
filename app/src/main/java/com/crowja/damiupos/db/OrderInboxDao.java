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
        return db.insert(DatabaseHelper.TABLE_ORDER_INBOX, null, v);
    }

    public int updateStatus(long id, String status, long trxId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_STATUS, status);
        v.put(DatabaseHelper.COL_INBOX_TRX_ID, trxId);
        return db.update(DatabaseHelper.TABLE_ORDER_INBOX, v,
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
        return db.update(DatabaseHelper.TABLE_ORDER_INBOX, v, where,
                new String[]{OrderInbox.STATUS_APPROVED, OrderInbox.STATUS_REJECTED});
    }

    /** Tandai bahwa user sudah klik "Balas" pada item ini. */
    public int setReplied(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_INBOX_REPLIED, 1);
        return db.update(DatabaseHelper.TABLE_ORDER_INBOX, v,
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
        return db.update(DatabaseHelper.TABLE_ORDER_INBOX, v,
                DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    public int delete(long id) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete(DatabaseHelper.TABLE_ORDER_INBOX,
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
        o.setReceivedAt(c.getString(c.getColumnIndexOrThrow(DatabaseHelper.COL_INBOX_RECEIVED_AT)));
        return o;
    }
}
