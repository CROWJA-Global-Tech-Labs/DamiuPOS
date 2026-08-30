package com.crowja.damiupos.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Offline-first two-way sync. Local SQLite is the source of truth; this pushes dirty
 * rows + tombstones, then pulls deltas and applies them (last-write-wins on edited_at).
 *
 * <p>Cross-row references travel as *_uuid: a transaction's local {@code customer_id}
 * is translated to the customer's {@code sync_uuid} on push, and back to the local id
 * on pull. Specs are ordered so referenced entities are applied before referencing ones.
 */
public class SyncEngine {

    /** A foreign key carried across the wire as a uuid. */
    private static final class Ref {
        final String localCol;     // e.g. "customer_id"
        final String serverField;  // e.g. "customer_uuid"
        final String refTable;     // e.g. customers
        Ref(String localCol, String serverField, String refTable) {
            this.localCol = localCol; this.serverField = serverField; this.refTable = refTable;
        }
    }

    private static final class Spec {
        final String entity;
        final String table;
        final String[] dataCols;   // scalar columns (identical name phone & server)
        final Ref[] refs;
        final String idCol;        // local primary-key column (default "_id")
        Spec(String entity, String table, String[] dataCols, Ref[] refs) {
            this(entity, table, dataCols, refs, DatabaseHelper.COL_ID);
        }
        Spec(String entity, String table, String[] dataCols, Ref[] refs, String idCol) {
            this.entity = entity; this.table = table; this.dataCols = dataCols;
            this.refs = refs; this.idCol = idCol;
        }
    }

    private static final Ref[] NO_REFS = new Ref[0];

    // Dependency order: leaf entities first so refs resolve on pull.
    private static final Spec[] SPECS = {
            new Spec("customers", DatabaseHelper.TABLE_CUSTOMERS, new String[]{
                    DatabaseHelper.COL_NAME, DatabaseHelper.COL_PHONE, DatabaseHelper.COL_ADDRESS,
                    DatabaseHelper.COL_LATITUDE, DatabaseHelper.COL_LONGITUDE,
                    DatabaseHelper.COL_IS_RESELLER, DatabaseHelper.COL_RESELLER_SINCE,
                    DatabaseHelper.COL_KOMISI_ADD_TO_PRICE, DatabaseHelper.COL_LINKED_RESELLER_UUID,
                    DatabaseHelper.COL_GALON_PINJAM_ADJUST, DatabaseHelper.COL_WAJIB_ONGKIR,
                    DatabaseHelper.COL_FOLLOWUP_EXCLUDED_AT,
                    DatabaseHelper.COL_FOLLOWUP_EXCLUDE_REASON, DatabaseHelper.COL_LAST_FOLLOWUP_AT,
                    DatabaseHelper.COL_FOLLOWUP_MANUAL_AT, DatabaseHelper.COL_FOLLOWUP_NOTE,
                    DatabaseHelper.COL_PRODUCT_PRICES, DatabaseHelper.COL_LOCATIONS,
                    DatabaseHelper.COL_PHONES, DatabaseHelper.COL_CUST_ASSIGN_DEVICE,
                    DatabaseHelper.COL_HANDED_OVER_AT, DatabaseHelper.COL_HANDED_OVER_BY,
                    DatabaseHelper.COL_CUST_CREATED_BY,
                    DatabaseHelper.COL_ISSUE_FLAGS, DatabaseHelper.COL_ISSUE_NOTE,
                    DatabaseHelper.COL_ISSUE_REPORTED_AT, DatabaseHelper.COL_ISSUE_REPORTED_BY,
                    DatabaseHelper.COL_ISSUE_RESOLVED_AT,
                    // "Kunjungi Urgent" — DUA ARAH: web menandai, HP menyelesaikan kunjungan.
                    DatabaseHelper.COL_VISIT_URGENT_AT, DatabaseHelper.COL_VISIT_URGENT_BY,
                    DatabaseHelper.COL_VISIT_URGENT_DONE_AT, DatabaseHelper.COL_VISIT_URGENT_DONE_REASON,
                    DatabaseHelper.COL_PRIORITY_AT, DatabaseHelper.COL_PRIORITY_REASON,
                    DatabaseHelper.COL_PRIORITY_BY, DatabaseHelper.COL_PRIORITY_CLEARED_AT,
                    // "Kirim WA Perkenalan" (Pelanggan Promosi) — server-authoritative, di-set saat
                    // endpoint /intro-wa dipanggil (web ATAU HP). Pull-only: server men-skip kolom ini
                    // dari push (lihat SyncController::fillColumns di web).
                    DatabaseHelper.COL_INTRO_WA_SENT_AT,
                    DatabaseHelper.COL_PHOTO_URL, DatabaseHelper.COL_CREATED_AT,
                    // "Bonus Beli N Gratis 1" — di-set DI WEB saja; HP tidak punya kontrol UI untuk
                    // mengubahnya (dua-arah secara mekanis, sama seperti is_reseller/wajib_ongkir,
                    // tapi tak ada tombol di app untuk menulisnya).
                    DatabaseHelper.COL_CUST_BONUS_ENABLED,
            }, NO_REFS),

            new Spec("products", DatabaseHelper.TABLE_PRODUCTS, new String[]{
                    DatabaseHelper.COL_PRODUCT_NAME, DatabaseHelper.COL_PRODUCT_SLUG,
                    DatabaseHelper.COL_HARGA_JUAL,
                    DatabaseHelper.COL_HARGA_MODAL, DatabaseHelper.COL_COLOR,
                    DatabaseHelper.COL_CREATED_AT,
            }, NO_REFS),

            // Staff (PIN operators). pin_hash = PIN yang di-set/reset dari WEB (SHA-256, pull-only):
            // otoritatif bila terisi, NULL = fallback ke PIN lokal HP. PIN lokal sendiri tak di-push.
            new Spec("staff", DatabaseHelper.TABLE_USERS, new String[]{
                    DatabaseHelper.COL_USER_NAME, DatabaseHelper.COL_USER_ROLE,
                    DatabaseHelper.COL_USER_ACTIVE, DatabaseHelper.COL_USER_PIN_HASH,
                    DatabaseHelper.COL_USER_CREATED_AT,
            }, NO_REFS),

            new Spec("expenses", DatabaseHelper.TABLE_EXPENSES, new String[]{
                    DatabaseHelper.COL_EXPENSE_NAME, DatabaseHelper.COL_EXPENSE_AMOUNT,
                    DatabaseHelper.COL_EXPENSE_NOTE, DatabaseHelper.COL_EXPENSE_CATEGORY,
                    DatabaseHelper.COL_EXPENSE_LITERS, DatabaseHelper.COL_EXPENSE_PCS,
                    DatabaseHelper.COL_EXPENSE_CREATED_BY,
                    DatabaseHelper.COL_PHOTO_URL,
                    DatabaseHelper.COL_EXPENSE_CREATED_AT,
            }, NO_REFS),

            new Spec("galon_stock", DatabaseHelper.TABLE_GALON_STOCK, new String[]{
                    DatabaseHelper.COL_STOCK_JUMLAH, DatabaseHelper.COL_STOCK_CATATAN,
                    DatabaseHelper.COL_STOCK_TANGGAL,
            }, NO_REFS),

            new Spec("attendance", DatabaseHelper.TABLE_ATTENDANCE, new String[]{
                    DatabaseHelper.COL_ATT_EVENT, DatabaseHelper.COL_ATT_TS,
                    DatabaseHelper.COL_PHOTO_URL,
                    DatabaseHelper.COL_ATT_LAT, DatabaseHelper.COL_ATT_LNG,
                    DatabaseHelper.COL_ATT_OUT_OF_RADIUS, DatabaseHelper.COL_ATT_DISTANCE_M,
                    DatabaseHelper.COL_ATT_RADIUS_REASON,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_ATT_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),

            new Spec("reseller_rates", DatabaseHelper.TABLE_RESELLER_RATES, new String[]{
                    DatabaseHelper.COL_RR_KOMISI,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_RR_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_RR_PRODUCT_ID, "product_uuid", DatabaseHelper.TABLE_PRODUCTS),
            }),

            new Spec("reseller_withdrawals", DatabaseHelper.TABLE_RESELLER_WD, new String[]{
                    DatabaseHelper.COL_WD_TYPE, DatabaseHelper.COL_WD_GALON_QTY,
                    DatabaseHelper.COL_WD_AMOUNT, DatabaseHelper.COL_WD_NOTE,
                    DatabaseHelper.COL_WD_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_WD_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_WD_EXPENSE_ID, "expense_uuid", DatabaseHelper.TABLE_EXPENSES),
            }),

