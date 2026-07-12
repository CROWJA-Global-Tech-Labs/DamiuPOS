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
    private static final int DATABASE_VERSION = 52;

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
    /** Server URL of the uploaded photo (set after a successful media upload;
     *  synced so the web dashboard can show it). Reused for customers + attendance. */
    public static final String COL_PHOTO_URL = "photo_url";
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
    /** Tautan reseller: uuid reseller rujukan pelanggan ini → Transaksi Baru auto-pilih afiliasi. */
    public static final String COL_LINKED_RESELLER_UUID = "linked_reseller_uuid";
    /** Koreksi manual "Galon Dipinjam" (offset): ditambahkan ke saldo hasil hitung transaksi
     *  (JUAL PINJAM − KEMBALI). Disetel dari web, disinkron dua-arah. 0 = tanpa koreksi. */
    public static final String COL_GALON_PINJAM_ADJUST = "galon_pinjam_adjust";
    /** 1 = pelanggan wajib ongkir → Transaksi Baru auto-set Ongkos Kirim ke "Per Galon". */
    public static final String COL_WAJIB_ONGKIR = "wajib_ongkir";
    /** 1 = pelanggan ini MILIK perangkat ini (origin = perangkat sendiri di server), 0 = dari
     *  perangkat lain. Semua pelanggan cabang kini ditarik ke tiap HP (branch-wide); flag ini
     *  (dari field pull 'is_mine', BUKAN di-push balik) menyalakan filter "Hanya Pelanggan Saya". */
    public static final String COL_IS_MINE = "is_mine";
    /** Agregat pelanggan yang dihitung SERVER lintas-cabang (semua perangkat), dikirim saat pull —
     *  supaya jumlah transaksi/galon/konsumsi TAMPIL SAMA di tiap HP walau transaksinya ada di
     *  perangkat lain. Field pull-only (tidak di-push balik). Untuk pelanggan MILIK perangkat ini
     *  (is_mine=1) tampilan pakai agregat LOKAL yang lebih segar; salinan perangkat lain pakai ini. */
    public static final String COL_SRV_TRX = "srv_trx";                 // COUNT semua transaksi
    public static final String COL_SRV_ORDERED = "srv_ordered";         // SUM galon JUAL (semua ownership)
    public static final String COL_SRV_BORROWED = "srv_borrowed";       // SUM galon JUAL PINJAM
    public static final String COL_SRV_KEMBALI = "srv_kembali";         // SUM galon KEMBALI
    public static final String COL_SRV_FIRST_JUAL = "srv_first_jual";   // MIN tanggal JUAL
    /** Label perangkat asal pelanggan (nama device, atau "Web") — untuk tag di daftar mobile. */
    public static final String COL_ORIGIN_LABEL = "origin_label";
    /** Saldo komisi reseller dari SERVER (ResellerSaldo — lintas semua perangkat), pull-only.
     *  Kalkulator lokal HP hanya melihat transaksi perangkat sendiri, jadi salinan reseller
     *  perangkat lain wajib pakai angka server ini agar sama dengan dashboard. */
    public static final String COL_SRV_SALDO = "srv_saldo";
    // Harga khusus per produk untuk pelanggan ini: JSON { product_uuid: harga }.
    // NULL/kosong = ikut harga produk standar. Disinkron apa adanya (string JSON).
    public static final String COL_PRODUCT_PRICES = "product_prices";
    /** Multi-lokasi pelanggan: JSON array terurut [{name,lat,lng,wajib_ongkir}] —
     *  entri PERTAMA = lokasi utama. Kosong/"[]" = hanya koordinat legacy (latitude/longitude).
     *  Mirror kompatibilitas: locations[0] disalin ke latitude/longitude/wajib_ongkir saat save.
     *  Saat user menghapus semua lokasi tulis literal "[]" (BUKAN null) agar clear ikut tersinkron
     *  (push meniadakan kolom null). Disinkron apa adanya (string JSON). */
    public static final String COL_LOCATIONS = "locations";
    /** Timestamp saat pelanggan di-"Remove" dari daftar Follow Up. NULL = tidak
     *  dikecualikan. Pelanggan otomatis muncul lagi kalau beli setelah tanggal ini. */
    public static final String COL_FOLLOWUP_EXCLUDED_AT = "followup_excluded_at";
    /** Alasan pelanggan dikeluarkan dari Follow Up (wajib diisi saat remove). */
    public static final String COL_FOLLOWUP_EXCLUDE_REASON = "followup_exclude_reason";
    /** Timestamp terakhir pelanggan di-follow-up (kirim pesan WA follow-up).
     *  Dipakai laporan harian: konsumen yang di-follow-up hari tsb. */
    public static final String COL_LAST_FOLLOWUP_AT = "last_followup_at";
    /** Ditambahkan manual ke Follow Up (dari web/dashboard) terlepas dari riwayat beli.
     *  NULL = tidak ditandai manual. Disinkron agar muncul di daftar Follow Up perangkat. */
    public static final String COL_FOLLOWUP_MANUAL_AT = "followup_manual_at";
    /** Catatan opsional untuk follow-up (mis. konteks saat ditambahkan manual). Disinkron. */
    public static final String COL_FOLLOWUP_NOTE = "followup_note";
    /** "Sudah Order Ulang" (serah-terima marketing): timestamp saat marketing menandai pelanggan
     *  sudah order ulang. Server lalu memindahkan kepemilikan baris ke 'web' sehingga pelanggan
     *  tersinkron ke SEMUA perangkat cabang. NULL = belum diserahterimakan. Disinkron. */
    public static final String COL_HANDED_OVER_AT = "handed_over_at";
    /** Device uuid yang MENANDAI serah-terima. Perangkat menyembunyikan pelanggan yang
     *  ditandainya sendiri (uuid == sync_device_uuid miliknya) dari semua daftar — "terhapus"
     *  di perangkat marketing, tetap tampil di perangkat lain. Disinkron. */
    public static final String COL_HANDED_OVER_BY = "handed_over_by_device";
    /** "Didaftarkan oleh": nama operator (staf clock-in) yang menginput pelanggan ini, direkam
     *  saat DIBUAT — cermin transactions.created_by_name. Disinkron agar dashboard menampilkan
     *  pencatatnya walau pelanggan belum bertransaksi. NULL untuk baris lama. */
    public static final String COL_CUST_CREATED_BY = "created_by_name";

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
    /** Antrian Delivery: status pemrosesan order. PENDING = masih di antrian,
     *  DONE = sudah ditandai Selesai. NULL = tidak diantrikan (mis. KEMBALI). */
    public static final String COL_DELIVERY_STATUS = "delivery_status";
    /** Wall-clock saat order masuk antrian (= waktu transaksi dibuat). Basis
     *  timer real-time & durasi proses. Terpisah dari `tanggal` (bisa di-backdate). */
    public static final String COL_DELIVERY_QUEUED_AT = "delivery_queued_at";
    /** Wall-clock saat staf menandai order Selesai. Durasi = done_at − queued_at. */
    public static final String COL_DELIVERY_DONE_AT = "delivery_done_at";
    /** Token acak per-order untuk link lacak publik web ({base}/track/{token}).
     *  Pelanggan memantau progres + lokasi kurir langsung. Disinkron ke server. */
    public static final String COL_DELIVERY_TOKEN = "delivery_token";
    /** Operator (user lokal) yang membuat/mengantar order — disinkron sebagai
     *  staff_uuid agar halaman lacak menampilkan GPS kurir yang benar. */
    public static final String COL_DELIVERY_STAFF_ID = "delivery_staff_id";
    /** Atribusi terpisah pembuat vs penyelesai transaksi (didenormalisasi namanya) +
     *  kanal pembuatan ('web' bila disusun di dashboard; null = perangkat asal). Disinkron
     *  agar dashboard menampilkan "dibuat oleh … (via …)" dan "diselesaikan oleh … (via …)". */
    public static final String COL_CREATED_BY_NAME = "created_by_name";
    public static final String COL_CREATED_VIA = "created_via";
    public static final String COL_COMPLETED_BY_NAME = "completed_by_name";
    /** Perangkat tujuan hasil "Efisiensikan Delivery" (di-set web = sync_device_uuid HP kurir).
     *  Antrian HP menampilkan order bila delivery_device_uuid NULL (belum di-route, tetap di HP asal)
     *  ATAU = HP ini. NULL = default. origin_device_uuid (atribusi) tak berubah. */
    public static final String COL_DELIVERY_DEVICE_UUID = "delivery_device_uuid";
    /** Lokasi tujuan pengiriman TERPILIH untuk transaksi JUAL (dari multi-lokasi pelanggan).
     *  Skalar tersinkron; 0/NULL = tidak dipilih → navigasi delivery fallback ke koordinat
     *  pelanggan. Nama ikut disimpan agar struk/antrian bisa menampilkan "Kirim ke: …". */
    public static final String COL_DELIVERY_DEST_NAME = "delivery_dest_name";
    public static final String COL_DELIVERY_DEST_LAT = "delivery_dest_lat";
    public static final String COL_DELIVERY_DEST_LNG = "delivery_dest_lng";

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
    /** Pengingat "Pesanan Terjadwal (tiap N hari)" membawa N-nya (dari server). >0 = interval;
     *  0/NULL = order WA biasa atau jadwal mingguan. HP memakainya untuk meredam pengingat yang
     *  sudah tak berlaku (pelanggan sudah order dalam N hari terakhir menurut data lokal). */
    public static final String COL_INBOX_SCHED_INTERVAL = "sched_interval_days";

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
    /** Nama operator yang menginput pengeluaran (atribusi; cermin transactions.created_by_name).
     *  Disinkron dua arah — basis filter "hanya pengeluaran yang dia input" per staf di HP. */
    public static final String COL_EXPENSE_CREATED_BY = "created_by_name";
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
    /** Lokasi GPS saat event absensi (clock in/break/out); null bila izin/lokasi tak tersedia. */
    public static final String COL_ATT_LAT = "latitude";
    public static final String COL_ATT_LNG = "longitude";

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
    public static final String COL_SAL_PROMO_ENABLED = "promo_enabled";   // non-marketing boleh Promosi

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
                    COL_PHOTO_URL + " TEXT, " +
                    COL_LATITUDE + " REAL DEFAULT 0, " +
                    COL_LONGITUDE + " REAL DEFAULT 0, " +
                    COL_IS_RESELLER + " INTEGER DEFAULT 0, " +
                    COL_RESELLER_SINCE + " TEXT, " +
                    COL_KOMISI_ADD_TO_PRICE + " INTEGER DEFAULT 1, " +
                    COL_LINKED_RESELLER_UUID + " TEXT, " +
                    COL_GALON_PINJAM_ADJUST + " INTEGER DEFAULT 0, " +
                    COL_WAJIB_ONGKIR + " INTEGER DEFAULT 0, " +
                    COL_IS_MINE + " INTEGER DEFAULT 1, " +
                    COL_SRV_TRX + " INTEGER DEFAULT 0, " +
                    COL_SRV_ORDERED + " INTEGER DEFAULT 0, " +
                    COL_SRV_BORROWED + " INTEGER DEFAULT 0, " +
                    COL_SRV_KEMBALI + " INTEGER DEFAULT 0, " +
                    COL_SRV_FIRST_JUAL + " TEXT, " +
                    COL_ORIGIN_LABEL + " TEXT, " +
                    COL_SRV_SALDO + " REAL DEFAULT 0, " +
                    COL_FOLLOWUP_EXCLUDED_AT + " TEXT, " +
                    COL_FOLLOWUP_EXCLUDE_REASON + " TEXT, " +
                    COL_LAST_FOLLOWUP_AT + " TEXT, " +
                    COL_FOLLOWUP_MANUAL_AT + " TEXT, " +
                    COL_FOLLOWUP_NOTE + " TEXT, " +
                    COL_PRODUCT_PRICES + " TEXT, " +
                    COL_LOCATIONS + " TEXT, " +
                    COL_HANDED_OVER_AT + " TEXT, " +
                    COL_HANDED_OVER_BY + " TEXT, " +
                    COL_CUST_CREATED_BY + " TEXT, " +
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
                    COL_DELIVERY_STATUS + " TEXT, " +
                    COL_DELIVERY_QUEUED_AT + " TEXT, " +
                    COL_DELIVERY_DONE_AT + " TEXT, " +
                    COL_DELIVERY_TOKEN + " TEXT, " +
                    COL_DELIVERY_STAFF_ID + " INTEGER DEFAULT 0, " +
                    COL_CREATED_BY_NAME + " TEXT, " +
                    COL_CREATED_VIA + " TEXT, " +
                    COL_COMPLETED_BY_NAME + " TEXT, " +
                    COL_DELIVERY_DEVICE_UUID + " TEXT, " +
                    COL_DELIVERY_DEST_NAME + " TEXT, " +
                    COL_DELIVERY_DEST_LAT + " REAL DEFAULT 0, " +
                    COL_DELIVERY_DEST_LNG + " REAL DEFAULT 0, " +
                    COL_TANGGAL + " TEXT DEFAULT (datetime('now','localtime')), " +
                    COL_CATATAN + " TEXT, " +
                    "FOREIGN KEY(" + COL_CUSTOMER_ID + ") REFERENCES " +
                    TABLE_CUSTOMERS + "(" + COL_ID + ") ON DELETE CASCADE" +
                    ");";

    // settings: key/value. edited_at/synced track which shared-config keys are
    // dirty for the app_settings sync path (only whitelisted keys ever go dirty;
    // synced defaults to 1 = clean so device-local keys are never pushed).
    private static final String CREATE_TABLE_SETTINGS =
            "CREATE TABLE " + TABLE_SETTINGS + " (" +
                    COL_SETTING_KEY + " TEXT PRIMARY KEY, " +
                    COL_SETTING_VALUE + " TEXT, " +
                    COL_EDITED_AT + " TEXT, " +
                    COL_SYNCED + " INTEGER DEFAULT 1" +
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
                    COL_INBOX_SCHED_INTERVAL + " INTEGER DEFAULT 0, " +
                    COL_INBOX_RECEIVED_AT + " TEXT DEFAULT (datetime('now','localtime'))" +
                    ");";

    private static final String CREATE_TABLE_EXPENSES =
            "CREATE TABLE " + TABLE_EXPENSES + " (" +
                    COL_EXPENSE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_EXPENSE_NAME + " TEXT NOT NULL, " +
                    COL_EXPENSE_AMOUNT + " REAL NOT NULL DEFAULT 0, " +
                    COL_EXPENSE_PHOTO_PATH + " TEXT, " +
                    COL_PHOTO_URL + " TEXT, " +    // URL nota di server (di-set MediaUploader, disinkron)
                    COL_EXPENSE_NOTE + " TEXT, " +
                    COL_EXPENSE_CATEGORY + " TEXT DEFAULT 'UMUM', " +
                    COL_EXPENSE_LITERS + " REAL DEFAULT 0, " +
                    COL_EXPENSE_PCS + " INTEGER DEFAULT 0, " +
                    COL_EXPENSE_CREATED_BY + " TEXT, " +
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
                    COL_SAL_BANK_HOLDER + " TEXT, " +
                    COL_SAL_PROMO_ENABLED + " INTEGER DEFAULT 0, " +
                    // Sync columns (1:1 dgn staf — sync_uuid = sync_uuid staf).
                    COL_SYNC_UUID + " TEXT, " +
                    COL_EDITED_AT + " TEXT, " +
                    COL_SYNCED + " INTEGER DEFAULT 0" +
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
                    COL_PHOTO_URL + " TEXT, " +
                    COL_ATT_TS + " TEXT DEFAULT (datetime('now','localtime')), " +
                    COL_ATT_LAT + " REAL, " +
                    COL_ATT_LNG + " REAL, " +
                    "FOREIGN KEY(" + COL_ATT_USER_ID + ") REFERENCES " +
                    TABLE_USERS + "(" + COL_USER_ID + ") ON DELETE CASCADE" +
                    ");";

    // ---- Kampanye pelanggan (v37) ---------------------------------------------
    // Campaigns + questions are authored on the web (pull-only here); deliveries are
    // created on this device when a campaign link is shared with the struk, then synced
    // up (two-way). Questions themselves are NOT mirrored — the customer fills them on the
    // public web link; the phone only needs the campaign text + the per-customer link.
    public static final String TABLE_CAMPAIGNS = "campaigns";
    public static final String COL_CAMP_TYPE = "type";            // INFO | KUISIONER
    public static final String COL_CAMP_TITLE = "title";
    public static final String COL_CAMP_BODY = "body_text";
    public static final String COL_CAMP_ACTIVE = "is_active";     // 1 = aktif (boleh dibagikan)
    public static final String COL_CAMP_TARGETS = "target_devices"; // JSON list of device uuids

    public static final String TABLE_CAMPAIGN_DELIVERIES = "campaign_deliveries";
    public static final String COL_CD_CAMPAIGN_ID = "campaign_id"; // local ref → campaigns._id (→ campaign_uuid)
    public static final String COL_CD_CUSTOMER_ID = "customer_id"; // local ref → customers._id (→ customer_uuid)
    public static final String COL_CD_TOKEN = "token";
    public static final String COL_CD_SENT_AT = "sent_at";
    public static final String COL_CD_CLICKED_AT = "clicked_at";
    public static final String COL_CD_RESPONDED_AT = "responded_at";
    public static final String COL_CD_REACTION = "reaction";       // UP | DOWN (web-set)
    public static final String COL_CD_REACTED_AT = "reacted_at";

    private static final String CREATE_TABLE_CAMPAIGNS =
            "CREATE TABLE " + TABLE_CAMPAIGNS + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_CAMP_TYPE + " TEXT, " +
                    COL_CAMP_TITLE + " TEXT, " +
                    COL_CAMP_BODY + " TEXT, " +
                    COL_CAMP_ACTIVE + " INTEGER DEFAULT 0, " +
                    COL_CAMP_TARGETS + " TEXT, " +
                    COL_CREATED_AT + " TEXT, " +
                    COL_SYNC_UUID + " TEXT, " +
                    COL_EDITED_AT + " TEXT, " +
                    COL_SYNCED + " INTEGER DEFAULT 0" +
                    ");";

    private static final String CREATE_TABLE_CAMPAIGN_DELIVERIES =
            "CREATE TABLE " + TABLE_CAMPAIGN_DELIVERIES + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_CD_CAMPAIGN_ID + " INTEGER DEFAULT -1, " +
                    COL_CD_CUSTOMER_ID + " INTEGER DEFAULT -1, " +
                    COL_CD_TOKEN + " TEXT, " +
                    COL_CD_SENT_AT + " TEXT, " +
                    COL_CD_CLICKED_AT + " TEXT, " +
                    COL_CD_RESPONDED_AT + " TEXT, " +
                    COL_CD_REACTION + " TEXT, " +
                    COL_CD_REACTED_AT + " TEXT, " +
                    COL_SYNC_UUID + " TEXT, " +
                    COL_EDITED_AT + " TEXT, " +
                    COL_SYNCED + " INTEGER DEFAULT 0" +
                    ");";

    // Gift pelanggan — hadiah (produk/item custom) yang di-assign dari web/dashboard, ditarik ke
    // tiap perangkat cabang (branch-wide). Pending sampai sebuah transaksi JUAL pelanggan itu
    // meng-klaim-nya (redeemed_at terisi + redeemed_transaction_uuid menunjuk struk-nya). Dua-arah:
    // pull assignment dari web, push redemption dari HP. item_name adalah snapshot (produk bisa
    // ganti nama); product_uuid & redeemed_transaction_uuid disimpan sebagai string mentah (tak
    // di-resolve lokal) — transaksi device-isolated jadi struk-nya cuma ada di HP yang menjual.
    public static final String TABLE_CUSTOMER_GIFTS = "customer_gifts";
    public static final String COL_GIFT_CUSTOMER_ID = "customer_id";   // local ref → customers._id (→ customer_uuid)
    public static final String COL_GIFT_ITEM_TYPE = "item_type";        // product | custom
    public static final String COL_GIFT_PRODUCT_UUID = "product_uuid";  // raw (tak di-resolve lokal)
    public static final String COL_GIFT_ITEM_NAME = "item_name";        // snapshot nama item
    public static final String COL_GIFT_QTY = "qty";
    public static final String COL_GIFT_REASON = "reason";
    public static final String COL_GIFT_REDEEMED_AT = "redeemed_at";    // NULL = belum diberikan
    public static final String COL_GIFT_REDEEMED_TRX_UUID = "redeemed_transaction_uuid"; // raw uuid struk
    public static final String COL_GIFT_REDEEMED_BY = "redeemed_by_name";

    private static final String CREATE_TABLE_CUSTOMER_GIFTS =
            "CREATE TABLE " + TABLE_CUSTOMER_GIFTS + " (" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_GIFT_CUSTOMER_ID + " INTEGER DEFAULT -1, " +
                    COL_GIFT_ITEM_TYPE + " TEXT, " +
                    COL_GIFT_PRODUCT_UUID + " TEXT, " +
                    COL_GIFT_ITEM_NAME + " TEXT, " +
                    COL_GIFT_QTY + " INTEGER DEFAULT 1, " +
                    COL_GIFT_REASON + " TEXT, " +
                    COL_GIFT_REDEEMED_AT + " TEXT, " +
                    COL_GIFT_REDEEMED_TRX_UUID + " TEXT, " +
                    COL_GIFT_REDEEMED_BY + " TEXT, " +
                    COL_CREATED_AT + " TEXT, " +
                    COL_SYNC_UUID + " TEXT, " +
                    COL_EDITED_AT + " TEXT, " +
                    COL_SYNCED + " INTEGER DEFAULT 0" +
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
        db.execSQL(CREATE_TABLE_CAMPAIGNS);
        db.execSQL(CREATE_TABLE_CAMPAIGN_DELIVERIES);
        db.execSQL(CREATE_TABLE_CUSTOMER_GIFTS);
        installSyncInfra(db, false);
        createIndexes(db);
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
        if (oldVersion < 27) {
            // salary_config joins sync (1:1 per staff). Add sync columns + backfill
            // sync_uuid = the staff's sync_uuid (deterministic → no cross-device
            // duplicate; server enforces unique(branch, staff_uuid)).
            tryExec(db, "ALTER TABLE " + TABLE_SALARY + " ADD COLUMN " + COL_SYNC_UUID + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_SALARY + " ADD COLUMN " + COL_EDITED_AT + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_SALARY + " ADD COLUMN " + COL_SYNCED + " INTEGER DEFAULT 0");
            tryExec(db, "UPDATE " + TABLE_SALARY + " SET " + COL_SYNC_UUID
                    + " = (SELECT u." + COL_SYNC_UUID + " FROM " + TABLE_USERS + " u WHERE u."
                    + COL_ID + " = " + TABLE_SALARY + "." + COL_SAL_USER_ID + "), "
                    + COL_EDITED_AT + " = '" + nowIso() + "', " + COL_SYNCED + " = 0"
                    + " WHERE " + COL_SYNC_UUID + " IS NULL");

            // settings: add sync-tracking columns for the app_settings (shared
            // config) path. Default synced=1 (clean) so existing device-local
            // keys are never pushed until a whitelisted key is changed.
            tryExec(db, "ALTER TABLE " + TABLE_SETTINGS + " ADD COLUMN " + COL_EDITED_AT + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_SETTINGS + " ADD COLUMN " + COL_SYNCED + " INTEGER DEFAULT 1");
        }
        if (oldVersion < 28) {
            // photo_url: server URL of the uploaded customer photo / attendance selfie.
            // Synced so the web dashboard can show images. Empty until MediaUploader
            // uploads the local file; no backfill needed (existing rows re-upload on
            // next sync because photo_url is empty while photo_path is set).
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_PHOTO_URL + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_ATTENDANCE + " ADD COLUMN " + COL_PHOTO_URL + " TEXT");
        }
        if (oldVersion < 29) {
            // Antrian Delivery: per-order fulfillment tracking on transactions.
            // Additive; existing rows keep NULL status (not queued). Synced so the
            // web dashboard sees the queue + processing-time data.
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_STATUS + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_QUEUED_AT + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_DONE_AT + " TEXT");
        }
        if (oldVersion < 30) {
            // Link lacak pengiriman publik: token per-order + operator (kurir).
            // Additive; baris lama tetap NULL/0 (tidak punya link lacak). Disinkron
            // agar dashboard menyajikan /track/{token} + GPS kurir ke pelanggan.
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_TOKEN + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_STAFF_ID + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 31) {
            // Atribusi terpisah: nama pembuat (operator/admin) & penyelesai (kurir) transaksi,
            // + kanal pembuatan ('web' bila disusun di dashboard; null = perangkat ini). Additif;
            // baris lama NULL. Disinkron agar dashboard menampilkan "dibuat/diselesaikan oleh".
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_CREATED_BY_NAME + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_CREATED_VIA + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_COMPLETED_BY_NAME + " TEXT");
        }
        if (oldVersion < 32) {
            // Lokasi GPS pada event absensi (selfie clock in/out) → dashboard tampil lokasi, bukan "Tanpa lokasi".
            tryExec(db, "ALTER TABLE " + TABLE_ATTENDANCE + " ADD COLUMN " + COL_ATT_LAT + " REAL");
            tryExec(db, "ALTER TABLE " + TABLE_ATTENDANCE + " ADD COLUMN " + COL_ATT_LNG + " REAL");
        }
        if (oldVersion < 33) {
            // Follow Up manual: pelanggan ditandai manual dari web (+ catatan opsional). Additif;
            // baris lama NULL. Disinkron agar entri manual muncul di daftar Follow Up perangkat.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_FOLLOWUP_MANUAL_AT + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_FOLLOWUP_NOTE + " TEXT");
        }
        if (oldVersion < 34) {
            // Re-push STOK GALON ke dashboard. Sebagian entri galon_stock lama tak pernah sampai ke
            // server (web menampilkan 0 baris asal-perangkat → saldo web ≠ HP). Pastikan tiap baris
            // punya sync_uuid (defensif; harusnya sudah sejak v26), lalu tandai ulang "dirty" agar
            // sinkron berikutnya meng-upload-nya. Idempoten: baris yang sudah ada di server di-upsert
            // via uuid (last-write-wins), jadi aman dijalankan ulang.
            Cursor gc = null;
            try {
                gc = db.rawQuery("SELECT " + COL_STOCK_ID + " FROM " + TABLE_GALON_STOCK
                        + " WHERE " + COL_SYNC_UUID + " IS NULL", null);
                String nowTs = nowIso();
                while (gc.moveToNext()) {
                    ContentValues v = new ContentValues();
                    v.put(COL_SYNC_UUID, java.util.UUID.randomUUID().toString());
                    v.put(COL_EDITED_AT, nowTs);
                    db.update(TABLE_GALON_STOCK, v, COL_STOCK_ID + "=?",
                            new String[]{String.valueOf(gc.getLong(0))});
                }
            } catch (Exception ignored) {
            } finally {
                if (gc != null) gc.close();
            }
            tryExec(db, "UPDATE " + TABLE_GALON_STOCK + " SET " + COL_SYNCED + " = 0");
        }
        if (oldVersion < 35) {
            // Harga khusus per produk untuk tiap pelanggan (JSON { product_uuid: harga }; default dari
            // harga produk, bisa diedit). Additif; baris lama NULL = ikut harga produk standar.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_PRODUCT_PRICES + " TEXT");
        }
        if (oldVersion < 36) {
            // Foto nota pengeluaran kini diunggah ke server (MediaUploader) lalu URL-nya disinkron ke
            // dashboard. Tambah kolom photo_url; baris lama dengan photo_path akan terunggah otomatis.
            tryExec(db, "ALTER TABLE " + TABLE_EXPENSES + " ADD COLUMN " + COL_PHOTO_URL + " TEXT");
        }
        if (oldVersion < 37) {
            // Kampanye pelanggan: campaigns ditarik dari web (pull-only), deliveries dibuat di HP
            // saat link kampanye dibagikan bersama struk lalu disinkron. Tabel baru (additif & aman
            // untuk perangkat live — tak menyentuh data yang ada). Sync infra dipasang di bawah.
            tryExec(db, CREATE_TABLE_CAMPAIGNS);
            tryExec(db, CREATE_TABLE_CAMPAIGN_DELIVERIES);
        }
        if (oldVersion < 38) {
            // Promosi untuk non-marketing: flag per staf (disinkron dari salary_configs web).
            tryExec(db, "ALTER TABLE " + TABLE_SALARY + " ADD COLUMN " + COL_SAL_PROMO_ENABLED + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 39) {
            // "Sudah Order Ulang" (serah-terima pelanggan marketing → perangkat lain): pelanggan
            // ditandai lalu server memindahkan kepemilikannya ke 'web' (tersinkron ke semua
            // perangkat); perangkat penanda menyembunyikannya dari daftar (lihat CustomerDao).
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_HANDED_OVER_AT + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_HANDED_OVER_BY + " TEXT");
        }
        if (oldVersion < 40) {
            // Tautan reseller: uuid reseller rujukan pelanggan → Transaksi Baru auto-pilih afiliasi.
            // Additif; baris lama NULL = tak ditautkan. Disinkron dua-arah (customers spec).
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_LINKED_RESELLER_UUID + " TEXT");
        }
        if (oldVersion < 41) {
            // N (interval_days) pengingat "Pesanan Terjadwal (tiap N hari)" dari server → HP bisa
            // meredam pengingat yang sudah tak berlaku. Additif; 0 = bukan interval.
            tryExec(db, "ALTER TABLE " + TABLE_ORDER_INBOX + " ADD COLUMN " + COL_INBOX_SCHED_INTERVAL + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 42) {
            // Koreksi manual "Galon Dipinjam" (offset) dari web → ditambahkan ke saldo pinjam. Additif.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_GALON_PINJAM_ADJUST + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 43) {
            // Pelanggan kini branch-wide (semua ditarik ke tiap HP). is_mine (dari pull) menyalakan
            // filter "Hanya Pelanggan Saya". Baris lama default 1 (dulu HP hanya punya miliknya + web);
            // SyncEngine memicu full re-pull sekali (needsCustomerRepull) supaya is_mine terisi benar
            // dan pelanggan perangkat lain ikut tertarik.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_IS_MINE + " INTEGER DEFAULT 1");
        }
        if (oldVersion < 44) {
            // Agregat pelanggan lintas-perangkat dari server (pull-only) + label perangkat asal →
            // jumlah transaksi/galon/konsumsi tampil SAMA di tiap HP walau transaksinya di device lain.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_SRV_TRX + " INTEGER DEFAULT 0");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_SRV_ORDERED + " INTEGER DEFAULT 0");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_SRV_BORROWED + " INTEGER DEFAULT 0");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_SRV_KEMBALI + " INTEGER DEFAULT 0");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_SRV_FIRST_JUAL + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_ORIGIN_LABEL + " TEXT");
        }
        if (oldVersion < 45) {
            // Flag "Wajib Ongkir" pelanggan → Transaksi Baru default Ongkos Kirim = Per Galon.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_WAJIB_ONGKIR + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 46) {
            // Indeks sekunder — sebelumnya NOL indeks, jadi tiap sync (WHERE sync_uuid) + tiap
            // agregat pelanggan (JOIN transactions ON customer_id) full-scan. Berat di HP lama.
            createIndexes(db);
        }
        if (oldVersion < 47) {
            // "Efisiensikan Delivery": perangkat tujuan order (di-route dari web). Additif; baris lama
            // NULL = belum di-route. Antrian HP memfilter (delivery_device_uuid NULL OR = HP ini).
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_DEVICE_UUID + " TEXT");
        }
        if (oldVersion < 48) {
            // Saldo komisi reseller otoritatif dari server (pull-only) → saldo di HP == dashboard
            // walau transaksi afiliasinya tercatat di perangkat lain.
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_SRV_SALDO + " REAL DEFAULT 0");
        }
        if (oldVersion < 49) {
            // Atribusi pembuat pengeluaran → HP memfilter daftar per operator yang login.
            tryExec(db, "ALTER TABLE " + TABLE_EXPENSES + " ADD COLUMN " + COL_EXPENSE_CREATED_BY + " TEXT");
        }
        if (oldVersion < 50) {
            // Multi-lokasi pelanggan (JSON array, entri pertama = utama) + lokasi tujuan
            // pengiriman terpilih per transaksi JUAL. Additif; baris lama NULL/0 = pakai
            // koordinat legacy pelanggan (lazy synthesis "Kediaman" saat baca).
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_LOCATIONS + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_DEST_NAME + " TEXT");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_DEST_LAT + " REAL DEFAULT 0");
            tryExec(db, "ALTER TABLE " + TABLE_TRANSACTIONS + " ADD COLUMN " + COL_DELIVERY_DEST_LNG + " REAL DEFAULT 0");
        }
        if (oldVersion < 51) {
            // "Didaftarkan oleh": rekam operator pencatat saat pelanggan dibuat (cermin
            // transactions.created_by_name) → dashboard tak lagi kosong untuk lead marketing.
            // Additif; baris lama NULL (dashboard jatuh ke staf transaksi pertama / perangkat asal).
            tryExec(db, "ALTER TABLE " + TABLE_CUSTOMERS + " ADD COLUMN " + COL_CUST_CREATED_BY + " TEXT");
        }
        if (oldVersion < 52) {
            // Gift pelanggan (hadiah produk/custom di-assign dari web, branch-wide). Tabel baru,
            // additif & aman untuk perangkat live. Sync columns sudah inline di CREATE; indeks
            // uuid ditambah di createIndexes (dipanggil idempoten di bawah bila belum ada).
            tryExec(db, CREATE_TABLE_CUSTOMER_GIFTS);
            tryExec(db, "CREATE INDEX IF NOT EXISTS idx_gifts_uuid ON " + TABLE_CUSTOMER_GIFTS + "(" + COL_SYNC_UUID + ")");
            tryExec(db, "CREATE INDEX IF NOT EXISTS idx_gifts_customer ON " + TABLE_CUSTOMER_GIFTS + "(" + COL_GIFT_CUSTOMER_ID + ")");
        }
    }

    /**
     * Indeks sekunder untuk semua jalur panas. Tanpa ini setiap {@code WHERE}/{@code JOIN}/
     * {@code GROUP BY} pada kolom non-{@code _id} adalah full-table scan — penyebab utama sync &
     * daftar lambat di HP lama (A325F/A750GN, eMMC lambat) dengan ribuan transaksi. Semua pakai
     * {@code IF NOT EXISTS} (idempoten) + {@link #tryExec} (abaikan tabel/kolom lama yang belum ada).
     * Dipanggil dari {@code onCreate} & upgrade v46.
     */
    private void createIndexes(SQLiteDatabase db) {
        // sync_uuid tiap tabel syncable — dipakai per-baris SETIAP siklus sync (applyRows lookup,
        // localIdForUuid ref-resolve, flushPush, tombstone). Ini offender terbesar.
        for (String t : SYNCABLE_TABLES) {
            tryExec(db, "CREATE INDEX IF NOT EXISTS idx_" + t + "_uuid ON " + t + "(" + COL_SYNC_UUID + ")");
        }
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_campaigns_uuid ON campaigns(" + COL_SYNC_UUID + ")");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_campdeliv_uuid ON campaign_deliveries(" + COL_SYNC_UUID + ")");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_gifts_uuid ON " + TABLE_CUSTOMER_GIFTS + "(" + COL_SYNC_UUID + ")");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_gifts_customer ON " + TABLE_CUSTOMER_GIFTS + "(" + COL_GIFT_CUSTOMER_ID + ")");

        // transactions.customer_id / reseller_id — tulang punggung agregat pelanggan + cascade delete.
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_trx_customer ON " + TABLE_TRANSACTIONS + "(" + COL_CUSTOMER_ID + ")");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_trx_reseller ON " + TABLE_TRANSACTIONS + "(" + COL_TRX_RESELLER_ID + ")");
        // transactions.type — semua agregat dashboard/laporan filter type='JUAL'/'KEMBALI'.
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_trx_type ON " + TABLE_TRANSACTIONS + "(" + COL_TYPE + ")");
        // Partial index: hanya baris kotor (push scan) & antrean delivery (badge) — kecil & tepat sasaran.
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_trx_dirty ON " + TABLE_TRANSACTIONS + "(" + COL_SYNCED + ") WHERE " + COL_SYNCED + "=0");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_cust_dirty ON " + TABLE_CUSTOMERS + "(" + COL_SYNCED + ") WHERE " + COL_SYNCED + "=0");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_exp_dirty ON " + TABLE_EXPENSES + "(" + COL_SYNCED + ") WHERE " + COL_SYNCED + "=0");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_att_dirty ON " + TABLE_ATTENDANCE + "(" + COL_SYNCED + ") WHERE " + COL_SYNCED + "=0");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_inbox_dirty ON " + TABLE_ORDER_INBOX + "(" + COL_SYNCED + ") WHERE " + COL_SYNCED + "=0");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_trx_delivery ON " + TABLE_TRANSACTIONS + "(" + COL_DELIVERY_STATUS + ") WHERE " + COL_DELIVERY_STATUS + " IS NOT NULL");
        // attendance(user_id, ts) — tiap insert transaksi cek hasInToday + semua query shift.
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_att_user_ts ON " + TABLE_ATTENDANCE + "(" + COL_ATT_USER_ID + "," + COL_ATT_TS + ")");
        // order_inbox.status / expenses.category / salary_items.user_id.
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_inbox_status ON " + TABLE_ORDER_INBOX + "(" + COL_INBOX_STATUS + ")");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_exp_category ON " + TABLE_EXPENSES + "(" + COL_EXPENSE_CATEGORY + ")");
        tryExec(db, "CREATE INDEX IF NOT EXISTS idx_salitem_user ON " + TABLE_SALARY_ITEMS + "(" + COL_SI_USER_ID + ")");
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
        // Satu transaksi untuk SELURUH backfill: tanpa ini tiap UPDATE = 1 fsync (ribuan fsync di
        // eMMC lambat = first-launch bisa hang berpuluh detik pada HP lama).
        db.beginTransaction();
        try {
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
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
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
