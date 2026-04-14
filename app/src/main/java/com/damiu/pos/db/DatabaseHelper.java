package com.damiu.pos.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "damiu_pos.db";
    private static final int DATABASE_VERSION = 9;

    // Table customers
    public static final String TABLE_CUSTOMERS = "customers";
    public static final String COL_ID = "_id";
    public static final String COL_NAME = "name";
    public static final String COL_PHONE = "phone";
    public static final String COL_ADDRESS = "address";
    public static final String COL_PHOTO_PATH = "photo_path";
    public static final String COL_LATITUDE = "latitude";
    public static final String COL_LONGITUDE = "longitude";
    public static final String COL_CREATED_AT = "created_at";

    // Table products
    public static final String TABLE_PRODUCTS = "products";
    public static final String COL_PRODUCT_ID = "_id";
    public static final String COL_PRODUCT_NAME = "name";
    public static final String COL_HARGA_JUAL = "harga_jual";
    public static final String COL_HARGA_MODAL = "harga_modal";
    public static final String COL_COLOR = "color";

    // Table galon stock
    public static final String TABLE_GALON_STOCK = "galon_stock";
    public static final String COL_STOCK_ID = "_id";
    public static final String COL_STOCK_JUMLAH = "jumlah";
    public static final String COL_STOCK_CATATAN = "catatan";
    public static final String COL_STOCK_TANGGAL = "tanggal";
    public static final String COL_STOCK_PHOTO_PATH = "photo_path";

    // Table transactions
    public static final String TABLE_TRANSACTIONS = "transactions";
    public static final String COL_TRX_ID = "_id";
    public static final String COL_CUSTOMER_ID = "customer_id";
    public static final String COL_TRX_PRODUCT_ID = "product_id";
    public static final String COL_TYPE = "type";
    public static final String COL_JUMLAH_GALON = "jumlah_galon";
    public static final String COL_HARGA_PER_GALON = "harga_per_galon";
    public static final String COL_TOTAL_HARGA = "total_harga";
    public static final String COL_TANGGAL = "tanggal";
    public static final String COL_CATATAN = "catatan";
    public static final String COL_ONGKIR = "ongkir";
    public static final String COL_ONGKIR_TYPE = "ongkir_type";
    public static final String COL_ITEMS_JSON = "items_json";
    public static final String COL_GALON_OWNERSHIP = "galon_ownership";
    public static final String COL_HARGA_BOTOL = "harga_botol";

    // Table settings
    public static final String TABLE_SETTINGS = "settings";
    public static final String COL_SETTING_KEY = "key";
    public static final String COL_SETTING_VALUE = "value";

    private static final String CREATE_TABLE_CUSTOMERS =
            "CREATE TABLE " + TABLE_CUSTOMERS + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_NAME + " TEXT NOT NULL, " +
                    COL_PHONE + " TEXT, " +
                    COL_ADDRESS + " TEXT, " +
                    COL_PHOTO_PATH + " TEXT, " +
                    COL_LATITUDE + " REAL DEFAULT 0, " +
                    COL_LONGITUDE + " REAL DEFAULT 0, " +
                    COL_CREATED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_PRODUCTS =
            "CREATE TABLE " + TABLE_PRODUCTS + " (" +
                    COL_PRODUCT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_PRODUCT_NAME + " TEXT NOT NULL, " +
                    COL_HARGA_JUAL + " REAL NOT NULL DEFAULT 0, " +
                    COL_HARGA_MODAL + " REAL NOT NULL DEFAULT 0, " +
                    COL_COLOR + " TEXT DEFAULT '#1565C0', " +
                    COL_CREATED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_GALON_STOCK =
            "CREATE TABLE " + TABLE_GALON_STOCK + " (" +
                    COL_STOCK_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_STOCK_JUMLAH + " INTEGER NOT NULL, " +
                    COL_STOCK_CATATAN + " TEXT, " +
                    COL_STOCK_PHOTO_PATH + " TEXT, " +
                    COL_STOCK_TANGGAL + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_TRANSACTIONS =
            "CREATE TABLE " + TABLE_TRANSACTIONS + " (" +
                    COL_TRX_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_CUSTOMER_ID + " INTEGER NOT NULL, " +
                    COL_TRX_PRODUCT_ID + " INTEGER DEFAULT 0, " +
                    COL_TYPE + " TEXT NOT NULL, " +
                    COL_JUMLAH_GALON + " INTEGER NOT NULL, " +
                    COL_HARGA_PER_GALON + " REAL DEFAULT 0, " +
                    COL_TOTAL_HARGA + " REAL DEFAULT 0, " +
                    COL_ONGKIR + " REAL DEFAULT 0, " +
                    COL_ONGKIR_TYPE + " TEXT DEFAULT 'per_galon', " +
                    COL_ITEMS_JSON + " TEXT, " +
                    COL_GALON_OWNERSHIP + " TEXT DEFAULT 'PINJAM', " +
                    COL_HARGA_BOTOL + " REAL DEFAULT 0, " +
                    COL_TANGGAL + " TEXT DEFAULT (datetime('now','localtime')), " +
                    COL_CATATAN + " TEXT, " +
                    "FOREIGN KEY(" + COL_CUSTOMER_ID + ") REFERENCES " +
                    TABLE_CUSTOMERS + "(" + COL_ID + ") ON DELETE CASCADE" +
                    ");";

    private static final String CREATE_TABLE_SETTINGS =
            "CREATE TABLE " + TABLE_SETTINGS + " (" +
                    COL_SETTING_KEY + " TEXT PRIMARY KEY, " +
                    COL_SETTING_VALUE + " TEXT" +
                    ");";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_CUSTOMERS);
        db.execSQL(CREATE_TABLE_PRODUCTS);
        db.execSQL(CREATE_TABLE_GALON_STOCK);
        db.execSQL(CREATE_TABLE_TRANSACTIONS);
        db.execSQL(CREATE_TABLE_SETTINGS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(CREATE_TABLE_PRODUCTS);
            db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                    " ADD COLUMN " + COL_TRX_PRODUCT_ID + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_PRODUCTS +
                        " ADD COLUMN " + COL_COLOR + " TEXT DEFAULT '#1565C0'");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 4) {
            db.execSQL(CREATE_TABLE_GALON_STOCK);
        }
        if (oldVersion < 5) {
            db.execSQL(CREATE_TABLE_SETTINGS);
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_ONGKIR + " REAL DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_ONGKIR_TYPE + " TEXT DEFAULT 'per_galon'");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_ITEMS_JSON + " TEXT");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 8) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_GALON_STOCK +
                        " ADD COLUMN " + COL_STOCK_PHOTO_PATH + " TEXT");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 9) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_GALON_OWNERSHIP + " TEXT DEFAULT 'PINJAM'");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_HARGA_BOTOL + " REAL DEFAULT 0");
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("PRAGMA foreign_keys=ON;");
    }
}