            new Spec("salary_items", DatabaseHelper.TABLE_SALARY_ITEMS, new String[]{
                    DatabaseHelper.COL_SI_KIND, DatabaseHelper.COL_SI_LABEL,
                    DatabaseHelper.COL_SI_QTY, DatabaseHelper.COL_SI_RATE,
                    DatabaseHelper.COL_SI_SORT, DatabaseHelper.COL_SI_ARCHIVED,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_SI_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),

            // salary_config is keyed locally by user_id (no _id); its sync_uuid is
            // the staff's sync_uuid (1:1). Server enforces unique(branch, staff_uuid).
            new Spec("salary_configs", DatabaseHelper.TABLE_SALARY, new String[]{
                    DatabaseHelper.COL_SAL_POKOK_ENABLED, DatabaseHelper.COL_SAL_POKOK,
                    DatabaseHelper.COL_SAL_TUNJ_HARIAN, DatabaseHelper.COL_SAL_LEMBUR_RATE,
                    DatabaseHelper.COL_SAL_ANGSURAN_SISA, DatabaseHelper.COL_SAL_ANGSURAN_BULAN,
                    DatabaseHelper.COL_SAL_JABATAN, DatabaseHelper.COL_SAL_STATUS,
                    DatabaseHelper.COL_SAL_BANK_NAME, DatabaseHelper.COL_SAL_BANK_NO,
                    DatabaseHelper.COL_SAL_BANK_HOLDER, DatabaseHelper.COL_SAL_PROMO_ENABLED,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_SAL_USER_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }, DatabaseHelper.COL_SAL_USER_ID),

            new Spec("transactions", DatabaseHelper.TABLE_TRANSACTIONS, new String[]{
                    DatabaseHelper.COL_TYPE, DatabaseHelper.COL_JUMLAH_GALON,
                    DatabaseHelper.COL_HARGA_PER_GALON, DatabaseHelper.COL_TOTAL_HARGA,
                    DatabaseHelper.COL_ONGKIR, DatabaseHelper.COL_ONGKIR_TYPE,
                    DatabaseHelper.COL_ITEMS_JSON, DatabaseHelper.COL_GALON_OWNERSHIP,
                    DatabaseHelper.COL_HARGA_BOTOL, DatabaseHelper.COL_PAYMENT_METHOD,
                    DatabaseHelper.COL_DELIVERY_STATUS, DatabaseHelper.COL_DELIVERY_QUEUED_AT,
                    // Pesanan Tertunda — cermin App\Support\TertundaSchedule di web (dua arah: HP bisa
                    // membuatnya, web bisa menjadwalkan ulang/melanjutkannya).
                    DatabaseHelper.COL_DELIVERY_TERTUNDA_AT, DatabaseHelper.COL_DELIVERY_TERTUNDA_RESUME_AT,
                    DatabaseHelper.COL_DELIVERY_DONE_AT, DatabaseHelper.COL_DELIVERY_TOKEN,
                    // "Sedang dikerjakan" (▶ Jalankan) + stempel berhentinya — DITULIS HP, dua arah.
                    // Dua stempel karena push membuang kolom null (berhenti tak bisa dikirim NULL).
                    DatabaseHelper.COL_DELIVERY_STARTED_AT, DatabaseHelper.COL_DELIVERY_STARTED_CLEARED_AT,
                    DatabaseHelper.COL_CREATED_BY_NAME, DatabaseHelper.COL_CREATED_VIA,
                    DatabaseHelper.COL_COMPLETED_BY_NAME, DatabaseHelper.COL_CREDIT_GRACE_UNTIL,
                    DatabaseHelper.COL_DELIVERY_DEVICE_UUID,
                    // Urutan antar MANUAL (Strategi Pengiriman) — server menolaknya dari push HP,
                    // jadi praktis pull-only: HP hanya MEMBACA urutan yang disusun operator di web.
                    DatabaseHelper.COL_DELIVERY_SEQ,
                    DatabaseHelper.COL_DELIVERY_DEST_NAME, DatabaseHelper.COL_DELIVERY_DEST_LAT,
                    DatabaseHelper.COL_DELIVERY_DEST_LNG, DatabaseHelper.COL_ASSIGNED_DEVICE_UUID,
                    // ⚡ Prioritas pengiriman (+ alasan + penanda) — server-authoritative: server
                    // menolak nilai ini dari push HP, jadi praktis pull-only walau ikut dataCols.
                    DatabaseHelper.COL_DELIVERY_PRIORITY_AT, DatabaseHelper.COL_DELIVERY_PRIORITY_REASON,
                    DatabaseHelper.COL_DELIVERY_PRIORITY_BY,
                    // "Pesanan Terbuka" (lelang) — server-authoritative (distempel storePending/
                    // resumeDueTertunda di web), pull-only walau ikut dataCols sama seperti prioritas.
                    DatabaseHelper.COL_DELIVERY_OPEN_DISPATCH_AT,
                    // Badge ✏️/🗑️ "sudah pernah diubah" di kartu antrian — server-authoritative
                    // (di-skip dari fillColumns push, lihat komentar konstantanya), pull-only.
                    DatabaseHelper.COL_LAST_MANUAL_EDIT_AT, DatabaseHelper.COL_VOID_REQUEST_PENDING_AT,
                    DatabaseHelper.COL_TANGGAL, DatabaseHelper.COL_CATATAN,
                    // BUKTI SELESAI: hanya URL yang disinkron — photo_path lokal-saja (pola persis
                    // attendance/expenses: path tak pernah meninggalkan perangkat).
                    DatabaseHelper.COL_PHOTO_URL,
                    // ID transaksi unik struk (<KODE>-DDMMYY-<COUNTER>) — HP menghitungnya SENDIRI
                    // offline (counter lokal per-perangkat, lihat TransactionDao.insert()) lalu
                    // push; server hanya mengisi bila baris ini lahir DI WEB (Transaction::booted).
                    // Dua arah agar HP juga melihat receipt_no baris yang dibuat di web/perangkat lain.
                    DatabaseHelper.COL_RECEIPT_NO,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_TRX_PRODUCT_ID, "product_uuid", DatabaseHelper.TABLE_PRODUCTS),
                    new Ref(DatabaseHelper.COL_TRX_RESELLER_ID, "reseller_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_DELIVERY_STAFF_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),

            // After transactions so its trx_uuid ref resolves on pull.
            new Spec("order_inbox", DatabaseHelper.TABLE_ORDER_INBOX, new String[]{
                    DatabaseHelper.COL_INBOX_SENDER_NAME, DatabaseHelper.COL_INBOX_SENDER_PHONE,
                    DatabaseHelper.COL_INBOX_RAW, DatabaseHelper.COL_INBOX_PARSED_JSON,
                    DatabaseHelper.COL_INBOX_PARSER, DatabaseHelper.COL_INBOX_STATUS,
                    DatabaseHelper.COL_INBOX_REPLIED, DatabaseHelper.COL_INBOX_SCHED_INTERVAL,
                    DatabaseHelper.COL_INBOX_RECEIVED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_INBOX_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
                    new Ref(DatabaseHelper.COL_INBOX_TRX_ID, "trx_uuid", DatabaseHelper.TABLE_TRANSACTIONS),
            }),

            // Kampanye pelanggan — authored on the web (pull-only): the phone reads title/body to
            // append to the WA struk caption and filters by target_devices. No refs.
            new Spec("campaigns", DatabaseHelper.TABLE_CAMPAIGNS, new String[]{
                    DatabaseHelper.COL_CAMP_TYPE, DatabaseHelper.COL_CAMP_TITLE,
                    DatabaseHelper.COL_CAMP_BODY, DatabaseHelper.COL_CAMP_ACTIVE,
                    DatabaseHelper.COL_CAMP_TARGETS, DatabaseHelper.COL_CAMP_STRUK_STOP,
                    DatabaseHelper.COL_CAMP_STRUK_INLINE, DatabaseHelper.COL_CAMP_STRUK_TEXT,
                    DatabaseHelper.COL_CAMP_MIN_REPEAT, DatabaseHelper.COL_CAMP_SORT_ORDER,
                    DatabaseHelper.COL_CAMP_STARTS_AT, DatabaseHelper.COL_CAMP_ENDS_AT,
                    DatabaseHelper.COL_CAMP_SCHEDULE_DAYS, DatabaseHelper.COL_CAMP_PRIORITY,
                    DatabaseHelper.COL_CREATED_AT,
            }, NO_REFS),

            // Deliveries (two-way) — created on this device when a campaign link is shared with the
            // struk (anti-rebroadcast key = campaign+customer). After campaigns so its campaign_uuid
            // ref resolves on pull; after customers for customer_uuid.
            new Spec("campaign_deliveries", DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES, new String[]{
                    DatabaseHelper.COL_CD_TOKEN, DatabaseHelper.COL_CD_SLUG, DatabaseHelper.COL_CD_SENT_AT,
                    DatabaseHelper.COL_CD_CLICKED_AT, DatabaseHelper.COL_CD_RESPONDED_AT,
                    DatabaseHelper.COL_CD_REACTION, DatabaseHelper.COL_CD_REACTED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_CD_CAMPAIGN_ID, "campaign_uuid", DatabaseHelper.TABLE_CAMPAIGNS),
                    new Ref(DatabaseHelper.COL_CD_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
            }),

            // Gift pelanggan (branch-wide) — assignment ditulis di web (pull), redemption ditulis di
            // HP saat JUAL meng-klaim-nya (push). product_uuid & redeemed_transaction_uuid dibawa
            // sebagai kolom data mentah (bukan Ref): transaksi device-isolated jadi struk-nya tak
            // selalu ada di tiap HP; yang penting redeemed_at menandai gift tak lagi pending di mana
            // pun. customer_uuid di-resolve via Ref (customers branch-wide → selalu ada). Setelah
            // customers agar ref-nya resolve saat pull.
            new Spec("customer_gifts", DatabaseHelper.TABLE_CUSTOMER_GIFTS, new String[]{
                    DatabaseHelper.COL_GIFT_ITEM_TYPE, DatabaseHelper.COL_GIFT_PRODUCT_UUID,
                    DatabaseHelper.COL_GIFT_ITEM_NAME, DatabaseHelper.COL_GIFT_QTY,
                    DatabaseHelper.COL_GIFT_REASON, DatabaseHelper.COL_GIFT_REDEEMED_AT,
                    DatabaseHelper.COL_GIFT_REDEEMED_TRX_UUID, DatabaseHelper.COL_GIFT_REDEEMED_BY,
                    DatabaseHelper.COL_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_GIFT_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
            }),

            // REFUND pelanggan (branch-wide, DUA ARAH): baris 'credit' ditulis web saat operator
            // memberi refund; baris 'usage' ditulis SIAPA PUN yang memotong saldo — web maupun HP
            // saat membuat transaksi. transaction_uuid dibawa mentah (transaksi device-isolated,
            // struk-nya tak selalu ada di tiap HP); customer_uuid lewat Ref seperti gift. Harus
            // SESUDAH customers agar ref-nya resolve saat pull.
            new Spec("customer_refunds", DatabaseHelper.TABLE_CUSTOMER_REFUNDS, new String[]{
                    DatabaseHelper.COL_REFUND_KIND, DatabaseHelper.COL_REFUND_AMOUNT,
                    DatabaseHelper.COL_REFUND_REASON, DatabaseHelper.COL_REFUND_TRX_UUID,
                    DatabaseHelper.COL_REFUND_BY, DatabaseHelper.COL_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_REFUND_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
            }),

            // HUTANG pelanggan (branch-wide, DUA ARAH): baris 'debt' lahir dari penjualan berbayar
            // "HUTANG" atau dicatat manual; baris 'payment' ditulis SIAPA PUN yang menerima
            // pelunasan — kuriirlah yang paling sering menerimanya di lapangan. Sama seperti refund:
            // transaction_uuid mentah, customer_uuid lewat Ref, dan HARUS SESUDAH customers agar
            // ref-nya resolve saat pull.
            new Spec("customer_debts", DatabaseHelper.TABLE_CUSTOMER_DEBTS, new String[]{
                    DatabaseHelper.COL_DEBT_KIND, DatabaseHelper.COL_DEBT_AMOUNT,
                    DatabaseHelper.COL_DEBT_REASON, DatabaseHelper.COL_DEBT_TRX_UUID,
                    DatabaseHelper.COL_DEBT_BY, DatabaseHelper.COL_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_DEBT_CUSTOMER_ID, "customer_uuid", DatabaseHelper.TABLE_CUSTOMERS),
            }),

            // Whitelist "boleh login" per perangkat (pull-only dari web, branch-wide). Layar login
            // memfilter daftar staf: hanya yang ada di sini untuk perangkat ini (kosong = semua).
            new Spec("device_staff_logins", DatabaseHelper.TABLE_DEVICE_STAFF_LOGINS, new String[]{
                    DatabaseHelper.COL_DSL_DEVICE_UUID, DatabaseHelper.COL_DSL_STAFF_UUID,
            }, NO_REFS),

            // "Laporan Kendala Pengiriman" se-rit dari kurir (alasan + foto + pelanggan terdampak).
            // COL_PHOTO_PATH SENGAJA TIDAK ikut dataCols: path itu lokal-perangkat & tak berarti di
            // server — yang disinkron hanya photo_url hasil unggahan MediaUploader (persis pola
            // expenses). staff_id dikirim sebagai staff_uuid lewat Ref supaya bermakna lintas HP.
            new Spec("delivery_obstacles", DatabaseHelper.TABLE_DELIVERY_OBSTACLES, new String[]{
                    DatabaseHelper.COL_DO_REASON, DatabaseHelper.COL_PHOTO_URL,
                    DatabaseHelper.COL_DO_DEVICE_UUID, DatabaseHelper.COL_DO_CUSTOMER_UUIDS,
                    DatabaseHelper.COL_DO_AFFECTED_COUNT, DatabaseHelper.COL_DO_NOTIFIED_AT,
                    DatabaseHelper.COL_DO_NOTIFIED_COUNT, DatabaseHelper.COL_DO_NOTIFY_RESULTS,
                    DatabaseHelper.COL_DO_LAT, DatabaseHelper.COL_DO_LNG,
                    DatabaseHelper.COL_DO_CREATED_BY, DatabaseHelper.COL_DO_REPORTED_AT,
                    DatabaseHelper.COL_CREATED_AT,
            }, new Ref[]{
                    new Ref(DatabaseHelper.COL_DO_STAFF_ID, "staff_uuid", DatabaseHelper.TABLE_USERS),
            }),
    };

    public static final class Result {
        public boolean ok;
        public int pushed;
        public int pulled;
        public String error;
    }

    private final Context appCtx;
    private final DatabaseHelper dbHelper;
    private final SyncSettings cfg;
    private final SyncApi api;
    private final SettingsDao settingsDao;

    /** Key/value settings sync uses this server entity name (special path). */
    private static final String SETTINGS_ENTITY = "app_settings";

    /** Broadcast (app-internal) sent after a sync pulled changes, so open screens
     *  (e.g. the clock-in staff list) can refresh in near real-time. */
    public static final String ACTION_SYNCED = "com.crowja.damiupos.action.SYNCED";

    // Per-cycle cache: refTable -> (localId -> sync_uuid), to speed push ref lookups.
    private final Map<String, Map<Long, String>> uuidCache = new HashMap<>();

    // Follow-up MANUAL baru yang masuk lewat pull (ditambah admin di dashboard) — {nama, catatan}.
    // Diisi di applyRows, dibaca di sync() untuk memunculkan notifikasi + popup + suara.
    private final java.util.List<String[]> pulledManualFollowups = new java.util.ArrayList<>();

    // Konversi promo yang baru terdeteksi lewat pull: pelanggan promosi MILIK PERANGKAT INI
    // (diakuisisi gratis di sini) yang agg pembelian-berbayar-pertamanya BARU terisi dari server —
    // {nama, tanggal pembelian}. Dibaca di sync() → notifikasi "order pembelian pertama".
    private final java.util.List<String[]> pulledPromoConversions = new java.util.ArrayList<>();

    // Pesanan BARU (order_inbox PENDING) yang baru masuk lewat pull UNTUK PERANGKAT INI dan
    // pelanggannya belum lengkap (foto/koordinat) → bisa menyebabkan kredit galon dicabut server.
    // Isi = {customerLocalId (Long), nama (String)}. Diisi HANYA di cabang INSERT applyRows
    // (localId == -1) → tepat sekali per baris pesanan yang benar-benar baru; dibaca di sync()
    // untuk notifikasi + antre popup "Lengkapi Data Pelanggan" yang ditampilkan MainActivity.
    private final java.util.List<Object[]> pulledIncompleteQueueCustomers = new java.util.ArrayList<>();

    // Order yang SEBELUM pull ini ada di antrian PERANGKAT INI (delivery_device_uuid null-atau-milik-
    // sendiri, delivery_status PENDING), tapi pull baru saja mengubahnya. Isi = nama pelanggan. Dua
    // kejadian: (a) DIPINDAHKAN ke perangkat lain oleh web (delivery_device_uuid berubah ke uuid lain);
    // (b) DISELESAIKAN oleh pihak lain (device lain / web) — delivery_status berubah jadi DONE padahal
    // perangkat ini tak pernah memanggil markDelivered() untuk baris itu (kalau device ini yang
    // menyelesaikan, status lokal SUDAH DONE sebelum pull ini datang, jadi tak pernah masuk sini — lihat
    // catatan di applyRows). Diisi HANYA di cabang UPDATE (localId != -1); dibaca &amp; dikosongkan di
    // sync() untuk notifikasi ⚠.
    private final java.util.List<String> pulledRoutedAway = new java.util.ArrayList<>();
    private final java.util.List<String[]> pulledCompletedElsewhere = new java.util.ArrayList<>();   // {nama, completedByName}

    // PELANGGAN PERKENALAN baru (pelanggan promosi marketing — gratis/berbayar — yang belum dikirim
    // WA Perkenalan) yang BARU masuk lewat pull, sudah tersaring wilayah tugas perangkat ini. Hanya
    // terisi bila perangkat dicentang "Petugas WA Perkenalan" di web. Isi = nama; dibaca di sync()
    // untuk notifikasi "sapa pelanggan baru". Diisi HANYA di cabang INSERT (dedup alami).
    private final java.util.List<String> pulledPromoIntroArrivals = new java.util.ArrayList<>();

    // Set by push() when the server reports customers it doesn't have (referenced by pushed
    // transactions): they + their history were re-queued, so sync() pushes a second pass.
    private boolean backfillPending = false;

    // SyncEngine has no singleton — LocationService, SyncWorker, OnlineTasks, SyncSettingsActivity
    // and WizardActivity each construct their OWN "new SyncEngine(ctx)". The old "synchronized"
    // modifier on sync()/pull() etc. locked on THIS instance only, so it never actually excluded
    // two independently-triggered SyncEngine objects from running pull()'s non-transactional
    // check-then-insert (applyRows) at the same time — e.g. LocationService's own ~60s poll loop
    // (which bypasses WorkManager entirely) racing the syncNow() fired right after this device
    // mints a campaign delivery token. That race is what let TWO local `campaigns` rows appear for
    // one server campaign, which then made appendActiveCampaigns() mint two different delivery
    // tokens for what should have been one idempotent link (duplicate kampanye on the struk).
    // A STATIC lock closes it process-wide regardless of instance count.
    private static final Object SYNC_LOCK = new Object();

    public SyncEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.dbHelper = DatabaseHelper.getInstance(appCtx);
        this.settingsDao = new SettingsDao(dbHelper);
        this.cfg = new SyncSettings(settingsDao);
        this.api = new SyncApi(cfg);
    }

