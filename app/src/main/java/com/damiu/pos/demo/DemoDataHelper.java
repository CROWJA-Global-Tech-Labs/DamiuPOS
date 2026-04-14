package com.damiu.pos.demo;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.model.Customer;
import com.damiu.pos.model.Product;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Generates/clears demonstration data. All demo customers have "[DEMO]" prefix
 * in their name so they can be cleanly removed via clearDemo().
 */
public class DemoDataHelper {

    public static final String DEMO_PREFIX = "[DEMO] ";

    private final DatabaseHelper dbHelper;

    public DemoDataHelper(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /**
     * Seed ~6 customers + JUAL transactions at varying ages so the Follow Up list
     * (threshold default 5 hari) shows multiple candidates.
     * Returns number of customers created.
     */
    public int generate() {
        return generateDetailed()[0];
    }

    /**
     * Returns {customersCreated, transactionsCreated} so caller can diagnose
     * silent failures (e.g. missing columns).
     */
    public int[] generateDetailed() {
        // Idempotent: wipe any previous demo rows first so re-running always gives
        // a consistent, fully-populated result (including transactions).
        clearDemo();

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Ensure at least one product exists
        long productId = ensureDefaultProduct(db);

        int[] counts = new int[]{0, 0};

        // Customer seed: name, phone, address, lat, lng, days since last purchase, galon count
        Object[][] seeds = new Object[][]{
                {"Bu Siti Rahayu",   "081234567001", "Jl. Pandanaran No. 12, Boyolali",
                        -7.5360, 110.5950,  8,  3},
                {"Pak Budi Santoso", "081234567002", "Jl. Merapi No. 45, Boyolali",
                        -7.5380, 110.5970, 12,  2},
                {"Warung Bu Ani",    "081234567003", "Jl. Mawar No. 8, Boyolali",
                        -7.5340, 110.5930, 20,  5},
                {"Pak Joko",         "081234567004", "Jl. Melati No. 21, Boyolali",
                        -7.5400, 110.6000, 35,  1},
                {"Bu Tini",          "081234567005", "Jl. Anggrek No. 3, Boyolali",
                        -7.5320, 110.5920, 50,  2},
                {"Kedai Pak Toni",   "081234567006", "Jl. Kenanga No. 17, Boyolali",
                        -7.5390, 110.5980, 70,  4},
        };

        for (Object[] s : seeds) {
            String name    = DEMO_PREFIX + (String) s[0];
            String phone   = (String) s[1];
            String address = (String) s[2];
            double lat     = (Double) s[3];
            double lng     = (Double) s[4];
            int days       = (Integer) s[5];
            int galon      = (Integer) s[6];

            // Skip if already seeded
            if (customerExists(db, name)) continue;

            ContentValues cv = new ContentValues();
            cv.put(DatabaseHelper.COL_NAME, name);
            cv.put(DatabaseHelper.COL_PHONE, phone);
            cv.put(DatabaseHelper.COL_ADDRESS, address);
            cv.put(DatabaseHelper.COL_LATITUDE, lat);
            cv.put(DatabaseHelper.COL_LONGITUDE, lng);
            long customerId = db.insert(DatabaseHelper.TABLE_CUSTOMERS, null, cv);
            if (customerId < 0) continue;
            counts[0]++;

            // Insert a JUAL transaction dated `days` days ago (compute timestamp in Java)
            double hargaPerGalon = 5000;
            double totalHarga = hargaPerGalon * galon;

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -days);
            String tanggal = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(cal.getTime());

            ContentValues tv = new ContentValues();
            tv.put(DatabaseHelper.COL_CUSTOMER_ID, customerId);
            tv.put(DatabaseHelper.COL_TRX_PRODUCT_ID, productId);
            tv.put(DatabaseHelper.COL_TYPE, "JUAL");
            tv.put(DatabaseHelper.COL_JUMLAH_GALON, galon);
            tv.put(DatabaseHelper.COL_HARGA_PER_GALON, hargaPerGalon);
            tv.put(DatabaseHelper.COL_TOTAL_HARGA, totalHarga);
            tv.put(DatabaseHelper.COL_ONGKIR, 0);
            tv.put(DatabaseHelper.COL_ONGKIR_TYPE, "per_galon");
            tv.put(DatabaseHelper.COL_TANGGAL, tanggal);
            long trxId = db.insert(DatabaseHelper.TABLE_TRANSACTIONS, null, tv);
            if (trxId > 0) counts[1]++;
            else android.util.Log.e("DAMIU/Demo",
                    "Transaction insert failed for " + name + " (days=" + days + ")");
        }
        return counts;
    }

    /** Delete all demo customers (and cascade their transactions). */
    public int clearDemo() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // Find IDs to delete
        List<Long> ids = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT " + DatabaseHelper.COL_ID + " FROM " + DatabaseHelper.TABLE_CUSTOMERS +
                        " WHERE " + DatabaseHelper.COL_NAME + " LIKE ?",
                new String[]{DEMO_PREFIX + "%"});
        while (c.moveToNext()) ids.add(c.getLong(0));
        c.close();

        if (ids.isEmpty()) return 0;

        for (long id : ids) {
            db.delete(DatabaseHelper.TABLE_TRANSACTIONS,
                    DatabaseHelper.COL_CUSTOMER_ID + "=?",
                    new String[]{String.valueOf(id)});
            db.delete(DatabaseHelper.TABLE_CUSTOMERS,
                    DatabaseHelper.COL_ID + "=?",
                    new String[]{String.valueOf(id)});
        }
        return ids.size();
    }

    private boolean customerExists(SQLiteDatabase db, String name) {
        Cursor c = db.rawQuery(
                "SELECT 1 FROM " + DatabaseHelper.TABLE_CUSTOMERS +
                        " WHERE " + DatabaseHelper.COL_NAME + "=? LIMIT 1",
                new String[]{name});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    private long ensureDefaultProduct(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
                "SELECT " + DatabaseHelper.COL_PRODUCT_ID + " FROM " + DatabaseHelper.TABLE_PRODUCTS +
                        " ORDER BY " + DatabaseHelper.COL_PRODUCT_ID + " ASC LIMIT 1",
                null);
        long id = -1;
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        if (id > 0) return id;

        ContentValues cv = new ContentValues();
        cv.put(DatabaseHelper.COL_PRODUCT_NAME, "Galon 19L");
        cv.put("harga_jual", 5000);
        cv.put("harga_modal", 4000);
        return db.insert(DatabaseHelper.TABLE_PRODUCTS, null, cv);
    }
}
