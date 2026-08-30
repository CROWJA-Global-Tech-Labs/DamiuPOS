package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.crowja.damiupos.BuildConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SettingsDao {

    // Developer bypass: when true, app behaves as if Pro is always active.
    // Default mengikuti BuildConfig.BYPASS_PRO (lihat app/build.gradle).
    // Bisa juga di-override saat runtime via setBypassPro() kalau perlu.
    private static volatile boolean BYPASS_PRO = BuildConfig.BYPASS_PRO;

    public static final String KEY_DEFAULT_ONGKIR = "default_ongkir";
    public static final String KEY_POINTS_ENABLED = "points_enabled";
    public static final String KEY_POINTS_PER_AMOUNT = "points_per_amount";
    public static final String KEY_POINTS_REWARD_THRESHOLD = "points_reward_threshold";
    /** Ambang "Bonus Beli N Gratis 1", PER JENIS PRODUK — cermin App\Support\ProductBonus::threshold
     *  di web. Diatur di web (Konfigurasi → Bonus Beli N Gratis 1), HP hanya membaca. */
    public static final String KEY_PRODUCT_BONUS_THRESHOLD = "product_bonus_threshold";
    public static final String KEY_DEPOT_NAME = "depot_name";
    public static final String KEY_DEPOT_ADDRESS = "depot_address";
    public static final String KEY_DEPOT_PHONE = "depot_phone";
    /** URL logo depot (di-set di web Konfigurasi, disinkron lewat app_settings). Diunduh & di-cache
     *  lokal lalu ditampilkan di Pengaturan + struk. */
    public static final String KEY_DEPOT_LOGO_URL = "depot_logo_url";
    public static final String KEY_WIZARD_COMPLETED = "wizard_completed";
    public static final String KEY_FOLLOWUP_DAYS = "followup_days";
    public static final String KEY_STOCK_ALERT = "stock_alert";
    public static final String KEY_HARGA_BOTOL_GALON = "harga_botol_galon";
    public static final String KEY_LAST_GALON_OWNERSHIP = "last_galon_ownership";
    public static final String KEY_PRO_ACTIVE = "pro_active";
    public static final String KEY_PRO_PURCHASE_TOKEN = "pro_purchase_token";
    public static final String KEY_PRO_LAST_VERIFIED_AT = "pro_last_verified_at";
    public static final String KEY_PRO_PRODUCT_ID = "pro_product_id";
    public static final String KEY_PRO_EXPIRY_AT = "pro_expiry_at";
    /** Pro temp dari rewarded ad — millis since epoch. Pro aktif kalau > now. */
    public static final String KEY_PRO_TEMP_UNTIL = "pro_temp_until";
    /** Last rewarded ad watched — untuk enforce cooldown (REWARD_COOLDOWN_MS). */
    public static final String KEY_LAST_REWARD_AT = "last_reward_at";
    public static final String KEY_WA_RINGTONE_URI = "wa_ringtone_uri";
    public static final String KEY_WA_REPLY_TEMPLATE = "wa_reply_template";
    public static final String DEFAULT_WA_REPLY_TEMPLATE = "Baik, siap kak";
    /** Komisi reseller per galon (Rupiah). Default 1000. */
    public static final String KEY_RESELLER_KOMISI = "reseller_komisi_per_galon";
    public static final double DEFAULT_RESELLER_KOMISI = 1000;
    /** Template pesan follow-up WhatsApp. Placeholder yang didukung:
     *  {nama} {hari} {tanggal} {hari_lalu} — di-replace saat kirim. */
    public static final String KEY_FOLLOWUP_TEMPLATE = "followup_template";
    public static final String DEFAULT_FOLLOWUP_TEMPLATE =
            "Bismillah, menginformasikan kakak terakhir membeli air minum di "
            + "{hari}, {tanggal} (sudah {hari_lalu} hari). "
            + "Mau saya kirim lagi {hari_kirim} kak? Terima kasih";
    /** Nama merek yang disisipkan ke PANTUN follow-up (token {brand}). Diatur di web Konfigurasi →
     *  Follow-up Pelanggan, tersinkron ke HP. SENGAJA terpisah dari depot_name: nama depot di
     *  lapangan panjang ("FREZ - NGEMPLAK") dan merusak irama baris pantun. Kosong = jatuh ke
     *  depot_name (lihat PantunPicker.brand). */
    public static final String KEY_PANTUN_BRAND = "pantun_brand";
    /** Pembuka & penutup pesan follow-up PANTUN (Konfigurasi web, tersinkron). Bentuk pesannya:
     *  pembuka / baris kosong / pantun / baris kosong / penutup. Keduanya mendukung {nama};
     *  penutup juga {hari_kirim} ("hari ini"/"besok" mengikuti jam tutup). CERMIN
     *  App\Support\FollowUpWa::DEFAULT_OPENER & DEFAULT_CLOSER — ubah keduanya bersamaan. */
    public static final String KEY_FOLLOWUP_OPENER = "followup_opener";
    public static final String DEFAULT_FOLLOWUP_OPENER = "Assalamu'alaikum";
    public static final String KEY_FOLLOWUP_CLOSER = "followup_closer";
    public static final String DEFAULT_FOLLOWUP_CLOSER =
            "Air nya sudah habis belum nggih? Saya kirim {hari_kirim} pripun?";
    /** Paket pantun yang diunduh dari server + cap versinya. LOKAL saja — sengaja TIDAK masuk
     *  SHAREABLE_KEYS supaya tak pernah ikut terdorong balik ke server saat push. */
    public static final String KEY_PANTUN_PACK = "pantun_pack";
    public static final String KEY_PANTUN_PACK_VERSION = "pantun_pack_version";
    /**
     * Nomor langkah rotasi pantun MILIK PERANGKAT INI — naik satu tiap kali HP mengirim follow-up
     * berpantun, supaya pantunnya dipakai BERURUTAN (melingkar) dan seluruh isi paket kebagian,
     * bukan diacak dan menyisakan sebagian tak pernah terkirim.
     *
     * <p>LOKAL saja, sama seperti dua kunci di atas: sengaja TIDAK masuk SHAREABLE_KEYS. Ini
     * penghitung yang NAIK, dan sinkronisasi kita LWW (yang menang = yang paling akhir menulis) —
     * kalau ikut terdorong, dua HP yang sama-sama mengirim akan saling menimpa dan kenaikan salah
     * satunya hilang. Web memutar rotasinya sendiri dari {@code customers.followup_count}, jadi tiap
     * sisi jalan berurutan menurut hitungannya masing-masing tanpa perlu satu penghitung bersama.
     */
    public static final String KEY_PANTUN_CURSOR = "pantun_cursor";
    /** Jam tutup operasional cabang (HH:mm; Konfigurasi web, tersinkron). Lewat jam ini,
     *  {hari_kirim} / frasa "hari ini" pada pesan follow-up WA otomatis jadi "besok". */
    public static final String KEY_OPS_CLOSE_TIME = "ops_close_time";
    public static final String DEFAULT_OPS_CLOSE_TIME = "17:00";
    /** Jam buka operasional cabang (HH:mm; Konfigurasi web, tersinkron). Dipakai preset jadwal
     *  Pesanan Tertunda ("Besok {jam}"/"Lusa {jam}") — cermin App\Support\OpsHours::DEFAULT_OPEN. */
    public static final String KEY_OPS_OPEN_TIME = "ops_open_time";
    public static final String DEFAULT_OPS_OPEN_TIME = "08:00";
    /** Template pesan WA yang dikirim bersama gambar struk. Placeholder: {nama} {depot}.
     *  Diatur di dashboard (Konfigurasi → Pesan WA Struk), tersinkron ke semua perangkat;
     *  link tracking & kampanye tetap ditambahkan otomatis setelah pesan ini. */
    public static final String KEY_WA_STRUK_TEMPLATE = "wa_struk_template";
    public static final String DEFAULT_WA_STRUK_TEMPLATE =
            "👋 Assalamu'alaikum,\nPelanggan Yth, berikut struk pembelian air minum Anda. Terima kasih 🙏";
    /** Detail rekening transfer bank — disematkan OTOMATIS di struk (WA) hanya saat metode
     *  pembayaran transaksi = TRANSFER. Diatur di dashboard (Konfigurasi → Pesan WA Struk),
     *  tersinkron ke semua perangkat. Kosong = tak ada baris rekening disematkan. */
    public static final String KEY_BANK_TRANSFER_DETAIL = "bank_transfer_detail";
    /** URL gambar QRIS CABANG INI (diunggah admin di Konfigurasi → Pesan WA Struk), disematkan di
     *  struk WA saat metode pembayaran = QRIS. Tersinkron via app_settings pull biasa (baris ini
     *  memang selalu ikut terkirim tiap pull — hanya belum pernah dibaca sisi Android sebelum ini).
     *  Kosong = belum diunggah → tak ada baris disematkan. */
    public static final String KEY_QRIS_IMAGE_URL = "qris_image_url";
    /** Waktu (jam) sebelum item APPROVED/REJECTED di-arsipkan otomatis.
     *  Default 24 jam; 0 = matikan auto-archive. */
    public static final String KEY_WA_AUTO_ARCHIVE_HOURS = "wa_auto_archive_hours";
    public static final int DEFAULT_WA_AUTO_ARCHIVE_HOURS = 24;
    /** Id pesanan terakhir yang sudah "diakui" user (ditap) — sound stop kalau
     *  latest pending id <= ini. Reset ke 0 = belum ada yg diakui. */
    public static final String KEY_WA_LAST_ALERT_ID = "wa_last_alert_id";

    /** 1 = fitur multi user & absensi aktif (wajib login PIN + clock in). */
    public static final String KEY_MULTIUSER_ENABLED = "multiuser_enabled";
    /** Jam kerja normal per HARI (ambang lembur harian & basis normalisasi
     *  bulanan: jam wajib = hari kerja × ini). Default 7. */
    public static final String KEY_DAILY_NORMAL_HOURS = "daily_normal_hours";
    public static final double DEFAULT_DAILY_NORMAL_HOURS = 7;
    /** Jumlah hari kerja per pekan (1–7). Basis jam kerja ideal periode:
     *  hari ideal periode = hari kerja/pekan × (jumlah hari periode ÷ 7). */
    public static final String KEY_WORK_DAYS_PER_WEEK = "work_days_per_week";
    public static final int DEFAULT_WORK_DAYS_PER_WEEK = 6;
    /** Asumsi liter per galon untuk Analisa & Prediksi (default 19). */
    public static final String KEY_LITERS_PER_GALON = "liters_per_galon";
    public static final double DEFAULT_LITERS_PER_GALON = 19;
    /** Bonus penjualan per galon untuk staf (Rp). Aktif/nonaktif + nominal. */
    public static final String KEY_SALES_BONUS_ENABLED = "sales_bonus_enabled";
    public static final String KEY_SALES_BONUS_PER_GALON = "sales_bonus_per_galon";
    /** Tanggal cut-off rekap absensi bulanan (1..31). Default 28. Periode =
     *  (cutoff+1 bulan lalu) s/d (cutoff bulan ini). */
    public static final String KEY_PAYROLL_CUTOFF_DAY = "payroll_cutoff_day";
    public static final int DEFAULT_PAYROLL_CUTOFF_DAY = 28;
    /** Id user yang sedang login (clock in). 0 = tidak ada (idle/istirahat). */
    public static final String KEY_CURRENT_USER_ID = "current_user_id";
    public static final String KEY_CURRENT_USER_NAME = "current_user_name";
    /** User yang sedang istirahat (shift masih terbuka) — supaya bisa "Lanjut Kerja"
     *  satu ketukan tanpa PIN/selfie. 0 = tidak ada yang istirahat. */
    public static final String KEY_BREAK_USER_ID = "break_user_id";
    public static final String KEY_BREAK_USER_NAME = "break_user_name";
    /** 1 = ada shift terbuka (antara clock in pertama s/d Pulang), termasuk saat
     *  istirahat. Dipakai service polling agar tetap aktif selama shift. */
    public static final String KEY_SHIFT_ACTIVE = "shift_active";
    /** Cabut kredit galon staf bila FOTO/KOORDINAT pelanggan belum lengkap saat delivery selesai
     *  (di-set di dashboard, HP hanya baca). Aktif → popup peringatan + stempel tenggang 24 jam saat
     *  Selesaikan. Harus cocok dgn App\Support\CreditGuard::KEY di server. */
    public static final String KEY_REVOKE_CREDIT_INCOMPLETE = "revoke_credit_incomplete_delivery";

    /** Cegah staf HP menyelesaikan delivery pelanggan yang detailnya ditandai BERMASALAH sebelum
     *  mengajukan "sudah diperbaiki + catatan" (persetujuan owner). Dashboard-authoritative, HP baca.
     *  Default AKTIF. Harus cocok dgn key di RemoteConfigController server. */
    public static final String KEY_BLOCK_DELIVERY_ON_ISSUE = "block_delivery_on_issue";

    /** BUKTI SELESAI: kurir wajib MEMOTRET bukti sebelum menandai order Selesai (foto terunggah
     *  otomatis → tampil di Detail Transaksi web). Dashboard-authoritative (server→HP saja; sengaja
     *  tak masuk phone_writable_settings server), HP hanya membaca. Default NONAKTIF — digelar
     *  diam-diam dulu, diaktifkan per cabang dari Konfigurasi. Harus cocok dgn key server. */
    public static final String KEY_DELIVERY_PROOF_REQUIRED = "delivery_proof_required";

    /** BUKTI SELESAI OPSIONAL: fotonya DITAWARKAN (boleh dilewati) alih-alih diwajibkan. Diabaikan
     *  bila KEY_DELIVERY_PROOF_REQUIRED aktif — "wajib" selalu menang. Dashboard-authoritative. */
    public static final String KEY_DELIVERY_PROOF_OPTIONAL = "delivery_proof_optional";

    /** Struk WA TANPA emoji (depot yang pelanggannya banyak memakai HP jadul).
     *  Dashboard-authoritative; harus cocok dgn key server. Cermin App\Support\StrukWa. */
    public static final String KEY_WA_STRUK_NO_EMOJI = "wa_struk_no_emoji";

    /**
     * Kunci konfigurasi bisnis BRANCH yang aman disinkronkan lintas perangkat
     * (app_settings). ALLOWLIST — apa pun di luar daftar ini TIDAK PERNAH dikirim
     * ke server. Sengaja EXCLUDE: rahasia (sync_*, pro_*), state
     * sesi/perangkat (current_user_*, wizard, cursor, last_*), dan toggle
     * device-spesifik (ringtone).
     */
    public static final Set<String> SHAREABLE_KEYS = new HashSet<>(Arrays.asList(
            KEY_DEFAULT_ONGKIR,
            KEY_POINTS_ENABLED, KEY_POINTS_PER_AMOUNT, KEY_POINTS_REWARD_THRESHOLD,
            KEY_PRODUCT_BONUS_THRESHOLD,
            KEY_DEPOT_NAME, KEY_DEPOT_ADDRESS, KEY_DEPOT_PHONE, KEY_DEPOT_LOGO_URL,
            KEY_FOLLOWUP_DAYS, KEY_FOLLOWUP_TEMPLATE, KEY_PANTUN_BRAND,
            KEY_FOLLOWUP_OPENER, KEY_FOLLOWUP_CLOSER,
            KEY_OPS_CLOSE_TIME, KEY_OPS_OPEN_TIME, KEY_WA_STRUK_TEMPLATE,
            KEY_BANK_TRANSFER_DETAIL, KEY_QRIS_IMAGE_URL,
            KEY_STOCK_ALERT, KEY_HARGA_BOTOL_GALON, KEY_RESELLER_KOMISI,
            KEY_MULTIUSER_ENABLED,
            KEY_DAILY_NORMAL_HOURS, KEY_WORK_DAYS_PER_WEEK, KEY_LITERS_PER_GALON,
            KEY_SALES_BONUS_ENABLED, KEY_SALES_BONUS_PER_GALON,
            KEY_PAYROLL_CUTOFF_DAY,
            KEY_WA_REPLY_TEMPLATE, KEY_WA_AUTO_ARCHIVE_HOURS,
            KEY_REVOKE_CREDIT_INCOMPLETE, KEY_BLOCK_DELIVERY_ON_ISSUE,
            KEY_DELIVERY_PROOF_REQUIRED, KEY_DELIVERY_PROOF_OPTIONAL,
            KEY_WA_STRUK_NO_EMOJI
    ));

    private final DatabaseHelper dbHelper;

    public SettingsDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    public void set(String key, String value) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_SETTING_KEY, key);
        values.put(DatabaseHelper.COL_SETTING_VALUE, value);
        // Only whitelisted branch-config keys become dirty for app_settings sync;
        // everything else stays synced=1 (clean) so device-local/secret keys are
        // never pushed to the cloud.
        if (SHAREABLE_KEYS.contains(key)) {
            values.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
            values.put(DatabaseHelper.COL_SYNCED, 0);
        } else {
            values.put(DatabaseHelper.COL_SYNCED, 1);
        }
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------- app_settings sync helpers

    /** Dirty whitelisted settings to push: each = {key, value, edited_at}. */
    public List<String[]> getDirtyShared() {
        List<String[]> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SETTINGS,
                new String[]{DatabaseHelper.COL_SETTING_KEY, DatabaseHelper.COL_SETTING_VALUE,
                        DatabaseHelper.COL_EDITED_AT},
                DatabaseHelper.COL_SYNCED + "=0", null, null, null, null);
        while (c.moveToNext()) {
            String key = c.getString(0);
            if (!SHAREABLE_KEYS.contains(key)) continue;   // defense in depth
            out.add(new String[]{key, c.getString(1), c.getString(2)});
        }
        c.close();
        return out;
    }

    /** All whitelisted (shareable) settings as key→value — uploaded for archiving on enroll. */
    public java.util.Map<String, String> getAllShared() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DatabaseHelper.TABLE_SETTINGS,
                new String[]{DatabaseHelper.COL_SETTING_KEY, DatabaseHelper.COL_SETTING_VALUE},
                null, null, null, null, null);
        while (c.moveToNext()) {
            String key = c.getString(0);
            if (SHAREABLE_KEYS.contains(key)) out.put(key, c.getString(1));
        }
        c.close();
        return out;
    }

    /** Mark the given setting keys as pushed (synced=1). */
    public void markSharedSynced(List<String> keys) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SYNCED, 1);
        for (String k : keys) {
            db.update(DatabaseHelper.TABLE_SETTINGS, v,
                    DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{k});
        }
    }

    /**
     * Apply a setting received from sync. Only whitelisted keys are accepted.
     * Last-write-wins: skip if a newer un-pushed local edit exists.
     */
    public void applySyncedSetting(String key, String value, String editedAt, boolean deleted) {
        if (key == null || !SHAREABLE_KEYS.contains(key)) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        boolean exists = false; int localSynced = 1; String localEdited = "";
        Cursor c = db.query(DatabaseHelper.TABLE_SETTINGS,
                new String[]{DatabaseHelper.COL_SYNCED, DatabaseHelper.COL_EDITED_AT},
                DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{key}, null, null, null);
        if (c.moveToFirst()) { exists = true; localSynced = c.getInt(0); localEdited = c.getString(1); }
        c.close();
        if (exists && localSynced == 0 && localEdited != null && editedAt != null
                && localEdited.compareTo(editedAt) > 0) {
            return;   // keep newer un-pushed local edit
        }
        if (deleted) {
            db.delete(DatabaseHelper.TABLE_SETTINGS,
                    DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{key});
            return;
        }
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SETTING_KEY, key);
        v.put(DatabaseHelper.COL_SETTING_VALUE, value);
        v.put(DatabaseHelper.COL_EDITED_AT, editedAt);
        v.put(DatabaseHelper.COL_SYNCED, 1);
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String get(String key, String defaultValue) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(DatabaseHelper.TABLE_SETTINGS, new String[]{DatabaseHelper.COL_SETTING_VALUE},
                DatabaseHelper.COL_SETTING_KEY + "=?", new String[]{key},
                null, null, null);
        String result = defaultValue;
        if (cursor.moveToFirst()) {
            result = cursor.getString(0);
        }
        cursor.close();
        return result;
    }

    // -- Pesan admin tertunda --
    // Pesan dari dashboard ("Kirim Pesan ke Perangkat") yang belum sempat ditampilkan
    // sebagai popup karena app sedang di background. Disimpan lokal (bukan SHAREABLE_KEYS,
    // jadi tidak ikut sync) lalu ditampilkan saat MainActivity berikutnya tampil.
    private static final String K_PENDING_MSG_TITLE = "pending_admin_msg_title";
    private static final String K_PENDING_MSG_BODY  = "pending_admin_msg_body";

    public void setPendingAdminMessage(String title, String body) {
        set(K_PENDING_MSG_TITLE, title != null ? title : "");
        set(K_PENDING_MSG_BODY, body != null ? body : "");
    }
    public String getPendingAdminMessageTitle() { return get(K_PENDING_MSG_TITLE, ""); }
    public String getPendingAdminMessageBody()  { return get(K_PENDING_MSG_BODY, ""); }
    /** Ada pesan admin yang belum ditampilkan sebagai popup? */
    public boolean hasPendingAdminMessage() {
        String t = getPendingAdminMessageTitle();
        return t != null && !t.isEmpty();
    }
    public void clearPendingAdminMessage() {
        set(K_PENDING_MSG_TITLE, "");
        set(K_PENDING_MSG_BODY, "");
    }

    // -- Antrian Delivery: status tampilan layar (LOKAL saja, bukan SHAREABLE_KEYS → tak ikut sync) --
    /** Kartu "Strategi Pengiriman" sedang dilipat? Default TIDAK (terbuka saat pertama kali). */
    private static final String K_DLV_STRATEGY_COLLAPSED = "dlv_strategy_collapsed";
    /** Id LOKAL order yang sedang dijalankan kurir (mode kerja); "" / "0" = tidak ada. Dipakai supaya
     *  mode kerja tetap bertahan saat layar ditinggal sebentar (mis. buka Maps lalu balik). Sejak
     *  "jalankan bersamaan" nilainya CSV beberapa id (satu rit multi-order); nilai lama satu-angka
     *  tetap terbaca apa adanya oleh parser di bawah. */
    private static final String K_DLV_RUNNING_TRX = "dlv_running_trx";

    /** Order MANDIRI yang struk WA-nya sudah dikonfirmasi terkirim kurir (lihat
     *  DeliveryQueueActivity.requireSelfOrderStrukThen). LOKAL per-perangkat & tak disinkronkan:
     *  gerbangnya memang urusan HP yang mengantar, bukan status yang perlu diketahui cabang. */
    private static final String K_DLV_STRUK_SENT_TRX = "dlv_struk_sent_trx";

    public boolean isDeliveryStrategyCollapsed() { return "1".equals(get(K_DLV_STRATEGY_COLLAPSED, "0")); }

    public void setDeliveryStrategyCollapsed(boolean v) { set(K_DLV_STRATEGY_COLLAPSED, v ? "1" : "0"); }

    /** Semua order yang sedang dijalankan, urut sesuai saat dijalankan. Kosong = tak ada. */
    public java.util.LinkedHashSet<Long> getDeliveryRunningTrxIds() {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (String part : get(K_DLV_RUNNING_TRX, "").split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) out.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public java.util.LinkedHashSet<Long> getSelfOrderStrukSentIds() {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (String part : get(K_DLV_STRUK_SENT_TRX, "").split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) out.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public void setSelfOrderStrukSentIds(java.util.Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            int skip = Math.max(0, ids.size() - 300);   // simpan 300 terakhir, jangan tumbuh selamanya
            int i = 0;
            for (Long id : ids) {
                if (id == null || id <= 0 || i++ < skip) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        set(K_DLV_STRUK_SENT_TRX, sb.toString());
    }

    public void setDeliveryRunningTrxIds(java.util.Collection<Long> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null || id <= 0) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        set(K_DLV_RUNNING_TRX, sb.toString());
    }

    // -- Antrean "Lengkapi Data Pelanggan" tertunda --
    // Id pelanggan (LOKAL) dari pesanan baru yang belum lengkap foto/koordinat, menunggu
    // ditampilkan sebagai popup oleh MainActivity begitu tampil (mis. sinkron terjadi di
    // background). Lokal saja (bukan SHAREABLE_KEYS) → tak ikut sync. Comma-separated, dedup.
    private static final String K_PENDING_INCOMPLETE = "pending_incomplete_warn";
    private static final int PENDING_INCOMPLETE_CAP = 20;

    /** Antre id pelanggan untuk diperingatkan; dedup dengan yang sudah antre, dibatasi jumlahnya. */
    public void addPendingIncompleteWarn(java.util.List<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) return;
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        String cur = get(K_PENDING_INCOMPLETE, "");
        if (cur != null) {
            for (String s : cur.split(",")) { String t = s.trim(); if (!t.isEmpty()) set.add(t); }
        }
        for (Long id : customerIds) if (id != null && id > 0) set.add(String.valueOf(id));
        // Jangan menumpuk tanpa batas bila app lama tak dibuka: buang yang terlama.
        while (set.size() > PENDING_INCOMPLETE_CAP) {
            java.util.Iterator<String> it = set.iterator(); it.next(); it.remove();
        }
        set(K_PENDING_INCOMPLETE, android.text.TextUtils.join(",", set));
    }

    /** Ambil SEMUA id yang antre lalu KOSONGKAN antrean (dikonsumsi sekali). */
    public java.util.List<Long> takePendingIncompleteWarn() {
        String cur = get(K_PENDING_INCOMPLETE, "");
        java.util.List<Long> out = new java.util.ArrayList<>();
        if (cur != null && !cur.isEmpty()) {
            for (String s : cur.split(",")) {
                try { long id = Long.parseLong(s.trim()); if (id > 0) out.add(id); }
                catch (NumberFormatException ignored) {}
            }
        }
        if (!out.isEmpty()) set(K_PENDING_INCOMPLETE, "");
        return out;
    }

    public double getDefaultOngkir() {
        String val = get(KEY_DEFAULT_ONGKIR, "0");
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setDefaultOngkir(double value) {
        set(KEY_DEFAULT_ONGKIR, String.valueOf(value));
    }

    public boolean isPointsEnabled() {
        return "1".equals(get(KEY_POINTS_ENABLED, "0"));
    }

    public void setPointsEnabled(boolean enabled) {
        set(KEY_POINTS_ENABLED, enabled ? "1" : "0");
    }

    /** Rupiah amount required per 1 point. Default 10.000 */
    public double getPointsPerAmount() {
        String val = get(KEY_POINTS_PER_AMOUNT, "10000");
        try {
            double d = Double.parseDouble(val);
            return d > 0 ? d : 10000;
        } catch (NumberFormatException e) {
            return 10000;
        }
    }

    public void setPointsPerAmount(double value) {
        set(KEY_POINTS_PER_AMOUNT, String.valueOf(value));
    }

    /** Points needed for a reward. Default 100 */
    public int getPointsRewardThreshold() {
        String val = get(KEY_POINTS_REWARD_THRESHOLD, "100");
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    /** "Bonus Beli N Gratis 1": galon TERBAYAR per jenis produk → 1 unit gratis. Default 10,
     *  cermin App\Support\ProductBonus::DEFAULT_THRESHOLD di web. Web-only untuk mengubah. */
    public int getProductBonusThreshold() {
        String val = get(KEY_PRODUCT_BONUS_THRESHOLD, "10");
        try {
            int v = Integer.parseInt(val);
            return v > 0 ? v : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public void setPointsRewardThreshold(int value) {
        set(KEY_POINTS_REWARD_THRESHOLD, String.valueOf(value));
    }

    public String getDepotName() { return get(KEY_DEPOT_NAME, ""); }
    public void setDepotName(String v) { set(KEY_DEPOT_NAME, v != null ? v : ""); }

    /**
     * Area absensi (geofence) cabang — pusat + radius meter, DIATUR DI WEB (HR &amp; Payslip → Area
     * Absensi) dan turun ke HP lewat pull app_settings biasa. Sengaja TIDAK masuk SHAREABLE_KEYS:
     * server menolak tulisan kunci ini dari perangkat (kunci tata-kelola, lihat
     * SyncController::applySetting), jadi HP hanya membacanya.
     *
     * <p>Kunci &amp; default radius 150 m mengikuti App\Support\Reports::geofence supaya vonis
     * "di luar radius" di HP identik dengan vonis di laporan &amp; slip gaji.
     */
    public static final String KEY_GEOFENCE_LAT = "geofence_lat";
    public static final String KEY_GEOFENCE_LNG = "geofence_lng";
    public static final String KEY_GEOFENCE_RADIUS = "geofence_radius";
    public static final double DEFAULT_GEOFENCE_RADIUS = 150;

    /** Titik pusat area absensi, atau null bila admin belum mengaturnya (geofence nonaktif). */
    public double[] getGeofenceCenter() {
        try {
            String lat = get(KEY_GEOFENCE_LAT, "");
            String lng = get(KEY_GEOFENCE_LNG, "");
            if (lat == null || lat.isEmpty() || lng == null || lng.isEmpty()) return null;
            return new double[]{Double.parseDouble(lat), Double.parseDouble(lng)};
        } catch (Exception e) { return null; }
    }

    /** Radius area absensi (meter); jatuh ke default bila kosong/tak masuk akal. */
    public double getGeofenceRadius() {
        try {
            double v = Double.parseDouble(get(KEY_GEOFENCE_RADIUS,
                    String.valueOf(DEFAULT_GEOFENCE_RADIUS)));
            return v > 0 ? v : DEFAULT_GEOFENCE_RADIUS;
        } catch (Exception e) { return DEFAULT_GEOFENCE_RADIUS; }
    }

    public String getDepotAddress() { return get(KEY_DEPOT_ADDRESS, ""); }
    public void setDepotAddress(String v) { set(KEY_DEPOT_ADDRESS, v != null ? v : ""); }

    public String getDepotPhone() { return get(KEY_DEPOT_PHONE, ""); }
    public void setDepotPhone(String v) { set(KEY_DEPOT_PHONE, v != null ? v : ""); }

    /** URL logo depot (disinkron dari web). Kosong = belum di-set. */
    public String getDepotLogoUrl() { return get(KEY_DEPOT_LOGO_URL, ""); }

    public boolean isWizardCompleted() { return "1".equals(get(KEY_WIZARD_COMPLETED, "0")); }
    public void setWizardCompleted(boolean v) { set(KEY_WIZARD_COMPLETED, v ? "1" : "0"); }

    public int getFollowupDays() {
        try {
            int v = Integer.parseInt(get(KEY_FOLLOWUP_DAYS, "5"));
            return v > 0 ? v : 5;
        } catch (NumberFormatException e) { return 5; }
    }
    public void setFollowupDays(int v) { set(KEY_FOLLOWUP_DAYS, String.valueOf(v > 0 ? v : 5)); }

    public int getStockAlert() {
        try {
            int v = Integer.parseInt(get(KEY_STOCK_ALERT, "30"));
            return v >= 0 ? v : 30;
        } catch (NumberFormatException e) { return 30; }
    }
    public void setStockAlert(int v) { set(KEY_STOCK_ALERT, String.valueOf(v >= 0 ? v : 30)); }

    /** Harga default satu botol galon kosong (untuk ganti rugi). Default 35.000 */
    public double getHargaBotolGalon() {
        String val = get(KEY_HARGA_BOTOL_GALON, "35000");
        try {
            double d = Double.parseDouble(val);
            return d > 0 ? d : 35000;
        } catch (NumberFormatException e) {
            return 35000;
        }
    }

    public void setHargaBotolGalon(double v) {
        set(KEY_HARGA_BOTOL_GALON, String.valueOf(v > 0 ? v : 35000));
    }

    /** Terakhir yang dipilih di form transaksi: PINJAM atau BELI */
    public String getLastGalonOwnership() {
        return get(KEY_LAST_GALON_OWNERSHIP, "PINJAM");
    }

    public void setLastGalonOwnership(String v) {
        set(KEY_LAST_GALON_OWNERSHIP, v != null ? v : "PINJAM");
    }

    public static void setBypassPro(boolean enabled) {
        BYPASS_PRO = enabled;
    }

    public static boolean isBypassProEnabled() {
        return BYPASS_PRO;
    }

    // ---------------- Pro / subscription status (cached locally) ----------------

    public boolean isProActive() {
        return BYPASS_PRO
                || "1".equals(get(KEY_PRO_ACTIVE, "0"))
                || isProTempActive();
    }

    /** Apakah subscriber permanent (bukan temp dari rewarded ad). */
    public boolean isProSubscriber() {
        return BYPASS_PRO || "1".equals(get(KEY_PRO_ACTIVE, "0"));
    }

    /** Apakah Pro temp dari rewarded ad masih berlaku. */
    public boolean isProTempActive() {
        return getProTempUntil() > System.currentTimeMillis();
    }

    /** Pro temp expiry timestamp (millis since epoch). 0 = tidak pernah claim. */
    public long getProTempUntil() {
        try { return Long.parseLong(get(KEY_PRO_TEMP_UNTIL, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }

    public void setProTempUntil(long millis) {
        set(KEY_PRO_TEMP_UNTIL, String.valueOf(millis));
    }

    /** Timestamp terakhir user menonton rewarded ad — untuk cek cooldown. */
    public long getLastRewardAt() {
        try { return Long.parseLong(get(KEY_LAST_REWARD_AT, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }

    public void setLastRewardAt(long millis) {
        set(KEY_LAST_REWARD_AT, String.valueOf(millis));
    }

    /**
     * Bisa user tonton rewarded ad lagi? True kalau cooldown sudah lewat
     * (default {@code BuildConfig.REWARD_COOLDOWN_MS} = 24 jam dari terakhir).
     */
    public boolean canWatchRewardAd() {
        long last = getLastRewardAt();
        if (last == 0) return true;
        return (System.currentTimeMillis() - last) >= BuildConfig.REWARD_COOLDOWN_MS;
    }

    /** Berapa detik lagi sampai user bisa tonton ad lagi. 0 = bisa sekarang. */
    public long secondsUntilNextReward() {
        long last = getLastRewardAt();
        if (last == 0) return 0L;
        long delta = (last + BuildConfig.REWARD_COOLDOWN_MS) - System.currentTimeMillis();
        return Math.max(0L, delta / 1000L);
    }

    public void setProActive(boolean active) {
        set(KEY_PRO_ACTIVE, active ? "1" : "0");
        set(KEY_PRO_LAST_VERIFIED_AT, String.valueOf(System.currentTimeMillis()));
    }

    public String getProPurchaseToken() {
        return get(KEY_PRO_PURCHASE_TOKEN, "");
    }

    public void setProPurchaseToken(String token) {
        set(KEY_PRO_PURCHASE_TOKEN, token != null ? token : "");
    }

    public long getProLastVerifiedAt() {
        try {
            return Long.parseLong(get(KEY_PRO_LAST_VERIFIED_AT, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Product ID dari subscription yang aktif (mis. damiu_pro_monthly / damiu_pro_yearly). */
    public String getProProductId() {
        return get(KEY_PRO_PRODUCT_ID, "");
    }

    public void setProProductId(String productId) {
        set(KEY_PRO_PRODUCT_ID, productId != null ? productId : "");
    }

    /** Estimasi kapan subscription akan habis (millis since epoch). 0 = tidak diketahui. */
    public long getProExpiryAt() {
        try {
            return Long.parseLong(get(KEY_PRO_EXPIRY_AT, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void setProExpiryAt(long millis) {
        set(KEY_PRO_EXPIRY_AT, String.valueOf(millis));
    }


    /** URI nada dering yang dipilih user untuk notifikasi pesanan WA.
     *  Empty = pakai default sistem. */
    public String getWaRingtoneUri() { return get(KEY_WA_RINGTONE_URI, ""); }
    public void setWaRingtoneUri(String uri) {
        set(KEY_WA_RINGTONE_URI, uri != null ? uri : "");
    }

    public long getWaLastAlertId() {
        try { return Long.parseLong(get(KEY_WA_LAST_ALERT_ID, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
    public void setWaLastAlertId(long id) {
        set(KEY_WA_LAST_ALERT_ID, String.valueOf(id));
    }

    /** Berapa jam APPROVED/REJECTED disimpan sebelum auto-arsip. 0 = off. */
    public int getWaAutoArchiveHours() {
        try {
            int v = Integer.parseInt(get(KEY_WA_AUTO_ARCHIVE_HOURS,
                    String.valueOf(DEFAULT_WA_AUTO_ARCHIVE_HOURS)));
            return Math.max(0, v);
        } catch (NumberFormatException e) { return DEFAULT_WA_AUTO_ARCHIVE_HOURS; }
    }
    public void setWaAutoArchiveHours(int hours) {
        set(KEY_WA_AUTO_ARCHIVE_HOURS, String.valueOf(Math.max(0, hours)));
    }

    /** Template pesan balasan yang dipakai tombol "Balas" di inbox. */
    public String getWaReplyTemplate() {
        String v = get(KEY_WA_REPLY_TEMPLATE, DEFAULT_WA_REPLY_TEMPLATE);
        return v != null && !v.isEmpty() ? v : DEFAULT_WA_REPLY_TEMPLATE;
    }
    public void setWaReplyTemplate(String s) {
        set(KEY_WA_REPLY_TEMPLATE, s != null ? s.trim() : "");
    }

    /** Komisi reseller per galon dalam Rupiah. */
    public double getResellerKomisi() {
        try {
            double v = Double.parseDouble(get(KEY_RESELLER_KOMISI,
                    String.valueOf(DEFAULT_RESELLER_KOMISI)));
            return v >= 0 ? v : DEFAULT_RESELLER_KOMISI;
        } catch (NumberFormatException e) { return DEFAULT_RESELLER_KOMISI; }
    }
    public void setResellerKomisi(double v) {
        set(KEY_RESELLER_KOMISI, String.valueOf(v >= 0 ? v : DEFAULT_RESELLER_KOMISI));
    }

    // ---------------- Multi user & absensi ----------------

    public boolean isMultiUserEnabled() {
        return "1".equals(get(KEY_MULTIUSER_ENABLED, "0"));
    }
    public void setMultiUserEnabled(boolean enabled) {
        set(KEY_MULTIUSER_ENABLED, enabled ? "1" : "0");
    }

    /** Setting cabang "cabut kredit galon bila data pelanggan tak lengkap saat delivery selesai"
     *  (dashboard-authoritative, HP hanya baca). Default mati. */
    public boolean isRevokeCreditIncompleteEnabled() {
        return "1".equals(get(KEY_REVOKE_CREDIT_INCOMPLETE, "0"));
    }

    /** Setting cabang "cegah selesaikan delivery bila pelanggan bermasalah" (dashboard-authoritative,
     *  HP hanya baca). Default AKTIF — staf wajib mengajukan "sudah diperbaiki" dulu. */
    public boolean isBlockDeliveryOnIssueEnabled() {
        return !"0".equals(get(KEY_BLOCK_DELIVERY_ON_ISSUE, "1"));
    }

    /** Setting cabang "wajib FOTO bukti sebelum tandai Selesai" (dashboard-authoritative, HP hanya
     *  baca). Default NONAKTIF — fitur digelar diam-diam, diaktifkan per cabang dari Konfigurasi. */
    public boolean isDeliveryProofRequired() {
        return "1".equals(get(KEY_DELIVERY_PROOF_REQUIRED, "0"));
    }

    /** Foto bukti DITAWARKAN (boleh dilewati). "Wajib" menang bila keduanya aktif, jadi di sini
     *  sengaja dikecualikan — supaya pemanggil tak perlu mengurutkan sendiri kedua flag itu. */
    /**
     * Struk WA harus dikirim TANPA emoji? Setelan ini sudah lama ada di dashboard dan nilainya
     * pun sudah tersimpan di HP -- tapi dulu tak pernah ADA yang membacanya di sisi Android,
     * jadi menyalakannya hanya berefek pada struk yang dikirim dari web. Persis pola bug
     * qris_image_url. Paritas kuncinya dikunci SyncParityTest di repo backend.
     */
    public boolean isWaStrukNoEmoji() {
        return "1".equals(get(KEY_WA_STRUK_NO_EMOJI, "0"));
    }

    public boolean isDeliveryProofOptional() {
        return !isDeliveryProofRequired() && "1".equals(get(KEY_DELIVERY_PROOF_OPTIONAL, "0"));
    }

    /**
     * Disable multi-user LOCALLY only — write the value clean (synced=1) so it is never
     * pushed back to the dashboard. Used by the anti-lockout failsafe: a single phone that
     * somehow ends up with no usable user must not turn off staff login branch-wide (the key
     * is now dashboard-authoritative via {@link #SHAREABLE_KEYS}). The dashboard value still
     * wins on the next pull.
     */
    public void setMultiUserEnabledLocal(boolean enabled) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_SETTING_KEY, KEY_MULTIUSER_ENABLED);
        v.put(DatabaseHelper.COL_SETTING_VALUE, enabled ? "1" : "0");
        v.put(DatabaseHelper.COL_EDITED_AT, DatabaseHelper.nowIso());
        v.put(DatabaseHelper.COL_SYNCED, 1);
        db.insertWithOnConflict(DatabaseHelper.TABLE_SETTINGS, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Jam kerja normal per hari (ambang lembur harian + basis normalisasi bulanan). */
    public double getDailyNormalHours() {
        try {
            double v = Double.parseDouble(get(KEY_DAILY_NORMAL_HOURS,
                    String.valueOf(DEFAULT_DAILY_NORMAL_HOURS)));
            return v > 0 ? v : DEFAULT_DAILY_NORMAL_HOURS;
        } catch (NumberFormatException e) { return DEFAULT_DAILY_NORMAL_HOURS; }
    }
    public void setDailyNormalHours(double v) {
        set(KEY_DAILY_NORMAL_HOURS, String.valueOf(v > 0 ? v : DEFAULT_DAILY_NORMAL_HOURS));
    }

    /** Jumlah hari kerja per pekan (1–7, default 6). */
    public int getWorkDaysPerWeek() {
        try {
            int v = Integer.parseInt(get(KEY_WORK_DAYS_PER_WEEK,
                    String.valueOf(DEFAULT_WORK_DAYS_PER_WEEK)));
            if (v < 1) return 1;
            if (v > 7) return 7;
            return v;
        } catch (NumberFormatException e) { return DEFAULT_WORK_DAYS_PER_WEEK; }
    }
    public void setWorkDaysPerWeek(int v) {
        if (v < 1) v = 1;
        if (v > 7) v = 7;
        set(KEY_WORK_DAYS_PER_WEEK, String.valueOf(v));
    }

    public double getLitersPerGalon() {
        try {
            double v = Double.parseDouble(get(KEY_LITERS_PER_GALON,
                    String.valueOf(DEFAULT_LITERS_PER_GALON)));
            return v > 0 ? v : DEFAULT_LITERS_PER_GALON;
        } catch (NumberFormatException e) { return DEFAULT_LITERS_PER_GALON; }
    }
    public void setLitersPerGalon(double v) {
        set(KEY_LITERS_PER_GALON, String.valueOf(v > 0 ? v : DEFAULT_LITERS_PER_GALON));
    }

    public boolean isSalesBonusEnabled() {
        return "1".equals(get(KEY_SALES_BONUS_ENABLED, "0"));
    }
    public void setSalesBonusEnabled(boolean enabled) {
        set(KEY_SALES_BONUS_ENABLED, enabled ? "1" : "0");
    }
    public double getSalesBonusPerGalon() {
        try {
            double v = Double.parseDouble(get(KEY_SALES_BONUS_PER_GALON, "0"));
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) { return 0; }
    }
    public void setSalesBonusPerGalon(double v) {
        set(KEY_SALES_BONUS_PER_GALON, String.valueOf(v > 0 ? v : 0));
    }

    /** Tanggal cut-off rekap absensi bulanan (1..31). */
    public int getPayrollCutoffDay() {
        try {
            int v = Integer.parseInt(get(KEY_PAYROLL_CUTOFF_DAY,
                    String.valueOf(DEFAULT_PAYROLL_CUTOFF_DAY)));
            return (v >= 1 && v <= 31) ? v : DEFAULT_PAYROLL_CUTOFF_DAY;
        } catch (NumberFormatException e) { return DEFAULT_PAYROLL_CUTOFF_DAY; }
    }
    public void setPayrollCutoffDay(int v) {
        set(KEY_PAYROLL_CUTOFF_DAY, String.valueOf((v >= 1 && v <= 31) ? v : DEFAULT_PAYROLL_CUTOFF_DAY));
    }

    /** Id user yang sedang clock in. 0 = belum login / istirahat / sudah out. */
    public long getCurrentUserId() {
        try { return Long.parseLong(get(KEY_CURRENT_USER_ID, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
    public String getCurrentUserName() { return get(KEY_CURRENT_USER_NAME, ""); }

    public void setCurrentUser(long id, String name) {
        set(KEY_CURRENT_USER_ID, String.valueOf(id));
        set(KEY_CURRENT_USER_NAME, name != null ? name : "");
    }
    public void clearCurrentUser() { setCurrentUser(0, ""); }

    /** User yang sedang istirahat (shift terbuka) → boleh "Lanjut Kerja" tanpa PIN/selfie. 0 = tidak ada. */
    public long getBreakUserId() {
        try { return Long.parseLong(get(KEY_BREAK_USER_ID, "0")); }
        catch (NumberFormatException e) { return 0L; }
    }
    public String getBreakUserName() { return get(KEY_BREAK_USER_NAME, ""); }
    public void setBreakUser(long id, String name) {
        set(KEY_BREAK_USER_ID, String.valueOf(id));
        set(KEY_BREAK_USER_NAME, name != null ? name : "");
    }
    public void clearBreakUser() { setBreakUser(0, ""); }

    /** Shift terbuka (clock in pertama s/d Pulang, termasuk istirahat). */
    public boolean isShiftActive() { return "1".equals(get(KEY_SHIFT_ACTIVE, "0")); }
    public void setShiftActive(boolean v) { set(KEY_SHIFT_ACTIVE, v ? "1" : "0"); }

    /** Template pesan follow-up. Lihat {@link #KEY_FOLLOWUP_TEMPLATE} untuk placeholder. */
    public String getFollowUpTemplate() {
        String v = get(KEY_FOLLOWUP_TEMPLATE, DEFAULT_FOLLOWUP_TEMPLATE);
        return v != null && !v.isEmpty() ? v : DEFAULT_FOLLOWUP_TEMPLATE;
    }
    public void setFollowUpTemplate(String s) {
        set(KEY_FOLLOWUP_TEMPLATE, s != null ? s.trim() : "");
    }

    /** Nama merek untuk pantun (web-authoritative, tanpa setter). Lihat {@link #KEY_PANTUN_BRAND}. */
    public String getPantunBrand() {
        String v = get(KEY_PANTUN_BRAND, "");
        return v != null ? v.trim() : "";
    }

    /** Pembuka pesan follow-up pantun (web-authoritative). Lihat {@link #KEY_FOLLOWUP_OPENER}. */
    public String getFollowUpOpener() {
        String v = get(KEY_FOLLOWUP_OPENER, DEFAULT_FOLLOWUP_OPENER);
        return v != null && !v.trim().isEmpty() ? v.trim() : DEFAULT_FOLLOWUP_OPENER;
    }

    /** Penutup pesan follow-up pantun (web-authoritative). Lihat {@link #KEY_FOLLOWUP_CLOSER}. */
    public String getFollowUpCloser() {
        String v = get(KEY_FOLLOWUP_CLOSER, DEFAULT_FOLLOWUP_CLOSER);
        return v != null && !v.trim().isEmpty() ? v.trim() : DEFAULT_FOLLOWUP_CLOSER;
    }

    /** Paket pantun mentah (JSON) hasil unduhan terakhir; "" bila belum pernah diunduh. */
    public String getPantunPack() {
        String v = get(KEY_PANTUN_PACK, "");
        return v != null ? v : "";
    }

    /** Cap versi paket pantun yang SEDANG dipegang perangkat ini. */
    public String getPantunPackVersion() {
        String v = get(KEY_PANTUN_PACK_VERSION, "");
        return v != null ? v.trim() : "";
    }

    /** Simpan paket + versinya sekaligus (dipanggil setelah unduhan sukses). */
    public void setPantunPack(String json, String version) {
        set(KEY_PANTUN_PACK, json != null ? json : "");
        set(KEY_PANTUN_PACK_VERSION, version != null ? version.trim() : "");
    }

    /** Posisi rotasi pantun perangkat ini — lihat {@link #KEY_PANTUN_CURSOR}. */
    public long getPantunCursor() {
        try {
            String v = get(KEY_PANTUN_CURSOR, "0");
            return v == null || v.trim().isEmpty() ? 0L : Long.parseLong(v.trim());
        } catch (Exception e) {
            return 0L;   // nilai rusak → mulai lagi dari awal, jangan sampai menggagalkan kiriman
        }
    }

    /**
     * Majukan rotasi satu langkah dan kembalikan posisi YANG BARU SAJA DIPAKAI (nilai sebelum naik).
     * Dipanggil TEPAT SEKALI per pesan terkirim — memanggilnya untuk sekadar mengintip akan
     * melompati satu pantun.
     */
    public long nextPantunCursor() {
        long cur = getPantunCursor();
        // Dipagari jauh di bawah batas long supaya tak pernah meluap jadi negatif walau perangkat
        // dipakai bertahun-tahun; melingkar di sini tak kelihatan (indeksnya di-modulo lagi nanti).
        set(KEY_PANTUN_CURSOR, String.valueOf(cur >= Long.MAX_VALUE - 1 ? 0L : cur + 1));
        return cur;
    }

    /** Jam tutup operasional (HH:mm) — lihat {@link #KEY_OPS_CLOSE_TIME}. */
    public String getOpsCloseTime() {
        String v = get(KEY_OPS_CLOSE_TIME, DEFAULT_OPS_CLOSE_TIME);
        return v != null && !v.trim().isEmpty() ? v.trim() : DEFAULT_OPS_CLOSE_TIME;
    }

    /** Jam buka operasional (HH:mm) — lihat {@link #KEY_OPS_OPEN_TIME}. */
    public String getOpsOpenTime() {
        String v = get(KEY_OPS_OPEN_TIME, DEFAULT_OPS_OPEN_TIME);
        return v != null && !v.trim().isEmpty() ? v.trim() : DEFAULT_OPS_OPEN_TIME;
    }

    /** Template pesan WA struk. Lihat {@link #KEY_WA_STRUK_TEMPLATE} untuk placeholder. */
    public String getWaStrukTemplate() {
        String v = get(KEY_WA_STRUK_TEMPLATE, DEFAULT_WA_STRUK_TEMPLATE);
        return v != null && !v.isEmpty() ? v : DEFAULT_WA_STRUK_TEMPLATE;
    }

    /** Detail rekening transfer bank (bisa kosong bila belum dikonfigurasi). Lihat
     *  {@link #KEY_BANK_TRANSFER_DETAIL}. */
    public String getBankTransferDetail() {
        String v = get(KEY_BANK_TRANSFER_DETAIL, "");
        return v != null ? v : "";
    }

    /** URL gambar QRIS cabang ini (bisa kosong bila belum diunggah). Lihat {@link #KEY_QRIS_IMAGE_URL}. */
    public String getQrisImageUrl() {
        String v = get(KEY_QRIS_IMAGE_URL, "");
        return v != null ? v : "";
    }

}