    public SyncSettings settings() { return cfg; }

    public Result enroll(String baseUrl, String enrollKey, String deviceName) {
        Result res = new Result();
        try {
            String deviceUuid = cfg.getDeviceUuid();
            if (deviceUuid.isEmpty()) deviceUuid = UUID.randomUUID().toString();
            // Snapshot current phone settings so the server can archive them before the
            // dashboard config takes over (recoverable from the dashboard).
            JSONObject settingsSnapshot = new JSONObject();
            for (java.util.Map.Entry<String, String> e : settingsDao.getAllShared().entrySet()) {
                if (e.getValue() != null) settingsSnapshot.put(e.getKey(), e.getValue());
            }
            JSONObject r = api.enroll(baseUrl, enrollKey, deviceUuid, deviceName,
                    BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, settingsSnapshot);
            cfg.setBaseUrl(baseUrl);
            cfg.setToken(r.getString("token"));
            cfg.setDeviceUuid(r.optString("device_uuid", deviceUuid));
            JSONObject b = r.getJSONObject("branch");
            cfg.setBranch(b.optString("code"), b.optString("uuid"), b.optString("name"));
            // MQTT broker hints in the enroll response are ignored — the app is
            // REST-only (polling). No broker credentials are stored on the device.
            cfg.setLocationIntervalSeconds(r.optInt("location_interval_seconds", 600));
            cfg.setEnabled(true);
            res.ok = true;
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    public Result sync() {
        synchronized (SYNC_LOCK) { return syncLocked(true); }
    }

    /**
     * PULL-ONLY sync — dipakai SEKALI saat provisioning HP baru ({@link
     * com.crowja.damiupos.WizardActivity#startProvisioning}). Fitur "import data perangkat ke
     * server" (push otomatis begitu enroll sukses) sengaja DIMATIKAN di titik ini: HP yang baru
     * di-provisioning bisa saja masih menyimpan data lokal sisa (bekas testing/demo/HP lama yang
     * di-reset), dan push otomatis akan langsung mengunggahnya ke cabang yang baru terhubung tanpa
     * sepengetahuan admin. Sinkron rutin sesudahnya (SyncScheduler) tetap push+pull seperti biasa —
     * gerbang ini HANYA untuk momen provisioning itu sendiri.
     */
    public Result pullOnly() {
        synchronized (SYNC_LOCK) { return syncLocked(false); }
    }

    private Result syncLocked(boolean push) {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            uuidCache.clear();
            if (push) {
                // Upload pending images first so freshly-stamped photo_url values ride this
                // cycle's push. Failures here are swallowed inside the uploader (retry next run).
                try { MediaUploader.uploadPending(dbHelper.getWritableDatabase(), api); }
                catch (Throwable ignored) {}
                res.pushed = push();
                // A push can reveal customers the dashboard is missing (e.g. after re-provisioning):
                // they + their full transaction history were just re-queued — push once more to seed them.
                if (backfillPending) res.pushed += push();
                // Safety-net: ask the server which of our RECENT transactions/expenses it never received
                // and re-push them. Catches any sale that slipped through (dropped push, an old build that
                // wrongly marked a skipped row synced) so a struk'd sale can't stay missing on the web.
                try { if (reconcile()) res.pushed += push(); }
                catch (Throwable ignored) {}
            }
            pulledManualFollowups.clear();
            pulledPromoConversions.clear();
            pulledIncompleteQueueCustomers.clear();
            pulledRoutedAway.clear();
            pulledCompletedElsewhere.clear();
            pulledPromoIntroArrivals.clear();
            try {
                res.pulled = pull();
            } finally {
                // Kirim notifikasi konversi yang SUDAH terkumpul walau pull gagal di tengah
                // (halaman berikut error jaringan): srv_first_paid + kursor per-halaman sudah
                // terlanjur tersimpan, jadi rising edge-nya sudah terkonsumsi — tanpa finally
                // ini notifikasinya hilang permanen.
                deliverPromoConversions();
            }
            // Cek berkala tiap sync: redam pengingat "Pesanan Terjadwal (tiap N hari)" yang sudah tak
            // berlaku — pelanggannya ternyata sudah order dalam N hari terakhir menurut data lokal
            // (lebih mutakhir dari server saat generate 04:30). Tidak fatal bila gagal.
            try { new com.crowja.damiupos.db.OrderInboxDao(dbHelper).pruneStaleScheduledReminders(); }
            catch (Throwable ignored) {}
            cfg.setLastSyncAt(DatabaseHelper.nowIso());
            res.ok = true;
            // Tell open screens to refresh (e.g. clock-in staff list + badge Follow Up) when the
            // pull brought new/changed rows from the dashboard.
            if (res.pulled > 0) {
                try {
                    appCtx.sendBroadcast(new android.content.Intent(ACTION_SYNCED)
                            .setPackage(appCtx.getPackageName()));
                } catch (Throwable ignored) {}
            }
            // Karyawan di-"Pulangkan" lewat dashboard web → event OUT-nya baru saja ditarik.
            // Tutup sesi login staf tsb di HP dan kembalikan ke halaman login.
            try { enforceRemoteClockOut(); } catch (Throwable ignored) {}
            // Kebijakan notifikasi: karyawan marketing HANYA menerima "Pesan & Pembaruan" (pesan
            // admin), bukan notifikasi operasional/pesanan dari dashboard (follow-up, jenis ganda).
            boolean marketing = com.crowja.damiupos.db.UserDao.isCurrentUserMarketing(appCtx);
            // Follow-up MANUAL baru dari dashboard → notifikasi + popup + suara.
            if (!marketing) {
                for (int i = 0; i < pulledManualFollowups.size(); i++) {
                    String[] f = pulledManualFollowups.get(i);
                    String body = f[0] + " ditambahkan ke Follow Up dari dashboard."
                            + (f[1] != null && !f[1].isEmpty() ? " Catatan: " + f[1] : "");
                    try {
                        OnlineNotifier.deliverAdminMessage(appCtx, "Follow Up Baru", body, 7910 + (i % 10));
                    } catch (Throwable ignored) {}
                }
            }
            // Pesanan baru untuk perangkat ini dgn pelanggan belum lengkap (foto/koordinat) →
            // notifikasi sistem + antre popup "Lengkapi Data Pelanggan" (mencegah kredit galon
            // penjualan dicabut server). BERLAKU SEMUA PERAN termasuk marketing — justru merekalah
            // yang paling sering mendaftarkan pelanggan tanpa foto/koordinat. Popup aktual (dengan
            // tombol "Lengkapi Sekarang") ditampilkan MainActivity saat foreground; notifikasi ini
            // menutup kasus app di background.
            if (!pulledIncompleteQueueCustomers.isEmpty()) {
                java.util.List<Long> ids = new java.util.ArrayList<>();
                for (int i = 0; i < pulledIncompleteQueueCustomers.size(); i++) {
                    Object[] e = pulledIncompleteQueueCustomers.get(i);
                    long id = (Long) e[0];
                    String name = (String) e[1];
                    ids.add(id);
                    try {
                        OnlineNotifier.postNotif(appCtx, "Lengkapi Data Pelanggan",
                                name + " (pesanan baru) belum ada foto/koordinat — lengkapi agar "
                                        + "kredit galon penjualan tidak dibatalkan.", 7920 + (i % 10));
                    } catch (Throwable ignored) {}
                }
                try { settingsDao.addPendingIncompleteWarn(ids); } catch (Throwable ignored) {}
            }
            // Order yang tadinya ada di antrian PERANGKAT INI dipindah ke perangkat lain oleh
            // web/operator lain → notifikasi ⚠ supaya kurir tak bingung kenapa ordernya menghilang.
            for (int i = 0; i < pulledRoutedAway.size(); i++) {
                String name = pulledRoutedAway.get(i);
                try {
                    OnlineNotifier.postNotif(appCtx, "⚠ Order Dipindahkan",
                            "Order \"" + name + "\" dipindahkan ke perangkat lain.", 7930 + (i % 10));
                } catch (Throwable ignored) {}
            }
            // Order yang tadinya ada di antrian PERANGKAT INI diselesaikan oleh perangkat/pihak lain
            // (web dashboard atau kurir lain) → notifikasi ⚠ supaya tak diantar/diproses dobel.
            for (int i = 0; i < pulledCompletedElsewhere.size(); i++) {
                String[] e = pulledCompletedElsewhere.get(i);
                String name = e[0];
                String by = e[1];
                try {
                    OnlineNotifier.postNotif(appCtx, "⚠ Order Diselesaikan Pihak Lain",
                            "Order \"" + name + "\" sudah diselesaikan"
                                    + (by != null && !by.trim().isEmpty() ? " oleh " + by.trim() : " oleh perangkat/pihak lain")
                                    + " — tak perlu diantar lagi.", 7940 + (i % 10));
                } catch (Throwable ignored) {}
            }
            // PELANGGAN PERKENALAN baru untuk PERANGKAT PETUGAS WA Perkenalan (sudah tersaring
            // wilayah di applyRows): dirangkum satu notifikasi supaya sinkron awal/borongan tak
            // membanjiri — badge di dashboard yang memuat daftar lengkapnya.
            if (!pulledPromoIntroArrivals.isEmpty()) {
                try {
                    String body = pulledPromoIntroArrivals.size() == 1
                            ? "Pelanggan baru dari promosi: \"" + pulledPromoIntroArrivals.get(0)
                                    + "\" masuk wilayah Anda — kirim WA Perkenalan."
                            : pulledPromoIntroArrivals.size() + " pelanggan baru dari promosi masuk wilayah Anda"
                                    + " — buka Pelanggan Promosi untuk kirim WA Perkenalan.";
                    OnlineNotifier.postNotif(appCtx, "👋 Pelanggan Perkenalan Baru", body, 7960);
                } catch (Throwable ignored) {}
            }
            // Kasus B (upgrade): jenis galon ganda — katalog lokal lama ber-uuid beda dari
            // katalog web. Peringatkan admin sekali (rising-edge) supaya dirapikan di dashboard.
            try {
                java.util.List<String> dupJenis =
                        new com.crowja.damiupos.db.ProductDao(dbHelper).getDuplicateJenisNames();
                if (!dupJenis.isEmpty()) {
                    if (!cfg.wasDupProductsWarned()) {
                        if (!marketing) {
                            OnlineNotifier.deliverAdminMessage(appCtx, "Jenis Galon Ganda",
                                    "Terdeteksi jenis galon ganda: "
                                            + android.text.TextUtils.join(", ", dupJenis)
                                            + ". Rapikan (hapus duplikat) dari dashboard web.", 7905);
                        }
                        cfg.setDupProductsWarned(true);
                    }
                } else if (cfg.wasDupProductsWarned()) {
                    cfg.setDupProductsWarned(false);   // sudah bersih → arm ulang
                }
            } catch (Throwable ignored) {}
        } catch (SyncApi.SyncException se) {
            res.error = se.getMessage();
            if (se.code == 401) handleRevoked();   // token deleted by dashboard "Cabut Akses"
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /**
     * Auto-logout saat karyawan di-"Pulangkan" lewat dashboard web: event OUT hasil Pulangkan
     * tersinkron ke tabel attendance HP, jadi kalau event TERAKHIR staf yang sedang login adalah
     * OUT (bukan dia yang menutup shift dari HP — alur Pulang lokal menutup sesi seketika),
     * sesi ditutup di sini dan HP dikembalikan ke halaman login. Kalau staf sempat clock-in
     * lagi SETELAH OUT web, event terakhirnya IN → tidak di-logout (dia memang bekerja lagi).
     * Juga membersihkan user "istirahat" yang dipulangkan (tombol Lanjut Kerja ikut hilang).
     */
    private void enforceRemoteClockOut() {
        if (!settingsDao.isMultiUserEnabled()) return;
        com.crowja.damiupos.db.AttendanceDao attDao = new com.crowja.damiupos.db.AttendanceDao(dbHelper);
        com.crowja.damiupos.db.UserDao userDao = new com.crowja.damiupos.db.UserDao(dbHelper);

        // User yang sedang istirahat lalu dipulangkan dari web → hapus resume "Lanjut Kerja".
        long breakUid = settingsDao.getBreakUserId();
        if (breakUid > 0 && attDao.isLastEventOut(breakUid)) {
            settingsDao.clearBreakUser();
        }

        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return;
        com.crowja.damiupos.model.User u = userDao.getById(uid);
        if (u == null || !u.tracksAttendance()) return;   // admin/viewer tanpa absensi
        if (!attDao.isLastEventOut(uid)) return;

        String name = settingsDao.getCurrentUserName();
        // Tutup sesi — urutan sama dengan alur Pulang lokal (MainActivity.finishClockOut).
        try { com.crowja.damiupos.WorkHoursReminder.cancel(appCtx, uid); } catch (Throwable ignored) {}
        settingsDao.setShiftActive(false);
        try { com.crowja.damiupos.LocationService.pollOnly(appCtx); } catch (Throwable ignored) {}
        settingsDao.clearCurrentUser();
        settingsDao.clearBreakUser();

        try {
            OnlineNotifier.deliverAdminMessage(appCtx, "Shift Ditutup",
                    (name != null && !name.isEmpty() ? name + " " : "")
                            + "telah dipulangkan dari dashboard — sesi di perangkat ini ditutup.", 7920);
        } catch (Throwable ignored) {}

        // Kembalikan UI ke halaman login. Berhasil saat app di depan; kalau app di background
        // (launch dibatasi OS), sesi sudah bersih — gate MainActivity mengarahkan ke login
        // begitu app dibuka lagi.
        try {
            android.content.Intent i = new android.content.Intent(appCtx, com.crowja.damiupos.LoginActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            appCtx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** Entities the dashboard "Pull Data" imports (full upload of every row). products & staff
     *  are dashboard-managed (pull-only) but uploaded here so the dashboard can seed/align them. */
    private static final String[] EXPORT_ENTITIES = {"customers", "products", "staff", "transactions", "expenses"};

    /**
     * Full export for a dashboard "Pull Data" request ({@code pull_data} command): uploads
     * EVERY row of customers, transactions and expenses (not just dirty ones) to
     * {@code /api/sync/import}. New rows are imported server-side; rows that already exist but
     * differ become reviewable conflicts on the dashboard. Run off the main thread.
     */
    public Result fullExport() {
        synchronized (SYNC_LOCK) { return fullExportLocked(); }
    }

    private Result fullExportLocked() {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            return dumpAndImport(java.util.Arrays.asList(EXPORT_ENTITIES));
        } catch (SyncApi.SyncException se) {
            res.error = se.getMessage();
            if (se.code == 401) handleRevoked();
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /**
     * "Putuskan Provisioning" dengan pengarsipan — pastikan SEMUA data perangkat sampai di web
     * SEBELUM akses diputus, lalu minta server mengarsipkannya:
     * <ol>
     *   <li>unggah SEMUA foto lokal yang belum punya URL (foto rumah pelanggan + nota
     *       pengeluaran + selfie) — kuota per-siklus diulang sampai habis;</li>
     *   <li>sync penuh (push baris kotor — termasuk photo_url yang baru distempel — + pull);</li>
     *   <li>full export SEMUA baris (pelanggan lengkap foto+koordinat, transaksi, pengeluaran,
     *       produk, staf) via /api/sync/import — baris yang tak pernah dirty pun ikut;</li>
     *   <li>POST /api/retire → server mengarsipkan data perangkat (kecuali absensi, tetap jadi
     *       rekam HR) dan mencabut akses.</li>
     * </ol>
     * Enrolment lokal TIDAK dihapus di sini — caller yang menghapus SETELAH sukses, supaya
     * kegagalan jaringan tidak memutus HP dengan data yang belum terselamatkan.
     */
    public Result retireAndArchive() {
        synchronized (SYNC_LOCK) { return retireAndArchiveLocked(); }
    }

    private Result retireAndArchiveLocked() {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            // (1) Semua foto: uploadPending dibatasi kuota per panggilan → ulangi sampai tidak
            // ada lagi yang terunggah (cap iterasi menjaga dari baris beracun yang tak pernah 0).
            for (int i = 0; i < 200; i++) {
                if (MediaUploader.uploadPending(dbHelper.getWritableDatabase(), api) == 0) break;
            }
            // (2) Push + pull normal — photo_url yang baru distempel ikut terkirim di sini.
            Result s = sync();
            if (!s.ok) { res.error = s.error != null ? s.error : "Sinkronisasi gagal"; return res; }
            // (3) Full dump semua baris → /api/sync/import (server menstempel origin perangkat ini,
            // jadi sapuan arsip di langkah 4 mencakup baris yang baru dibuat oleh import ini juga).
            Result d = dumpAndImport(java.util.Arrays.asList(EXPORT_ENTITIES));
            if (!d.ok) { res.error = d.error != null ? d.error : "Export penuh gagal"; return res; }
            // (4) Arsipkan + cabut akses di server. Setelah ini token mati — respons ini yang terakhir.
            org.json.JSONObject r = api.retire();
            res.ok = r.optBoolean("ok", false);
            res.pushed = r.optInt("archived", 0);
            if (!res.ok) res.error = "Server menolak permintaan arsip";
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /**
     * "Pull Settings" ({@code pull_settings} command): upload this phone's current shareable
     * settings to the dashboard, where they're archived for review & restore (mirrors the
     * enroll-time settings snapshot). Secrets are never included — only SHAREABLE_KEYS.
     */
    public Result exportSettings() {
        synchronized (SYNC_LOCK) { return exportSettingsLocked(); }
    }

    private Result exportSettingsLocked() {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            JSONObject settings = new JSONObject();
            for (java.util.Map.Entry<String, String> e : settingsDao.getAllShared().entrySet()) {
                if (e.getValue() != null) settings.put(e.getKey(), e.getValue());
            }
            JSONObject body = new JSONObject();
            body.put("settings", settings);
            api.uploadSettings(body);
            res.ok = true;
            res.pushed = settings.length();
        } catch (SyncApi.SyncException se) {
            res.error = se.getMessage();
            if (se.code == 401) handleRevoked();
        } catch (Exception e) {
            res.error = e.getMessage();
        }
        return res;
    }

    /**
     * Provisioning "import from device": seed the chosen categories' existing rows to the
     * server via the reviewable {@code /api/sync/import} path, then mark the ENTIRE local
     * backlog as already-synced so nothing else auto-uploads — the provisioning checklist is
     * the single source of what gets imported. Activity created after provisioning syncs
     * normally. Run off the main thread.
     */
    public Result provisioningImport(java.util.Collection<String> entityNames) {
        synchronized (SYNC_LOCK) { return provisioningImportLocked(entityNames); }
    }

    private Result provisioningImportLocked(java.util.Collection<String> entityNames) {
        Result res = new Result();
        if (!cfg.isEnrolled()) { res.error = "Belum terhubung ke server"; return res; }
        try {
            res = dumpAndImport(entityNames);
            if (res.ok) markBacklogSynced();
        } catch (SyncApi.SyncException se) {
            res.ok = false; res.error = se.getMessage();
            if (se.code == 401) handleRevoked();
        } catch (Exception e) {
            res.ok = false; res.error = e.getMessage();
        }
        return res;
    }

    /** Rows per import request — small enough that a big seed never exceeds the server's
     *  per-request time limit (shared hosting caps PHP execution at ~30s). */
    private static final int IMPORT_BATCH = 150;

    /** Build the import dump for the given entities (ALL rows) and POST it to
     *  {@code /api/sync/import} in batches, so a large depot (thousands of rows) never
     *  times out the request (which surfaces to the user as "500 gagal impor"). */
    private Result dumpAndImport(java.util.Collection<String> entityNames) throws Exception {
        Result res = new Result();
        uuidCache.clear();
        // Stamp pending images first so photo_url values are populated before the dump.
        try { MediaUploader.uploadPending(dbHelper.getWritableDatabase(), api); }
        catch (Throwable ignored) {}

        SQLiteDatabase db = dbHelper.getWritableDatabase();
        JSONObject batch = new JSONObject();
        int batchCount = 0, total = 0, conflicts = 0;

        for (String name : entityNames) {
            Spec s = specFor(name);
            if (s == null) continue;
            Cursor c = db.query(s.table, null, null, null, null, null, null);   // ALL rows
            try {
                while (c.moveToNext()) {
                    String uuid = str(c, DatabaseHelper.COL_SYNC_UUID);
                    if (uuid == null) continue;
                    JSONObject row = new JSONObject();
                    row.put("uuid", uuid);
                    String edited = str(c, DatabaseHelper.COL_EDITED_AT);
                    if (edited != null) row.put("edited_at", edited);
                    for (String col : s.dataCols) {
                        String v = str(c, col);
                        if (v != null) row.put(col, v);
                    }
                    for (Ref ref : s.refs) {
                        long localId = getLong(c, ref.localCol);
                        if (localId > 0) {
                            String refUuid = uuidForLocalId(db, ref.refTable, localId);
                            if (refUuid != null) row.put(ref.serverField, refUuid);
                        }
                    }
                    JSONArray arr = batch.optJSONArray(name);
                    if (arr == null) { arr = new JSONArray(); batch.put(name, arr); }
                    arr.put(row);
                    batchCount++;
                    total++;
                    if (batchCount >= IMPORT_BATCH) {
                        conflicts += flushImport(batch);
                        batch = new JSONObject();
                        batchCount = 0;
                    }
                }
            } finally { c.close(); }
        }
        if (batchCount > 0) conflicts += flushImport(batch);

        res.pushed = total;
        res.pulled = conflicts;     // surface conflict count for the dashboard review
        res.ok = true;
        return res;
    }

    /** POST one import batch; returns the server's conflict count for it. */
    private int flushImport(JSONObject entities) throws Exception {
        JSONObject body = new JSONObject();
        body.put("entities", entities);
        JSONObject resp = api.importDump(body);
        return resp.optInt("conflicts", 0);
    }

    /** Mark every syncable table's dirty rows as already-synced + drop pending tombstones. */
    private void markBacklogSynced() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SYNCED, 1);
        for (Spec s : SPECS) {
            db.update(s.table, v, DatabaseHelper.COL_SYNCED + "=0", null);
        }
        db.delete(DatabaseHelper.TABLE_SYNC_TOMBSTONES, DatabaseHelper.COL_SYNCED + "=0", null);
    }

    private Spec specFor(String entity) {
        for (Spec s : SPECS) {
            if (s.entity.equals(entity)) return s;
        }
        return null;
    }

    /**
     * The server rejected our token (HTTP 401) — the dashboard revoked this device.
     * Drop enrollment so we stop retrying, stop background work, and tell the user
     * to re-enroll (provisioning).
     */
    private void handleRevoked() {
        cfg.clear();                          // forget token + disable sync
        try { SyncScheduler.cancelAll(appCtx); } catch (Throwable ignored) {}
        try { ServiceRestartReceiver.cancel(appCtx); } catch (Throwable ignored) {}
        try { com.crowja.damiupos.LocationService.stop(appCtx); } catch (Throwable ignored) {}
        OnlineNotifier.postNotif(appCtx, "Akses dicabut",
                "Perangkat dilepas oleh admin. Daftar ulang (provisioning) untuk terhubung lagi.",
                7872);
    }

    // ------------------------------------------------------------------ PUSH

    /** Entities the reconcile safety-net checks (the operational rows a customer "receives" — sales +
     *  expenses), with the local table + how far back to look. Keep small: only what would be a real
     *  loss if it never reached the dashboard. */
    private static final String[][] RECONCILE = {
        {"transactions", DatabaseHelper.TABLE_TRANSACTIONS},
        {"expenses", DatabaseHelper.TABLE_EXPENSES},
    };
    private static final int RECONCILE_DAYS = 30;
    private static final int RECONCILE_CAP = 1500;   // per entity; server also caps at 2000

    /**
     * Reconciliation safety-net: send the server the uuids of our RECENT transactions/expenses; for
     * any it reports missing, mark them dirty (synced=0) so the very next push re-sends them. This is
     * the last line of defence against a struk'd sale never reaching the dashboard — no matter the
     * cause (dropped push mid-request, an older build that wrongly marked a skipped row synced, etc.).
     * Returns true when at least one row was re-queued (caller then pushes again this cycle).
     */
    private boolean reconcile() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        JSONObject entities = new JSONObject();
        // Map entity → its uuid list we're asking about (also lets us re-queue by table afterwards).
        for (String[] ent : RECONCILE) {
            JSONArray uuids = new JSONArray();
            Cursor c = db.rawQuery(
                    "SELECT " + DatabaseHelper.COL_SYNC_UUID + " FROM " + ent[1]
                    + " WHERE " + DatabaseHelper.COL_SYNC_UUID + " IS NOT NULL"
                    + " AND " + DatabaseHelper.COL_CREATED_AT + " >= datetime('now','-" + RECONCILE_DAYS + " days')"
                    + " ORDER BY " + DatabaseHelper.COL_CREATED_AT + " DESC LIMIT " + RECONCILE_CAP,
                    null);
            try {
                while (c.moveToNext()) {
                    String u = c.getString(0);
                    if (u != null && !u.isEmpty()) uuids.put(u);
                }
            } finally { c.close(); }
            if (uuids.length() > 0) entities.put(ent[0], uuids);
        }
        if (entities.length() == 0) return false;

        JSONObject body = new JSONObject();
        body.put("entities", entities);
        JSONObject resp = api.reconcile(body);   // throws on HTTP failure → caller swallows, retry next cycle
        JSONObject missing = resp != null ? resp.optJSONObject("missing") : null;
        if (missing == null || missing.length() == 0) return false;

        int requeued = 0;
        for (String[] ent : RECONCILE) {
            JSONArray arr = missing.optJSONArray(ent[0]);
            if (arr == null) continue;
            ContentValues v = new ContentValues();
            v.put(DatabaseHelper.COL_SYNCED, 0);   // dirty → next push() resends it
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, null);
                if (u == null || u.isEmpty()) continue;
                requeued += db.update(ent[1], v, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{u});
            }
        }
        if (requeued > 0) {
            android.util.Log.w("SyncEngine", "reconcile re-queued " + requeued + " row(s) the server was missing");
        }
        return requeued > 0;
    }

    /** Rows per push request — keeps a big backlog (e.g. thousands of freshly-imported
     *  customers + tombstones) under the server's per-request time limit (shared hosting
     *  caps PHP execution at ~30s; one giant push otherwise 500s). */
    private static final int PUSH_BATCH = 250;

    private int push() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        backfillPending = false;

        JSONObject entities = new JSONObject();
        List<String[]> sentRows = new ArrayList<>();   // {table, uuid, edited_at}
        List<String[]> sentTombs = new ArrayList<>();  // {entity, uuid}
        int batchCount = 0, total = 0;

        for (Spec s : SPECS) {
            Cursor c = db.query(s.table, null, DatabaseHelper.COL_SYNCED + "=0",
                    null, null, null, null);
            try {
                while (c.moveToNext()) {
                    String uuid = str(c, DatabaseHelper.COL_SYNC_UUID);
                    if (uuid == null) continue;
                    String edited = str(c, DatabaseHelper.COL_EDITED_AT);
                    JSONObject row = new JSONObject();
                    row.put("uuid", uuid);
                    row.put("edited_at", edited);
                    for (String col : s.dataCols) {
                        String v = str(c, col);
                        if (v != null) row.put(col, v);
                    }
                    for (Ref ref : s.refs) {
                        long localId = getLong(c, ref.localCol);
                        if (localId > 0) {
                            String refUuid = uuidForLocalId(db, ref.refTable, localId);
                            if (refUuid != null) row.put(ref.serverField, refUuid);
                        }
                    }
                    appendTo(entities, s.entity, row);
                    sentRows.add(new String[]{s.table, uuid, edited});
                    total++;
                    if (++batchCount >= PUSH_BATCH) {
                        flushPush(db, entities, sentRows, sentTombs, null);
                        entities = new JSONObject();
                        sentRows = new ArrayList<>();
                        sentTombs = new ArrayList<>();
                        batchCount = 0;
                    }
                }
            } finally { c.close(); }

            Cursor t = db.query(DatabaseHelper.TABLE_SYNC_TOMBSTONES, null,
                    DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNCED + "=0",
                    new String[]{s.entity}, null, null, null);
            try {
                while (t.moveToNext()) {
                    String uuid = str(t, DatabaseHelper.COL_SYNC_UUID);
                    if (uuid == null) continue;
                    String edited = str(t, DatabaseHelper.COL_EDITED_AT);
                    JSONObject row = new JSONObject();
                    row.put("uuid", uuid);
                    row.put("edited_at", edited);
                    row.put("deleted_at", edited);
                    // Atribusi penghapusan (bila ada) — server menyimpannya utk "Dihapus oleh …".
                    String delName = str(t, DatabaseHelper.COL_DELETED_BY_NAME);
                    String delDev = str(t, DatabaseHelper.COL_DELETED_BY_DEVICE);
                    if (delName != null) row.put("deleted_by_name", delName);
                    if (delDev != null) row.put("deleted_by_device", delDev);
                    appendTo(entities, s.entity, row);
                    sentTombs.add(new String[]{s.entity, uuid});
                    total++;
                    if (++batchCount >= PUSH_BATCH) {
                        flushPush(db, entities, sentRows, sentTombs, null);
                        entities = new JSONObject();
                        sentRows = new ArrayList<>();
                        sentTombs = new ArrayList<>();
                        batchCount = 0;
                    }
                }
            } finally { t.close(); }
        }

        // Branch-shared settings (app_settings) — special key/value path; ride the final batch.
        List<String[]> dirtySettings = settingsDao.getDirtyShared();
        JSONArray settingsArr = new JSONArray();
        for (String[] kv : dirtySettings) {
            JSONObject o = new JSONObject();
            o.put("key", kv[0]);
            o.put("value", kv[1]);
            o.put("edited_at", kv[2]);
            settingsArr.put(o);
        }

        if (batchCount > 0 || settingsArr.length() > 0) {
            flushPush(db, entities, sentRows, sentTombs,
                    settingsArr.length() > 0 ? settingsArr : null);
            if (!dirtySettings.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (String[] kv : dirtySettings) keys.add(kv[0]);
                settingsDao.markSharedSynced(keys);
            }
        }

        return total + settingsArr.length();
    }

    /** Append a row to entities[name], creating the array on first use. */
    private static void appendTo(JSONObject entities, String name, JSONObject row) throws Exception {
        JSONArray arr = entities.optJSONArray(name);
        if (arr == null) { arr = new JSONArray(); entities.put(name, arr); }
        arr.put(row);
    }

    /** POST one push batch, then mark its rows synced / drop its tombstones / re-queue any
     *  customers the server reports missing. Throws on HTTP failure → that batch's rows
     *  stay dirty (retried next cycle); batches already flushed remain durably synced. */
    private void flushPush(SQLiteDatabase db, JSONObject entities, List<String[]> sentRows,
                           List<String[]> sentTombs, JSONArray settings) throws Exception {
        if (entities.length() == 0 && (settings == null || settings.length() == 0)) return;
        JSONObject body = new JSONObject();
        body.put("entities", entities);
        if (settings != null && settings.length() > 0) body.put("settings", settings);
        JSONObject resp = api.push(body);   // throws on failure → these rows stay dirty

        // Per-row ACK gating: mark a row synced ONLY when the server actually acknowledged it with a
        // success status. Previously EVERY row in a 200-response batch was marked synced regardless of
        // its per-row status, so a row the server SKIPPED ('error', e.g. a transient constraint hit)
        // was silently marked synced and never retried → a real sale (whose struk already reached the
        // customer) could vanish from the dashboard. Now a non-acked row stays dirty and retries; the
        // reconcile pass is the final backstop for anything still missing.
        // null = legacy server without a `results` map → treat all sent rows as acked (old behaviour).
        Set<String> acked = ackedUuids(resp);
        for (String[] row : sentRows) {
            if (acked != null && !acked.contains(row[1])) continue;   // not confirmed → keep dirty, retry
            db.execSQL("UPDATE " + row[0] + " SET " + DatabaseHelper.COL_SYNCED + "=1"
                    + " WHERE " + DatabaseHelper.COL_SYNC_UUID + "=? AND "
                    + DatabaseHelper.COL_EDITED_AT + "=?", new Object[]{row[1], row[2]});
        }
        for (String[] tomb : sentTombs) {
            if (acked != null && !acked.contains(tomb[1])) continue;   // delete not confirmed → keep tombstone
            db.delete(DatabaseHelper.TABLE_SYNC_TOMBSTONES,
                    DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNC_UUID + "=?",
                    new String[]{tomb[0], tomb[1]});
        }
        requeueMissingCustomers(db, resp);
    }

    /** The ONLY per-row status that must keep a row dirty (unexpected, likely-transient failure — a
     *  row the server tried to store but couldn't). Every OTHER status is a terminal server decision:
     *  stored ("created"/"updated"), superseded ("kept_server"), tombstoned ("deleted"/"absent"), or
     *  deliberately not stored ("pull_only"/"collection_disabled") — none should retry forever. Old
     *  behaviour marked EVERY row synced regardless, so an "error" row was silently dropped. */
    private static final String STATUS_RETRY = "error";

    /** Uuids the server settled (any status EXCEPT "error") across every entity in the push response's
     *  `results` map, or NULL when there is no `results` map (older server) → caller acks all sent
     *  rows, preserving the previous HTTP-200-means-success behaviour. */
    private static Set<String> ackedUuids(JSONObject resp) {
        JSONObject results = resp != null ? resp.optJSONObject("results") : null;
        if (results == null) return null;   // legacy server → caller acks all
        Set<String> ok = new HashSet<>();
        for (java.util.Iterator<String> it = results.keys(); it.hasNext(); ) {
            JSONArray arr = results.optJSONArray(it.next());
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String uuid = r.optString("uuid", null);
                if (uuid != null && !STATUS_RETRY.equals(r.optString("status", ""))) ok.add(uuid);
            }
        }
        return ok;
    }


