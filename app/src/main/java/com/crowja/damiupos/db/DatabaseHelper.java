package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "damiu_pos.db";
    private static final int DATABASE_VERSION = 26;

    // ---- Online sync bookkeeping (v26) ----------------------------------------
    // Added to every syncable table; the server keys rows by sync_uuid, resolves
    // conflicts by edited_at (last-write-wins), and `synced`=0 marks a local row
    // that still needs to be pushed. Deletions are recorded in sync_tombstones so
    // existing SELECT queries never have to filter soft-deleted rows.
    public static final String COL_SYNC_UUID = "sync_uuid";
    public static final String COL_EDITED_AT = "edited_at";
    public static final String COL_SYNCED = "synced";      // 0 = dirty, 1 = pushed
    public static final String TABLE_SYNC_TOMBSTONES = "sync_tombstones";
    public static final String COL_TS_ENTITY = "entity";   // server registry name

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
    /** 1 = pelanggan ini reseller (dapat komisi per galon). */
    public static final String COL_IS_RESELLER = "is_reseller";
    /** Timestamp sejak kapan jadi reseller — komisi dihitung dari transaksi
     *  JUAL setelah tanggal ini saja. */
    public static final String COL_RESELLER_SINCE = "reseller_since";
    /** 1 = komisi reseller ini ditambahkan ke harga air minum saat transaksi
     *  (pelanggan membayar harga + komisi), bukan diserap margin depot. */
    public static final String COL_KOMISI_ADD_TO_PRICE = "komisi_add_to_price";
    /** Timestamp saat pelanggan di-"Remove" dari daftar Follow Up. NULL = tidak
     *  dikecualikan. Pelanggan otomatis muncul lagi kalau beli setelah tanggal ini. */
    public static final String COL_FOLLOWUP_EXCLUDED_AT = "followup_excluded_at";
    /** Alasan pelanggan dikeluarkan dari Follow Up (wajib diisi saat remove). */
    public static final String COL_FOLLOWUP_EXCLUDE_REASON = "followup_exclude_reason";
    /** Timestamp terakhir pelanggan di-follow-up (kirim pesan WA follow-up).
     *  Dipakai laporan harian: konsumen yang di-follow-up hari tsb. */
    public static final String COL_LAST_FOLLOWUP_AT = "last_followup_at";

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
    /** Metode pembayaran transaksi JUAL: TUNAI | QRIS | TRANSFER. */
    public static final String COL_PAYMENT_METHOD = "payment_method";
    /** Reseller afiliasi: customer_id reseller yang mendapat komisi atas
     *  transaksi JUAL ini (mis. transaksi atas rujukan reseller). 0 = tidak ada. */
    public static final String COL_TRX_RESELLER_ID = "reseller_id";

    // Table settings
    public static final String TABLE_SETTINGS = "settings";
    public static final String COL_SETTING_KEY = "key";
    public static final String COL_SETTING_VALUE = "value";

    // Table order_inbox — pesanan masuk dari notifikasi WhatsApp
    public static final String TABLE_ORDER_INBOX = "order_inbox";
    public static final String COL_INBOX_ID = "_id";
    public static final String COL_INBOX_SENDER_NAME = "sender_name";
    public static final String COL_INBOX_SENDER_PHONE = "sender_phone";
    public static final String COL_INBOX_CUSTOMER_ID = "customer_id";
    public static final String COL_INBOX_RAW = "raw_message";
    public static final String COL_INBOX_PARSED_JSON = "parsed_json";
    public static final String COL_INBOX_PARSER = "parser_used"; // 'regex' or 'claude'
    public static final String COL_INBOX_STATUS = "status";       // PENDING, APPROVED, REJECTED
    public static final String COL_INBOX_TRX_ID = "trx_id";
    public static final String COL_INBOX_RECEIVED_AT = "received_at";
    /** 1 kalau user sudah klik "Balas" pada item ini, 0 kalau belum.
     *  Dipakai sebagai gate sebelum boleh "Buat Trx + Selesai". */
    public static final String COL_INBOX_REPLIED = "replied";

    // Table expenses — operational expenses (listrik, gaji, beli botol, dll)
    public static final String TABLE_EXPENSES = "expenses";
    public static final String COL_EXPENSE_ID = "_id";
    public static final String COL_EXPENSE_NAME = "name";
    public static final String COL_EXPENSE_AMOUNT = "amount";
    public static final String COL_EXPENSE_PHOTO_PATH = "photo_path";
    public static final String COL_EXPENSE_NOTE = "note";
    public static final String COL_EXPENSE_CREATED_AT = "created_at";
    /** Kategori pengeluaran: 'AIR_BAKU' | 'SEGEL' | 'TUTUP' | 'UMUM'. */
    public static final String COL_EXPENSE_CATEGORY = "category";
    /** Jumlah liter air baku yang dibeli (untuk kategori AIR_BAKU; 0 untuk lainnya). */
    public static final String COL_EXPENSE_LITERS = "liters";
    /** Jumlah pcs (untuk kategori SEGEL / TUTUP; 0 untuk lainnya). */
    public static final String COL_EXPENSE_PCS = "pcs";

    // Table reseller_rates — override komisi per reseller per jenis air minum.
    // Kalau tidak ada row untuk (customer, product) → pakai rate global Settings.
    public static final String TABLE_RESELLER_RATES = "reseller_rates";
    public static final String COL_RR_ID = "_id";
    public static final String COL_RR_CUSTOMER_ID = "customer_id";
    public static final String COL_RR_PRODUCT_ID = "product_id";
    public static final String COL_RR_KOMISI = "komisi";

    // Table users — multi user (kasir/operator) dengan PIN login
    public static final String TABLE_USERS = "users";
    public static final String COL_USER_ID = "_id";
    public static final String COL_USER_NAME = "name";
    public static final String COL_USER_PIN = "pin";
    public static final String COL_USER_ROLE = "role";         // 'admin' | 'staf'
    public static final String COL_USER_ACTIVE = "is_active";  // 1 = bisa login
    public static final String COL_USER_CREATED_AT = "created_at";

    // Table attendance — log absensi per user. Event:
    //   IN    = clock in (login / kembali dari istirahat)
    //   BREAK = mulai istirahat (app idle sampai clock in lagi)
    //   OUT   = clock out (akhir shift, kirim laporan ke admin)
    public static final String TABLE_ATTENDANCE = "attendance";
    public static final String COL_ATT_ID = "_id";
    public static final String COL_ATT_USER_ID = "user_id";
    public static final String COL_ATT_EVENT = "event";
    public static final String COL_ATT_TS = "ts";
    /** Path foto wajah (selfie) saat clock in / pulang. */
    public static final String COL_ATT_PHOTO_PATH = "photo_path";

    // Table salary_config — konfigurasi gaji per staf (slip gaji cut-off).
    public static final String TABLE_SALARY = "salary_config";
    public static final String COL_SAL_USER_ID = "user_id";          // PK
    public static final String COL_SAL_POKOK_ENABLED = "pokok_enabled";
    public static final String COL_SAL_POKOK = "gaji_pokok";
    public static final String COL_SAL_TUNJ_HARIAN = "tunjangan_harian"; // per hari hadir
    public static final String COL_SAL_LEMBUR_RATE = "lembur_rate";      // per jam
    public static final String COL_SAL_ANGSURAN_SISA = "angsuran_sisa";  // sisa hutang
    public static final String COL_SAL_ANGSURAN_BULAN = "angsuran_bulan"; // cicilan/bulan
    public static final String COL_SAL_JABATAN = "jabatan";
    public static final String COL_SAL_STATUS = "status";
    public static final String COL_SAL_BANK_NAME = "bank_name";
    public static final String COL_SAL_BANK_NO = "bank_no";
    public static final String COL_SAL_BANK_HOLDER = "bank_holder";

    // Table salary_items — komponen gaji bebas per staf (tunjangan/potongan).
    public static final String TABLE_SALARY_ITEMS = "salary_items";
    public static final String COL_SI_ID = "_id";
    public static final String COL_SI_USER_ID = "user_id";
    public static final String COL_SI_KIND = "kind";                 // 'INCOME' | 'DEDUCTION'
    public static final String COL_SI_LABEL = "label";
    public static final String COL_SI_QTY = "qty";
    public static final String COL_SI_RATE = "rate";
    public static final String COL_SI_SORT = "sort_order";
    /** 1 = angsuran sudah lunas → diarsipkan, tidak ditagihkan lagi. */
    public static final String COL_SI_ARCHIVED = "archived";

    // Table pending_transactions — "Transaksi Pending": pesanan yang dicatat
    // staf saat belum bisa dieksekusi (mis. hendak pulang). Dieksekusi nanti
    // lewat layar Transaksi Baru, lalu baris pending dihapus.
    public static final String TABLE_PENDING_TRX = "pending_transactions";
    public static final String COL_PT_ID = "_id";
    public static final String COL_PT_CUSTOMER_ID = "customer_id";   // -1 = Umum/belum dipilih
    public static final String COL_PT_CUSTOMER_NAME = "customer_name";
    public static final String COL_PT_CUSTOMER_PHONE = "customer_phone";
    public static final String COL_PT_NOTE = "note";                 // ringkasan pesanan + catatan
    public static final String COL_PT_CREATED_BY = "created_by";      // user id pembuat
    public static final String COL_PT_CREATED_BY_NAME = "created_by_name";
    public static final String COL_PT_STATUS = "status";             // 0=PENDING, 1=DONE
    public static final String COL_PT_CREATED_AT = "created_at";

    // Table reseller_withdrawals — pencairan komisi reseller (air atau uang)
    public static final String TABLE_RESELLER_WD = "reseller_withdrawals";
    public static final String COL_WD_ID = "_id";
    public static final String COL_WD_CUSTOMER_ID = "customer_id";
    public static final String COL_WD_TYPE = "type";          // 'AIR' | 'UANG'
    public static final String COL_WD_GALON_QTY = "galon_qty"; // jumlah galon (untuk AIR)
    public static final String COL_WD_AMOUNT = "amount";       // nilai rupiah pencairan
    public static final String COL_WD_NOTE = "note";
    public static final String COL_WD_CREATED_AT = "created_at";
    /** Id expense (pengeluaran) yang dicatat untuk pencairan ini (0 = tidak ada).
     *  Dipakai untuk menghapus expense terkait saat pencairan dihapus. */
    public static final String COL_WD_EXPENSE_ID = "expense_id";

    private static final String CREATE_TABLE_CUSTOMERS =
            "CREATE TABLE " + TABLE_CUSTOMERS + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_NAME + " TEXT NOT NULL, " +
                    COL_PHONE + " TEXT, " +
                    COL_ADDRESS + " TEXT, " +
                    COL_PHOTO_PATH + " TEXT, " +
                    COL_LATITUDE + " REAL DEFAULT 0, " +
                    COL_LONGITUDE + " REAL DEFAULT 0, " +
                    COL_IS_RESELLER + " INTEGER DEFAULT 0, " +
                    COL_RESELLER_SINCE + " TEXT, " +
                    COL_KOMISI_ADD_TO_PRICE + " INTEGER DEFAULT 1, " +
                    COL_FOLLOWUP_EXCLUDED_AT + " TEXT, " +
                    COL_FOLLOWUP_EXCLUDE_REASON + " TEXT, " +
                    COL_LAST_FOLLOWUP_AT + " TEXT, " +
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
                    COL_PAYMENT_METHOD + " TEXT, " +
                    COL_TRX_RESELLER_ID + " INTEGER DEFAULT 0, " +
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

    private static final String CREATE_TABLE_ORDER_INBOX =
            "CREATE TABLE " + TABLE_ORDER_INBOX + " (" +
                    COL_INBOX_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_INBOX_SENDER_NAME + " TEXT, " +
                    COL_INBOX_SENDER_PHONE + " TEXT, " +
                    COL_INBOX_CUSTOMER_ID + " INTEGER DEFAULT 0, " +
                    COL_INBOX_RAW + " TEXT NOT NULL, " +
                    COL_INBOX_PARSED_JSON + " TEXT, " +
                    COL_INBOX_PARSER + " TEXT, " +
                    COL_INBOX_STATUS + " TEXT DEFAULT 'PENDING', " +
                    COL_INBOX_TRX_ID + " INTEGER DEFAULT 0, " +
                    COL_INBOX_REPLIED + " INTEGER DEFAULT 0, " +
                    COL_INBOX_RECEIVED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_EXPENSES =
            "CREATE TABLE " + TABLE_EXPENSES + " (" +
                    COL_EXPENSE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_EXPENSE_NAME + " TEXT NOT NULL, " +
                    COL_EXPENSE_AMOUNT + " REAL NOT NULL DEFAULT 0, " +
                    COL_EXPENSE_PHOTO_PATH + " TEXT, " +
                    COL_EXPENSE_NOTE + " TEXT, " +
                    COL_EXPENSE_CATEGORY + " TEXT DEFAULT 'UMUM', " +
                    COL_EXPENSE_LITERS + " REAL DEFAULT 0, " +
                    COL_EXPENSE_PCS + " INTEGER DEFAULT 0, " +
                    COL_EXPENSE_CREATED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_RESELLER_RATES =
            "CREATE TABLE " + TABLE_RESELLER_RATES + " (" +
                    COL_RR_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_RR_CUSTOMER_ID + " INTEGER NOT NULL, " +
                    COL_RR_PRODUCT_ID + " INTEGER NOT NULL, " +
                    COL_RR_KOMISI + " REAL NOT NULL DEFAULT 0, " +
                    "UNIQUE(" + COL_RR_CUSTOMER_ID + "," + COL_RR_PRODUCT_ID + "), " +
                    "FOREIGN KEY(" + COL_RR_CUSTOMER_ID + ") REFERENCES " +
                    TABLE_CUSTOMERS + "(" + COL_ID + ") ON DELETE CASCADE" +
                    ");";

    private static final String CREATE_TABLE_RESELLER_WD =
            "CREATE TABLE " + TABLE_RESELLER_WD + " (" +
                    COL_WD_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_WD_CUSTOMER_ID + " INTEGER NOT NULL, " +
                    COL_WD_TYPE + " TEXT NOT NULL DEFAULT 'UANG', " +
                    COL_WD_GALON_QTY + " INTEGER DEFAULT 0, " +
                    COL_WD_AMOUNT + " REAL NOT NULL DEFAULT 0, " +
                    COL_WD_NOTE + " TEXT, " +
                    COL_WD_EXPENSE_ID + " INTEGER DEFAULT 0, " +
                    COL_WD_CREATED_AT + " TEXT DEFAULT (datetime('now','localtime')), " +
                    "FOREIGN KEY(" + COL_WD_CUSTOMER_ID + ") REFERENCES " +
                    TABLE_CUSTOMERS + "(" + COL_ID + ") ON DELETE CASCADE" +
                    ");";

    private static final String CREATE_TABLE_USERS =
            "CREATE TABLE " + TABLE_USERS + " (" +
                    COL_USER_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_USER_NAME + " TEXT NOT NULL, " +
                    COL_USER_PIN + " TEXT NOT NULL, " +
                    COL_USER_ROLE + " TEXT NOT NULL DEFAULT 'staf', " +
                    COL_USER_ACTIVE + " INTEGER NOT NULL DEFAULT 1, " +
                    COL_USER_CREATED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_SALARY =
            "CREATE TABLE " + TABLE_SALARY + " (" +
                    COL_SAL_USER_ID + " INTEGER PRIMARY KEY, " +
                    COL_SAL_POKOK_ENABLED + " INTEGER DEFAULT 0, " +
                    COL_SAL_POKOK + " REAL DEFAULT 0, " +
                    COL_SAL_TUNJ_HARIAN + " REAL DEFAULT 0, " +
                    COL_SAL_LEMBUR_RATE + " REAL DEFAULT 0, " +
                    COL_SAL_ANGSURAN_SISA + " REAL DEFAULT 0, " +
                    COL_SAL_ANGSURAN_BULAN + " REAL DEFAULT 0, " +
                    COL_SAL_JABATAN + " TEXT, " +
                    COL_SAL_STATUS + " TEXT, " +
                    COL_SAL_BANK_NAME + " TEXT, " +
                    COL_SAL_BANK_NO + " TEXT, " +
                    COL_SAL_BANK_HOLDER + " TEXT" +
                    ");";

    private static final String CREATE_TABLE_SALARY_ITEMS =
            "CREATE TABLE " + TABLE_SALARY_ITEMS + " (" +
                    COL_SI_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_SI_USER_ID + " INTEGER NOT NULL, " +
                    COL_SI_KIND + " TEXT NOT NULL DEFAULT 'INCOME', " +
                    COL_SI_LABEL + " TEXT, " +
                    COL_SI_QTY + " REAL DEFAULT 1, " +
                    COL_SI_RATE + " REAL DEFAULT 0, " +
                    COL_SI_SORT + " INTEGER DEFAULT 0, " +
                    COL_SI_ARCHIVED + " INTEGER DEFAULT 0" +
                    ");";

    private static final String CREATE_TABLE_PENDING_TRX =
            "CREATE TABLE " + TABLE_PENDING_TRX + " (" +
                    COL_PT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_PT_CUSTOMER_ID + " INTEGER DEFAULT -1, " +
                    COL_PT_CUSTOMER_NAME + " TEXT, " +
                    COL_PT_CUSTOMER_PHONE + " TEXT, " +
                    COL_PT_NOTE + " TEXT, " +
                    COL_PT_CREATED_BY + " INTEGER DEFAULT 0, " +
                    COL_PT_CREATED_BY_NAME + " TEXT, " +
                    COL_PT_STATUS + " INTEGER DEFAULT 0, " +
                    COL_PT_CREATED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_ATTENDANCE =
            "CREATE TABLE " + TABLE_ATTENDANCE + " (" +
                    COL_ATT_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_ATT_USER_ID + " INTEGER NOT NULL, " +
                    COL_ATT_EVENT + " TEXT NOT NULL, " +
                    COL_ATT_PHOTO_PATH + " TEXT, " +
                    COL_ATT_TS + " TEXT DEFAULT (datetime('now','localtime')), " +
                    "FOREIGN KEY(" + COL_ATT_USER_ID + ") REFERENCES " +
                    TABLE_USERS + "(" + COL_USER_ID + ") ON DELETE CASCADE" +
                    ");";

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Drop the cached singleton so the next {@link #getInstance(Context)}
     * reopens the database file from disk. Call this after replacing the
     * .db file (e.g., after restoring from a backup) so cached connections
     * don't keep reading the old file.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            try { instance.close(); } catch (Exception ignored) {}
            instance = null;
        }
    }

    /** Filesystem name of the SQLite file (matches what Android stores on disk). */
    public static String getDatabaseFileName() {
        return DATABASE_NAME;
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
        db.execSQL(CREATE_TABLE_ORDER_INBOX);
        db.execSQL(CREATE_TABLE_EXPENSES);
        db.execSQL(CREATE_TABLE_RESELLER_WD);
        db.execSQL(CREATE_TABLE_RESELLER_RATES);
        db.execSQL(CREATE_TABLE_USERS);
        db.execSQL(CREATE_TABLE_ATTENDANCE);
        db.execSQL(CREATE_TABLE_SALARY);
        db.execSQL(CREATE_TABLE_SALARY_ITEMS);
        db.execSQL(CREATE_TABLE_PENDING_TRX);
        installSyncInfra(db, false);
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
        if (oldVersion < 10) {
            try { db.execSQL(CREATE_TABLE_ORDER_INBOX); } catch (Exception ignored) {}
        }
        if (oldVersion < 11) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_ORDER_INBOX +
                        " ADD COLUMN " + COL_INBOX_REPLIED + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 12) {
            try { db.execSQL(CREATE_TABLE_EXPENSES); } catch (Exception ignored) {}
        }
        if (oldVersion < 13) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_CUSTOMERS +
                        " ADD COLUMN " + COL_IS_RESELLER + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_CUSTOMERS +
                        " ADD COLUMN " + COL_RESELLER_SINCE + " TEXT");
            } catch (Exception ignored) {}
            try { db.execSQL(CREATE_TABLE_RESELLER_WD); } catch (Exception ignored) {}
        }
        if (oldVersion < 14) {
            try { db.execSQL(CREATE_TABLE_RESELLER_RATES); } catch (Exception ignored) {}
        }
        if (oldVersion < 15) {
            try { db.execSQL(CREATE_TABLE_USERS); } catch (Exception ignored) {}
            try { db.execSQL(CREATE_TABLE_ATTENDANCE); } catch (Exception ignored) {}
        }
        if (oldVersion < 16) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_CUSTOMERS +
                        " ADD COLUMN " + COL_FOLLOWUP_EXCLUDED_AT + " TEXT");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_CUSTOMERS +
                        " ADD COLUMN " + COL_FOLLOWUP_EXCLUDE_REASON + " TEXT");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 17) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_ATTENDANCE +
                        " ADD COLUMN " + COL_ATT_PHOTO_PATH + " TEXT");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_CUSTOMERS +
                        " ADD COLUMN " + COL_LAST_FOLLOWUP_AT + " TEXT");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 18) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_PAYMENT_METHOD + " TEXT");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 19) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_TRANSACTIONS +
                        " ADD COLUMN " + COL_TRX_RESELLER_ID + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 20) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_CUSTOMERS +
                        " ADD COLUMN " + COL_KOMISI_ADD_TO_PRICE + " INTEGER DEFAULT 1");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_RESELLER_WD +
                        " ADD COLUMN " + COL_WD_EXPENSE_ID + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 21) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_EXPENSES +
                        " ADD COLUMN " + COL_EXPENSE_CATEGORY + " TEXT DEFAULT 'UMUM'");
            } catch (Exception ignored) {}
            try {
                db.execSQL("ALTER TABLE " + TABLE_EXPENSES +
                        " ADD COLUMN " + COL_EXPENSE_LITERS + " REAL DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 22) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_EXPENSES +
                        " ADD COLUMN " + COL_EXPENSE_PCS + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 23) {
            try { db.execSQL(CREATE_TABLE_SALARY); } catch (Exception ignored) {}
            try { db.execSQL(CREATE_TABLE_SALARY_ITEMS); } catch (Exception ignored) {}
        }
        if (oldVersion < 24) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_SALARY_ITEMS +
                        " ADD COLUMN " + COL_SI_ARCHIVED + " INTEGER DEFAULT 0");
            } catch (Exception ignored) {}
        }
        if (oldVersion < 25) {
            try { db.execSQL(CREATE_TABLE_PENDING_TRX); } catch (Exception ignored) {}
        }
        if (oldVersion < 26) {
            installSyncInfra(db, true);   // additive columns + tombstones + backfill
        }
    }

    /** Tables that participate in online sync (all keyed by COL_ID = "_id"). */
    private static final String[] SYNCABLE_TABLES = {
            TABLE_CUSTOMERS, TABLE_PRODUCTS, TABLE_TRANSACTIONS, TABLE_EXPENSES,
            TABLE_USERS, TABLE_ATTENDANCE, TABLE_GALON_STOCK, TABLE_PENDING_TRX,
            TABLE_RESELLER_RATES, TABLE_RESELLER_WD, TABLE_SALARY_ITEMS, TABLE_ORDER_INBOX,
    };

    /**
     * Add sync columns to every syncable table + create the tombstones table.
     * Additive & idempotent (each ALTER is guarded). When {@code backfill} is true
     * (upgrade of an existing install), stamp every existing row with a fresh uuid
     * and mark it dirty so the first sync seeds the branch with current data.
     */
    private void installSyncInfra(SQLiteDatabase db, boolean backfill) {
        for (String table : SYNCABLE_TABLES) {
            tryExec(db, "ALTER TABLE " + table + " ADD COLUMN " + COL_SYNC_UUID + " TEXT");
            tryExec(db, "ALTER TABLE " + table + " ADD COLUMN " + COL_EDITED_AT + " TEXT");
            tryExec(db, "ALTER TABLE " + table + " ADD COLUMN " + COL_SYNCED + " INTEGER DEFAULT 0");
        }
        tryExec(db, "CREATE TABLE IF NOT EXISTS " + TABLE_SYNC_TOMBSTONES + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_TS_ENTITY + " TEXT NOT NULL, "
                + COL_SYNC_UUID + " TEXT NOT NULL, "
                + COL_EDITED_AT + " TEXT, "
                + COL_SYNCED + " INTEGER DEFAULT 0, "
                + "UNIQUE(" + COL_TS_ENTITY + "," + COL_SYNC_UUID + "))");

        if (!backfill) return;
        String now = nowIso();
        for (String table : SYNCABLE_TABLES) {
            Cursor c = null;
            try {
                c = db.rawQuery("SELECT " + COL_ID + " FROM " + table
                        + " WHERE " + COL_SYNC_UUID + " IS NULL", null);
                while (c.moveToNext()) {
                    ContentValues v = new ContentValues();
                    v.put(COL_SYNC_UUID, java.util.UUID.randomUUID().toString());
                    v.put(COL_EDITED_AT, now);
                    v.put(COL_SYNCED, 0);    // dirty → uploaded on first sync
                    db.update(table, v, COL_ID + "=?",
                            new String[]{String.valueOf(c.getLong(0))});
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
    }

    private static void tryExec(SQLiteDatabase db, String sql) {
        try { db.execSQL(sql); } catch (Exception ignored) {}
    }

    private static final SimpleDateFormat SYNC_TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    /** Wall-clock now as the LWW key ("yyyy-MM-dd HH:mm:ss.SSSnnn", 6 decimals to
     *  match the server's microsecond format). */
    public static String nowIso() {
        return SYNC_TS.format(new java.util.Date()) + "000";
    }

    // ---- Sync-aware write helpers (DAOs route writes through these) -----------

    /** Insert + stamp a fresh sync_uuid (if absent), edited_at and synced=0. */
    public long syncInsert(SQLiteDatabase db, String table, ContentValues v) {
        if (!v.containsKey(COL_SYNC_UUID) || v.getAsString(COL_SYNC_UUID) == null) {
            v.put(COL_SYNC_UUID, java.util.UUID.randomUUID().toString());
        }
        v.put(COL_EDITED_AT, nowIso());
        v.put(COL_SYNCED, 0);
        return db.insert(table, null, v);
    }

    /** Update + bump edited_at and mark dirty. */
    public int syncUpdate(SQLiteDatabase db, String table, ContentValues v, String where, String[] args) {
        v.put(COL_EDITED_AT, nowIso());
        v.put(COL_SYNCED, 0);
        return db.update(table, v, where, args);
    }

    /** Hard-delete (preserving existing behavior/cascades) but first record a
     *  tombstone per deleted row so the deletion propagates to other devices. */
    public int syncDelete(SQLiteDatabase db, String table, String entity, String where, String[] args) {
        Cursor c = null;
        try {
            c = db.query(table, new String[]{COL_SYNC_UUID}, where, args, null, null, null);
            while (c.moveToNext()) {
                String su = c.getString(0);
                if (su != null) insertTombstone(db, entity, su);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return db.delete(table, where, args);
    }

    public void insertTombstone(SQLiteDatabase db, String entity, String syncUuid) {
        ContentValues v = new ContentValues();
        v.put(COL_TS_ENTITY, entity);
        v.put(COL_SYNC_UUID, syncUuid);
        v.put(COL_EDITED_AT, nowIso());
        v.put(COL_SYNCED, 0);
        db.insertWithOnConflict(TABLE_SYNC_TOMBSTONES, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("PRAGMA foreign_keys=ON;");
    }
}