    /**
     * The server lists customers (referenced by transactions we just pushed) that the
     * dashboard has no row for. Re-queue each such customer AND its entire transaction
     * history as dirty so the next push seeds the full record on the dashboard.
     */
    private void requeueMissingCustomers(SQLiteDatabase db, JSONObject resp) {
        JSONObject missing = resp != null ? resp.optJSONObject("missing") : null;
        JSONArray customers = missing != null ? missing.optJSONArray("customers") : null;
        if (customers == null || customers.length() == 0) return;

        ContentValues dirty = new ContentValues();
        dirty.put(DatabaseHelper.COL_SYNCED, 0);
        for (int i = 0; i < customers.length(); i++) {
            String uuid = customers.optString(i, null);
            if (uuid == null || uuid.isEmpty()) continue;
            int n = db.update(DatabaseHelper.TABLE_CUSTOMERS, dirty,
                    DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
            if (n == 0) continue;   // customer not on this device → nothing to send
            long localId = localIdForUuid(db, DatabaseHelper.TABLE_CUSTOMERS, uuid);
            if (localId > 0) {
                db.update(DatabaseHelper.TABLE_TRANSACTIONS, dirty,
                        DatabaseHelper.COL_CUSTOMER_ID + "=?", new String[]{String.valueOf(localId)});
            }
            backfillPending = true;
        }
    }

    // ------------------------------------------------------------------ PULL

    /** Batas aman iterasi drain (500 baris/halaman → 60 = 30 000 baris/entitas per sync).
     *  Mencegah loop tak berujung kalau server keliru selalu melaporkan has_more. */
    private static final int MAX_PULL_PAGES = 60;

    private int pull() throws Exception {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // One-time recovery: re-pull staff & products from scratch so web-added rows whose insert
        // previously failed (NOT NULL pin) finally land. Runs once after this fix is installed.
        if (cfg.needsStaffRepull()) {
            cfg.setCursor("staff", "1970-01-01 00:00:00.000000");
            cfg.setCursor("products", "1970-01-01 00:00:00.000000");
            cfg.markStaffRepulled();
        }
        // "Pelanggan branch-wide": tarik ULANG semua pelanggan cabang sekali (kursor lokal sudah
        // lewat pelanggan perangkat lain yang updated_at-nya lebih tua) + isi flag is_mine.
        if (cfg.needsCustomerRepull()) {
            cfg.setCursor("customers", "1970-01-01 00:00:00.000000");
            cfg.markCustomerRepulled();
        }
        // Buku besar Hutang/Refund: tarik ulang PENUH dari epoch lalu PANGKAS baris lokal yang
        // ternyata tak ada di server. uuid yang benar-benar dikirim server dikumpulkan di sini;
        // {@see #pruneStaleRows} memakainya sesudah drain tuntas. Kosong = tak ada rekonsiliasi
        // penuh di siklus ini (perilaku normal).
        Map<String, java.util.Set<String>> fullResync = new HashMap<>();
        if (cfg.needsDebtRepull()) {
            cfg.setCursor("customer_debts", "1970-01-01 00:00:00.000000");
            cfg.setCursor("customer_refunds", "1970-01-01 00:00:00.000000");
            fullResync.put("customer_debts", new java.util.HashSet<>());
            fullResync.put("customer_refunds", new java.util.HashSet<>());
            cfg.markDebtRepulled();
        }

        int applied = 0;
        boolean firstPage = true;
        boolean more;
        int guard = 0;

        // Kuras SEMUA halaman dalam satu sync: server membatasi tiap respons (limit 500) dan
        // melaporkan has_more per entitas. Tanpa loop ini, satu sync cuma menarik 1 halaman/entitas
        // — pelanggan branch-wide (>500) baru lengkap setelah beberapa siklus (atau tak pernah).
        do {
            more = false;
            JSONArray names = new JSONArray();
            JSONObject cursors = new JSONObject();
            for (Spec s : SPECS) {
                names.put(s.entity);
                cursors.put(s.entity, cfg.getCursor(s.entity));
            }
            names.put(SETTINGS_ENTITY);
            cursors.put(SETTINGS_ENTITY, cfg.getCursor(SETTINGS_ENTITY));
            JSONObject body = new JSONObject();
            body.put("entities", names);
            body.put("cursors", cursors);

            JSONObject resp = api.pull(body);
            JSONObject entities = resp.optJSONObject("entities");
            JSONObject newCursors = resp.optJSONObject("cursors");
            JSONObject hasMore = resp.optJSONObject("has_more");

            for (Spec s : SPECS) {
                JSONArray arr = entities != null ? entities.optJSONArray(s.entity) : null;
                if (arr != null) {
                    applied += applyRows(db, s, arr);
                    // Rekonsiliasi penuh: catat uuid yang SUNGGUH dikirim server (termasuk yang
                    // ber-tombstone) — sisanya nanti dipangkas dari salinan lokal.
                    java.util.Set<String> seen = fullResync.get(s.entity);
                    if (seen != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            String u = o != null ? o.optString("uuid", null) : null;
                            if (u != null && !u.isEmpty()) seen.add(u);
                        }
                    }
                }
                if (newCursors != null) {
                    cfg.setCursor(s.entity, newCursors.optString(s.entity, cfg.getCursor(s.entity)));
                }
                if (hasMore != null && hasMore.optBoolean(s.entity, false)) more = true;
            }

            // Branch-shared settings (app_settings) + stok galon hanya perlu diproses SEKALI:
            // app_settings dikirim penuh (semua baris) tiap pull, jadi memprosesnya di halaman
            // pertama sudah cukup; has_more-nya sengaja TIDAK ikut menentukan `more` (desain full-
            // resend bisa selalu true bila >500 setting → loop tak berujung).
            if (firstPage) {
                JSONArray sa = entities != null ? entities.optJSONArray(SETTINGS_ENTITY) : null;
                if (sa != null) {
                    for (int i = 0; i < sa.length(); i++) {
                        JSONObject o = sa.optJSONObject(i);
                        if (o == null) continue;
                        String key = o.optString("key", null);
                        if (key == null || key.isEmpty()) continue;
                        String value = o.isNull("value") ? null : o.optString("value", null);
                        String editedAt = o.optString("edited_at", "");
                        boolean deleted = !o.isNull("deleted_at")
                                && !o.optString("deleted_at", "").isEmpty();
                        settingsDao.applySyncedSetting(key, value, editedAt, deleted);
                        applied++;
                    }
                    // Logo depot mungkin baru/berubah → unduh ke cache di background supaya struk &
                    // Pengaturan langsung punya gambarnya (ensureDownloaded no-op bila URL sama).
                    new Thread(() -> com.crowja.damiupos.util.DepotLogo.ensureDownloaded(appCtx)).start();
                }
                if (newCursors != null) {
                    cfg.setCursor(SETTINGS_ENTITY,
                            newCursors.optString(SETTINGS_ENTITY, cfg.getCursor(SETTINGS_ENTITY)));
                }

                // Branch-authoritative gallon stock from the server → cached and shown verbatim on
                // this device, so its "Stok Galon Tersedia" is ALWAYS identical to the dashboard
                // (per-device transaction isolation can otherwise leave the local keluar/kembali behind).
                JSONObject sg = resp.optJSONObject("stok_galon");
                if (sg != null) {
                    cfg.setStokGalon(sg.optInt("masuk", 0), sg.optInt("keluar", 0),
                            sg.optInt("kembali", 0), sg.optInt("tersedia", 0));
                }
            }
            firstPage = false;
        } while (more && ++guard < MAX_PULL_PAGES);

        // Pangkas HANYA bila drain selesai WAJAR (more==false). Kalau berhenti karena pagar
        // MAX_PULL_PAGES, daftar uuid server belum lengkap — memangkas dari daftar setengah jadi
        // akan membuang baris yang sebenarnya masih ada.
        if (!more) {
            for (Map.Entry<String, java.util.Set<String>> e : fullResync.entrySet()) {
                applied += pruneStaleRows(db, e.getKey(), e.getValue());
            }
        }

        return applied;
    }

    /**
     * Buang baris LOKAL sebuah entitas yang server ternyata TIDAK punya — dijalankan HANYA sesudah
     * tarik-ulang PENUH dari epoch selesai tuntas, jadi {@code serverUuids} memuat SELURUH baris
     * cabang untuk entitas itu (termasuk yang ber-tombstone).
     *
     * <p>Ini menutup satu-satunya lubang yang tak bisa disembuhkan pull biasa: baris yang lenyap
     * dari server TANPA tombstone (terhapus keras langsung di database) tak akan pernah disebut
     * lagi oleh pull mana pun, sehingga perangkat menyimpannya selamanya dan saldo buku besarnya
     * menyimpang dari dashboard. {@see SyncSettings#needsDebtRepull()}
     *
     * <p>Dua pagar keselamatan yang disengaja:
     * <ul>
     *   <li>Hanya baris {@code synced=1} — baris yang dibuat di HP dan BELUM ter-push tak boleh
     *       ikut terbuang hanya karena server memang belum tahu.</li>
     *   <li>HARD delete LOKAL tanpa tombstone. Kalau dugaan ini keliru, pull berikutnya
     *       mengembalikan barisnya dari server (server tetap sumber kebenaran). Tombstone justru
     *       akan MENGHAPUS baris aslinya di server — kerusakan yang tak bisa ditarik balik.</li>
     * </ul>
     */
    private int pruneStaleRows(SQLiteDatabase db, String entity, java.util.Set<String> serverUuids) {
        Spec spec = null;
        for (Spec s : SPECS) {
            if (s.entity.equals(entity)) { spec = s; break; }
        }
        if (spec == null) return 0;

        java.util.List<String> doomed = new java.util.ArrayList<>();
        Cursor c = db.query(spec.table, new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_SYNCED + "=1 AND " + DatabaseHelper.COL_SYNC_UUID + " IS NOT NULL",
                null, null, null, null);
        try {
            while (c.moveToNext()) {
                String u = c.getString(0);
                if (u != null && !u.isEmpty() && !serverUuids.contains(u)) doomed.add(u);
            }
        } finally {
            c.close();
        }
        for (String u : doomed) {
            db.delete(spec.table, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{u});
        }
        if (!doomed.isEmpty()) {
            android.util.Log.w("SyncEngine", "prune " + entity + ": " + doomed.size()
                    + " baris lokal basi dibuang (tak ada di server)");
        }
        return doomed.size();
    }

    /**
     * Pelanggan ini ditangani perangkat INI? Cermin {@link com.crowja.damiupos.db.OrderInboxDao}
     * assignedHere per-item: override perangkat per-pelanggan MENGALAHKAN wilayah otomatis; bila
     * penugasan tak bisa ditentukan (belum provisioning / tanpa koordinat / wilayah belum diatur)
     * dianggap "ya" agar pengingat tidak hilang dari semua perangkat.
     */
    private boolean assignedToThisDevice(com.crowja.damiupos.model.Customer c) {
        if (c == null) return false;
        String myUuid = cfg.getDeviceUuid();
        if (myUuid == null || myUuid.trim().isEmpty()) return true;
        String dev = com.crowja.damiupos.Wilayah.effectiveDevice(
                c.getAssignedDeviceUuid(), cfg.getBranchCenter(), cfg.getWilayahZones(),
                c.getLatitude(), c.getLongitude());
        return dev == null || dev.trim().isEmpty() || dev.equals(myUuid);
    }

    /**
     * Order delivery (baris transaksi) ini ditugaskan ke perangkat INI? delivery_device_uuid
     * (server-otoritatif) menang bila terisi; bila kosong, jatuh ke wilayah/override pelanggan
     * ({@link #assignedToThisDevice}).
     */
    private boolean deliveryAssignedHere(ContentValues v, com.crowja.damiupos.model.Customer c) {
        String devUuid = v.getAsString(DatabaseHelper.COL_DELIVERY_DEVICE_UUID);
        if (devUuid != null && !devUuid.trim().isEmpty()) {
            String myUuid = cfg.getDeviceUuid();
            return myUuid != null && devUuid.equals(myUuid);
        }
        return assignedToThisDevice(c);
    }

    /**
     * Kumpulkan pelanggan antrean baru yang belum lengkap untuk popup "Lengkapi Data Pelanggan".
     * Hanya bila setting cabut-kredit cabang AKTIF (jadi void nyata bisa terjadi — kalau mati,
     * tak ada yang perlu dicegah) DAN pelanggan memenuhi predikat server ({@code shouldWarn}:
     * tak lengkap, bukan Umum, bukan afiliasi reseller). Dedup per-siklus by customerId supaya
     * satu pelanggan yang muncul di dua sumber antrean tak dikumpulkan/di-notif dua kali.
     */
    private void collectIncompleteQueueCustomer(com.crowja.damiupos.model.Customer c) {
        if (c == null) return;
        if (!settingsDao.isRevokeCreditIncompleteEnabled()) return;
        if (!com.crowja.damiupos.IncompleteCustomerDialog.shouldWarn(c)) return;
        for (Object[] e : pulledIncompleteQueueCustomers) {
            if (((Long) e[0]).longValue() == c.getId()) return;   // sudah dikumpulkan siklus ini
        }
        pulledIncompleteQueueCustomers.add(new Object[]{c.getId(), c.getName()});
    }

    private int applyRows(SQLiteDatabase db, Spec s, JSONArray arr) {
        // Batches this page's check-then-insert/update as one atomic unit — previously each row's
        // SELECT-by-sync_uuid + INSERT/UPDATE auto-committed individually with no beginTransaction()
        // anywhere in this file. Doesn't change the happy path (all rows still applied one by one);
        // on an exception partway through, nothing in this entity's page is left half-applied — the
        // whole batch retries next pull (edited_at cursor for this entity is only advanced by the
        // caller AFTER this method returns, so no cursor/data desync either way).
        db.beginTransaction();
        try {
            int applied = applyRowsInner(db, s, arr);
            db.setTransactionSuccessful();
            return applied;
        } finally {
            db.endTransaction();
        }
    }

    private int applyRowsInner(SQLiteDatabase db, Spec s, JSONArray arr) {
        int applied = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String uuid = obj.optString("uuid", null);
            if (uuid == null || uuid.isEmpty()) continue;
            String editedAt = obj.optString("edited_at", "");
            String deletedAt = obj.isNull("deleted_at") ? null : obj.optString("deleted_at", null);

            long localId = -1; int localSynced = 1; String localEdited = "";
            Cursor c = db.query(s.table,
                    new String[]{s.idCol, DatabaseHelper.COL_SYNCED, DatabaseHelper.COL_EDITED_AT},
                    DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
            if (c.moveToFirst()) {
                localId = c.getLong(0);
                localSynced = c.getInt(1);
                localEdited = c.getString(2);
            }
            c.close();

            if (deletedAt != null && !deletedAt.isEmpty()) {
                if (localId != -1) {
                    db.delete(s.table, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
                }
                db.delete(DatabaseHelper.TABLE_SYNC_TOMBSTONES,
                        DatabaseHelper.COL_TS_ENTITY + "=? AND " + DatabaseHelper.COL_SYNC_UUID + "=?",
                        new String[]{s.entity, uuid});
                applied++;
                continue;
            }

            if (localId != -1 && localSynced == 0 && localEdited != null
                    && localEdited.compareTo(editedAt) > 0) {
                // Kolom data di-skip (edit lokal lebih baru), TAPI field pelanggan yang
                // server-authoritative (agregat lintas-perangkat + is_mine + origin_label) tetap
                // disegarkan — tak pernah di-push dari device, jadi tak ada risiko clobber.
                if ("customers".equals(s.entity)) {
                    collectPromoConversion(db, s.table, uuid, localId, obj);
                    ContentValues sv = new ContentValues();
                    sv.put(DatabaseHelper.COL_IS_MINE, obj.optBoolean("is_mine", true) ? 1 : 0);
                    sv.put(DatabaseHelper.COL_SRV_TRX, obj.optInt("agg_trx", 0));
                    sv.put(DatabaseHelper.COL_SRV_ORDERED, obj.optInt("agg_ordered", 0));
                    sv.put(DatabaseHelper.COL_SRV_BORROWED, obj.optInt("agg_borrowed", 0));
                    sv.put(DatabaseHelper.COL_SRV_KEMBALI, obj.optInt("agg_kembali", 0));
                    sv.put(DatabaseHelper.COL_SRV_HELD, obj.optInt("agg_held", 0));
                    sv.put(DatabaseHelper.COL_SRV_FIRST_JUAL,
                            obj.isNull("agg_first_jual") ? null : obj.optString("agg_first_jual", null));
                    sv.put(DatabaseHelper.COL_SRV_FIRST_PAID,
                            obj.isNull("agg_first_paid_jual") ? null : obj.optString("agg_first_paid_jual", null));
                    sv.put(DatabaseHelper.COL_SRV_LAST_JUAL,
                            obj.isNull("agg_last_jual") ? null : obj.optString("agg_last_jual", null));
                    sv.put(DatabaseHelper.COL_SRV_PROMO_GALON, obj.optInt("agg_promo_galon", 0));
                    sv.put(DatabaseHelper.COL_SRV_PROMO_PAID, obj.optInt("agg_promo_paid", 0));
                    sv.put(DatabaseHelper.COL_SRV_PROMO_PULLED, obj.optInt("agg_promo_pulled", 0));
                    sv.put(DatabaseHelper.COL_ORIGIN_LABEL, obj.optString("origin_label", ""));
                    sv.put(DatabaseHelper.COL_SRV_SALDO, obj.optDouble("agg_saldo", 0));
                    sv.put(DatabaseHelper.COL_SRV_PAID_JUAL_COUNT, obj.optInt("agg_paid_jual_count", 0));
                    // Desa/Kecamatan (reverse-geocode server) — pull-only, sama pola dgn origin_label.
                    sv.put(DatabaseHelper.COL_DESA, obj.isNull("desa") ? null : obj.optString("desa", null));
                    sv.put(DatabaseHelper.COL_KECAMATAN, obj.isNull("kecamatan") ? null : obj.optString("kecamatan", null));
                    db.update(s.table, sv, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
                }
                continue;   // don't clobber a newer un-pushed local edit
            }

            ContentValues v = new ContentValues();
            v.put(DatabaseHelper.COL_SYNC_UUID, uuid);
            v.put(DatabaseHelper.COL_EDITED_AT, editedAt);
            v.put(DatabaseHelper.COL_SYNCED, 1);
            for (String col : s.dataCols) {
                putTyped(v, col, obj.opt(col));
            }
            for (Ref ref : s.refs) {
                String refUuid = obj.isNull(ref.serverField) ? null : obj.optString(ref.serverField, null);
                long refLocal = (refUuid != null && !refUuid.isEmpty())
                        ? localIdForUuid(db, ref.refTable, refUuid) : 0;
                v.put(ref.localCol, refLocal);
            }
            // A staff (users) row created on the web carries no PIN — it's never synced. The local
            // pin column is NOT NULL, so without this the INSERT fails silently and the karyawan
            // never appears. Seed an empty PIN on first insert; the staff sets it on first login.
            // Updates leave pin untouched, so an existing staff keeps theirs.
            if (localId == -1 && DatabaseHelper.TABLE_USERS.equals(s.table)
                    && ! v.containsKey(DatabaseHelper.COL_USER_PIN)) {
                v.put(DatabaseHelper.COL_USER_PIN, "");
            }
            // Follow-up MANUAL baru dari dashboard: customer yang BARU ditandai
            // (followup_manual_at terisi sekarang, sebelumnya kosong) → kumpulkan untuk notif.
            if ("customers".equals(s.entity)) {
                collectPromoConversion(db, s.table, uuid, localId, obj);
                // "Hanya Pelanggan Saya": is_mine dari server (origin == perangkat ini). Field khusus
                // pull — TIDAK ada di spec dataCols, jadi tak ikut di-push balik (bukan kolom server).
                v.put(DatabaseHelper.COL_IS_MINE, obj.optBoolean("is_mine", true) ? 1 : 0);
                // Agregat lintas-perangkat (server) + label perangkat asal — juga pull-only.
                v.put(DatabaseHelper.COL_SRV_TRX, obj.optInt("agg_trx", 0));
                v.put(DatabaseHelper.COL_SRV_ORDERED, obj.optInt("agg_ordered", 0));
                v.put(DatabaseHelper.COL_SRV_BORROWED, obj.optInt("agg_borrowed", 0));
                v.put(DatabaseHelper.COL_SRV_KEMBALI, obj.optInt("agg_kembali", 0));
                v.put(DatabaseHelper.COL_SRV_HELD, obj.optInt("agg_held", 0));
                v.put(DatabaseHelper.COL_SRV_FIRST_JUAL,
                        obj.isNull("agg_first_jual") ? null : obj.optString("agg_first_jual", null));
                v.put(DatabaseHelper.COL_SRV_FIRST_PAID,
                        obj.isNull("agg_first_paid_jual") ? null : obj.optString("agg_first_paid_jual", null));
                v.put(DatabaseHelper.COL_SRV_LAST_JUAL,
                        obj.isNull("agg_last_jual") ? null : obj.optString("agg_last_jual", null));
                v.put(DatabaseHelper.COL_SRV_PROMO_GALON, obj.optInt("agg_promo_galon", 0));
                v.put(DatabaseHelper.COL_SRV_PROMO_PAID, obj.optInt("agg_promo_paid", 0));
                v.put(DatabaseHelper.COL_SRV_PROMO_PULLED, obj.optInt("agg_promo_pulled", 0));
                v.put(DatabaseHelper.COL_ORIGIN_LABEL, obj.optString("origin_label", ""));
                v.put(DatabaseHelper.COL_SRV_SALDO, obj.optDouble("agg_saldo", 0));
                v.put(DatabaseHelper.COL_SRV_PAID_JUAL_COUNT, obj.optInt("agg_paid_jual_count", 0));
                // Desa/Kecamatan (reverse-geocode server) — pull-only, sama pola dgn origin_label.
                v.put(DatabaseHelper.COL_DESA, obj.isNull("desa") ? null : obj.optString("desa", null));
                v.put(DatabaseHelper.COL_KECAMATAN, obj.isNull("kecamatan") ? null : obj.optString("kecamatan", null));

                // PELANGGAN PERKENALAN BARU masuk lewat pull (INSERT = benar-benar baru di HP ini,
                // dedup alami — cermin pulledIncompleteQueueCustomers): pelanggan promosi marketing
                // (gratis: agg_promo_galon>0, ATAU berbayar: agg_promo_paid>0) yang belum dikirim
                // WA Perkenalan → notifikasi untuk PERANGKAT PETUGAS (dicentang di web), disaring
                // ke wilayah tugasnya. Perangkat non-petugas tak pernah dinotifikasi.
                if (localId == -1 && cfg.isIntroWaDevice()
                        && (obj.optInt("agg_promo_galon", 0) > 0 || obj.optInt("agg_promo_paid", 0) > 0)
                        && obj.isNull(DatabaseHelper.COL_INTRO_WA_SENT_AT)) {
                    double plat = obj.optDouble(DatabaseHelper.COL_LATITUDE, 0);
                    double plng = obj.optDouble(DatabaseHelper.COL_LONGITUDE, 0);
                    if (com.crowja.damiupos.IntroWaDuty.inMyZones(cfg, plat, plng)) {
                        pulledPromoIntroArrivals.add(obj.optString(DatabaseHelper.COL_NAME, "Pelanggan"));
                    }
                }

                String inManual = obj.isNull(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT) ? null
                        : obj.optString(DatabaseHelper.COL_FOLLOWUP_MANUAL_AT, null);
                if (inManual != null && !inManual.isEmpty()) {
                    String localManual = null;
                    if (localId != -1) {
                        Cursor mc = db.query(s.table, new String[]{DatabaseHelper.COL_FOLLOWUP_MANUAL_AT},
                                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
                        if (mc.moveToFirst()) localManual = mc.getString(0);
                        mc.close();
                    }
                    if (localManual == null || localManual.isEmpty()) {
                        pulledManualFollowups.add(new String[]{
                                obj.optString(DatabaseHelper.COL_NAME, "Pelanggan"),
                                obj.isNull(DatabaseHelper.COL_FOLLOWUP_NOTE) ? ""
                                        : obj.optString(DatabaseHelper.COL_FOLLOWUP_NOTE, "")});
                    }
                }
            }

            // Antrean BARU untuk perangkat ini dgn pelanggan belum lengkap (foto/koordinat) →
            // kumpulkan supaya operator diperingatkan melengkapi (mencegah kredit galon penjualan
            // dicabut server saat delivery selesai). Hanya cabang INSERT (localId == -1) = baris
            // yang benar-benar baru → tepat sekali per antrean (dedup alami). Dua sumber "antrean":
            //  (a) pesanan masuk  — order_inbox PENDING;
            //  (b) order delivery ditugaskan ke perangkat ini — transactions delivery PENDING
            //      (mis. order online dari airfrez.com yang dirutekan ke perangkat ini).
            // customers spec di-apply lebih dulu → baris pelanggan (foto/koordinat) sudah segar.
            if (localId == -1) {
                try {
                    if ("order_inbox".equals(s.entity)) {
                        String inStatus = obj.isNull(DatabaseHelper.COL_INBOX_STATUS) ? null
                                : obj.optString(DatabaseHelper.COL_INBOX_STATUS, null);
                        Long custLocal = v.getAsLong(DatabaseHelper.COL_INBOX_CUSTOMER_ID);
                        if (com.crowja.damiupos.model.OrderInbox.STATUS_PENDING.equals(inStatus)
                                && custLocal != null && custLocal > 0) {
                            com.crowja.damiupos.model.Customer oc =
                                    new com.crowja.damiupos.db.CustomerDao(dbHelper).getById(custLocal);
                            if (assignedToThisDevice(oc)) collectIncompleteQueueCustomer(oc);
                        }
                    } else if ("transactions".equals(s.entity)) {
                        String delStatus = v.getAsString(DatabaseHelper.COL_DELIVERY_STATUS);
                        Long custLocal = v.getAsLong(DatabaseHelper.COL_CUSTOMER_ID);
                        if (com.crowja.damiupos.model.Transaction.DELIVERY_PENDING.equals(delStatus)
                                && custLocal != null && custLocal > 0) {
                            com.crowja.damiupos.model.Customer tc =
                                    new com.crowja.damiupos.db.CustomerDao(dbHelper).getById(custLocal);
                            if (deliveryAssignedHere(v, tc)) collectIncompleteQueueCustomer(tc);
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // Order yang SEBELUM update ini ada di antrian perangkat ini (lihat komentar
            // pulledRoutedAway di atas) tiba-tiba DIPINDAH ke perangkat lain atau DISELESAIKAN oleh
            // pihak lain → kumpulkan untuk notifikasi ⚠. Cabang UPDATE saja (localId != -1): baris
            // baru (INSERT) tak punya "sebelumnya" yang berarti. Dibungkus try/catch — kegagalan di
            // sini tak boleh menggagalkan penerapan sync itu sendiri.
            if (localId != -1 && "transactions".equals(s.entity)) {
                try {
                    Cursor oc = db.query(s.table,
                            new String[]{DatabaseHelper.COL_DELIVERY_DEVICE_UUID,
                                    DatabaseHelper.COL_DELIVERY_STATUS, DatabaseHelper.COL_CUSTOMER_ID},
                            DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
                    if (oc.moveToFirst()) {
                        String oldDev = oc.getString(0);
                        String oldStatus = oc.getString(1);
                        long oldCustId = oc.getLong(2);
                        String myUuid = cfg.getDeviceUuid();
                        boolean wasMine = oldDev == null || oldDev.trim().isEmpty() || oldDev.equals(myUuid);
                        if (wasMine && com.crowja.damiupos.model.Transaction.DELIVERY_PENDING.equals(oldStatus)) {
                            String newDev = v.getAsString(DatabaseHelper.COL_DELIVERY_DEVICE_UUID);
                            String newStatus = v.getAsString(DatabaseHelper.COL_DELIVERY_STATUS);
                            String custName = null;
                            if (oldCustId > 0) {
                                com.crowja.damiupos.model.Customer oldC =
                                        new com.crowja.damiupos.db.CustomerDao(dbHelper).getById(oldCustId);
                                if (oldC != null) custName = oldC.getName();
                            }
                            if (custName == null) custName = "Pelanggan";
                            if (newDev != null && !newDev.trim().isEmpty() && !newDev.equals(myUuid)) {
                                pulledRoutedAway.add(custName);
                            } else if (com.crowja.damiupos.model.Transaction.DELIVERY_DONE.equals(newStatus)) {
                                pulledCompletedElsewhere.add(new String[]{custName,
                                        v.getAsString(DatabaseHelper.COL_COMPLETED_BY_NAME)});
                            }
                        }
                    }
                    oc.close();
                } catch (Throwable ignored) {}
            }

            if (localId != -1) {
                db.update(s.table, v, DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid});
            } else {
                db.insert(s.table, null, v);
            }
            applied++;
        }
        return applied;
    }

    // --------------------------------------------------------------- helpers

    private String uuidForLocalId(SQLiteDatabase db, String table, long localId) {
        Map<Long, String> cache = uuidCache.get(table);
        if (cache == null) { cache = new HashMap<>(); uuidCache.put(table, cache); }
        if (cache.containsKey(localId)) return cache.get(localId);
        String uuid = null;
        Cursor c = db.query(table, new String[]{DatabaseHelper.COL_SYNC_UUID},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(localId)}, null, null, null);
        if (c.moveToFirst()) uuid = c.getString(0);
        c.close();
        cache.put(localId, uuid);
        return uuid;
    }

    /**
     * Kirim notifikasi konversi promo yang terkumpul selama pull, lalu kosongkan daftarnya.
     * Sengaja TIDAK digate role: target utamanya justru perangkat marketing pengakuisisi
     * (deliverAdminMessage = kanal "Pesan & Pembaruan" yang memang diterima marketing). Lebih
     * dari satu sekaligus → SATU notifikasi agregat: deliverAdminMessage menyimpan popup in-app
     * di SATU slot pending, jadi beberapa pesan individual saling menimpa. ID notifikasi per
     * pelanggan diturunkan dari hash nama — konversi pelanggan BERBEDA di sync berbeda tidak
     * saling menimpa notifikasi yang belum dibaca.
     */
    private void deliverPromoConversions() {
        try {
            if (pulledPromoConversions.isEmpty()) return;
            if (pulledPromoConversions.size() > 1) {
                OnlineNotifier.deliverAdminMessage(appCtx, "🎉 Konversi Promosi",
                        pulledPromoConversions.size() + " pelanggan promosi Anda sudah melakukan "
                                + "pembelian pertama. Lihat di menu Pelanggan Promosi.", 7930);
            } else {
                String[] pc = pulledPromoConversions.get(0);
                int id = 7931 + Math.abs((pc[0] != null ? pc[0].hashCode() : 0) % 60);
                OnlineNotifier.deliverAdminMessage(appCtx, "🎉 Konversi Promosi",
                        pc[0] + " (pelanggan promosi Anda) melakukan pembelian pertama pada "
                                + pc[1] + ".", id);
            }
            pulledPromoConversions.clear();
        } catch (Throwable ignored) {
            // Notifikasi tak boleh menggagalkan sync.
        }
    }

    /**
     * Deteksi rising-edge KONVERSI PROMO saat pull customers: pelanggan MILIK PERANGKAT INI yang
     * dulu diakuisisi lewat promo galon GRATIS (ada JUAL Rp 0 lokal pada hari daftarnya — transaksi
     * akuisisi memang tercatat di perangkat ini) dan agg pembelian-berbayar-pertamanya BARU terisi
     * dari server (sebelumnya kosong secara lokal). Pembelian itu biasanya terjadi di perangkat
     * depot, tak terlihat dari transaksi lokal HP marketing — hanya agg server yang tahu.
     * Hanya konversi BARU (≤ 3 hari) yang dinotifikasi: pull pertama setelah upgrade APK membawa
     * semua konversi historis sekaligus — tanpa jendela ini HP dibanjiri notifikasi lama.
     */
    private void collectPromoConversion(SQLiteDatabase db, String table, String uuid,
                                        long localId, JSONObject obj) {
        try {
            // Bukan gate is_mine: serah-terima "Sudah Order Ulang" memindahkan origin ke 'web'
            // (is_mine jadi false) padahal perangkat INILAH pengakuisisinya — probe transaksi
            // akuisisi lokal di bawah sudah membatasi ke perangkat yang benar.
            if (localId == -1) return;
            String paid = obj.isNull("agg_first_paid_jual")
                    ? null : obj.optString("agg_first_paid_jual", null);
            if (paid == null || paid.length() < 10) return;
            String paidDay = paid.substring(0, 10);
            // Jendela kebaruan: konversi lebih tua dari 3 hari masuk diam-diam (tanpa notifikasi).
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, -3);
            String cutoff = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(cal.getTime());
            if (paidDay.compareTo(cutoff) < 0) return;

            String name = null, createdAt = null, prevPaid = null;
            try (Cursor c = db.query(table, new String[]{DatabaseHelper.COL_NAME,
                            DatabaseHelper.COL_CREATED_AT, DatabaseHelper.COL_SRV_FIRST_PAID},
                    DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null)) {
                if (c.moveToFirst()) { name = c.getString(0); createdAt = c.getString(1); prevPaid = c.getString(2); }
            }
            if (prevPaid != null && !prevPaid.isEmpty()) return;   // bukan rising edge — sudah tahu
            if (createdAt == null || createdAt.length() < 10) return;
            String regDay = createdAt.substring(0, 10);
            if (paidDay.compareTo(regDay) <= 0) return;   // pembelian di hari daftar = bagian akuisisi
            // Akuisisi promo gratis DI PERANGKAT INI: JUAL total 0 pada hari daftar pelanggan.
            // [PENCAIRAN KOMISI] = JUAL Rp 0 sistem (payout reseller), bukan akuisisi promo —
            // dikecualikan, selaras CustomerDao.getPromoCustomers & Reports::promoCustomers.
            try (Cursor c2 = db.rawQuery(
                    "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                            + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + "=?"
                            + " AND " + DatabaseHelper.COL_TYPE + "='JUAL'"
                            + " AND substr(" + DatabaseHelper.COL_TANGGAL + ",1,10)=?"
                            + " AND " + DatabaseHelper.COL_TOTAL_HARGA + "=0"
                            + " AND COALESCE(" + DatabaseHelper.COL_CATATAN + ",'') NOT LIKE '%[PENCAIRAN KOMISI]%'",
                    new String[]{String.valueOf(localId), regDay})) {
                if (!(c2.moveToFirst() && c2.getInt(0) > 0)) return;
            }
            // Perangkat yang MENJUAL konversinya sendiri (depot satu-HP) sudah melihat popup
            // perayaan saat transaksi disimpan (isRepeatFromFreePromo) — jangan dinotifikasi dua
            // kali. Pembelian yang tercatat lokal di hari itu = perangkat ini penjualnya.
            try (Cursor c3 = db.rawQuery(
                    "SELECT COUNT(*) FROM " + DatabaseHelper.TABLE_TRANSACTIONS
                            + " WHERE " + DatabaseHelper.COL_CUSTOMER_ID + "=?"
                            + " AND " + DatabaseHelper.COL_TYPE + "='JUAL'"
                            + " AND " + DatabaseHelper.COL_TOTAL_HARGA + ">0"
                            + " AND substr(" + DatabaseHelper.COL_TANGGAL + ",1,10)=?",
                    new String[]{String.valueOf(localId), paidDay})) {
                if (c3.moveToFirst() && c3.getInt(0) > 0) return;
            }
            pulledPromoConversions.add(new String[]{name != null ? name : "Pelanggan", paidDay});
        } catch (Throwable ignored) {
            // Deteksi notifikasi tak boleh menggagalkan pull.
        }
    }

    private long localIdForUuid(SQLiteDatabase db, String table, String uuid) {
        long id = 0;
        Cursor c = db.query(table, new String[]{DatabaseHelper.COL_ID},
                DatabaseHelper.COL_SYNC_UUID + "=?", new String[]{uuid}, null, null, null);
        if (c.moveToFirst()) id = c.getLong(0);
        c.close();
        return id;
    }

    private static void putTyped(ContentValues v, String col, Object val) {
        if (val == null || val == JSONObject.NULL) v.putNull(col);
        else if (val instanceof Boolean) v.put(col, ((Boolean) val) ? 1 : 0);
        else if (val instanceof Integer) v.put(col, (Integer) val);
        else if (val instanceof Long) v.put(col, (Long) val);
        else if (val instanceof Double) v.put(col, (Double) val);
        else v.put(col, val.toString());
    }

    private static String str(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 ? c.getString(idx) : null;
    }

    private static long getLong(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        return idx >= 0 && !c.isNull(idx) ? c.getLong(idx) : 0;
    }
}
