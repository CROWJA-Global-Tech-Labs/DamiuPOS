package com.crowja.damiupos.sync;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Thin synchronous REST client for the DAMIU POS sync backend (call off the main thread). */
public class SyncApi {

    public static class SyncException extends Exception {
        public final int code;
        /** Raw response body (often JSON like {"message":"..."}) — for surfacing server errors. */
        public final String body;
        public SyncException(int code, String message) {
            super("HTTP " + code + ": " + message);
            this.code = code;
            this.body = message;
        }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final SyncSettings cfg;

    public SyncApi(SyncSettings cfg) {
        this.cfg = cfg;
        // Client proses-tunggal (berbagi ConnectionPool → keep-alive dipakai ulang, tak ada
        // handshake TLS baru tiap request meski SyncApi dibuat ulang tiap sync/tick).
        this.client = Http.SHARED;
    }

    public JSONObject enroll(String baseUrl, String enrollKey, @Nullable String deviceUuid,
                             String name, int versionCode, String versionName,
                             @Nullable JSONObject settings) throws Exception {
        JSONObject body = new JSONObject();
        body.put("enroll_key", enrollKey);
        if (deviceUuid != null && !deviceUuid.isEmpty()) body.put("device_uuid", deviceUuid);
        body.put("device_name", name);
        body.put("platform", "android");
        body.put("app_version_code", versionCode);
        body.put("app_version_name", versionName);
        // Current phone settings → archived server-side before the dashboard config overwrites them.
        if (settings != null && settings.length() > 0) body.put("settings", settings);
        return post(trim(baseUrl) + "/api/devices/enroll", body, null);
    }

    public JSONObject push(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/sync/push", body, cfg.getToken());
    }

    public JSONObject pull(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/sync/pull", body, cfg.getToken());
    }

    /** Reconcile: send {entities:{transactions:[uuid,...]}} → {missing:{transactions:[...]}}.
     *  Safety-net so a locally-recorded sale the server never received gets re-pushed. */
    public JSONObject reconcile(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/sync/reconcile", body, cfg.getToken());
    }

    /** Full "Pull Data" upload — every customer/transaction/expense row (dashboard-triggered). */
    public JSONObject importDump(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/sync/import", body, cfg.getToken());
    }

    /** "Pull Settings" upload — this phone's shareable settings, archived for review on the dashboard. */
    public JSONObject uploadSettings(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/settings/upload", body, cfg.getToken());
    }

    /** "Putuskan Provisioning": minta server MENGARSIPKAN seluruh data perangkat ini (kecuali
     *  absensi) lalu mencabut aksesnya — dipanggil TERAKHIR, setelah semua data terunggah. */
    public JSONObject retire() throws Exception {
        return post(cfg.getBaseUrl() + "/api/retire", new JSONObject(), cfg.getToken());
    }

    public JSONObject locationPing(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/location/ping", body, cfg.getToken());
    }

    /**
     * Contact-import guard: send the phone numbers about to be imported; the server replies
     * {@code {"deleted": ["<phone>", …]}} with the subset that match a customer DELETED on the
     * dashboard (and not re-added active) — so the device won't resurrect them. Echoes the exact
     * input strings back. Matching is country-code-agnostic, branch-scoped by the token.
     */
    public JSONObject customersDeletedCheck(org.json.JSONArray phones) throws Exception {
        JSONObject body = new JSONObject();
        body.put("phones", phones);
        return post(cfg.getBaseUrl() + "/api/customers/deleted-check", body, cfg.getToken());
    }

    /**
     * Buat (atau pakai-ulang) link publik 7 hari untuk kartu info satu pelanggan. Server balas
     * {@code {"url": "...", "expires_at": "..."}}. Branch-scoped by the token.
     */
    public JSONObject createCustomerShareLink(String customerUuid) throws Exception {
        JSONObject body = new JSONObject();
        body.put("customer_uuid", customerUuid);
        return post(cfg.getBaseUrl() + "/api/customers/share-link", body, cfg.getToken());
    }

    /**
     * Usulkan alokasi galon per karyawan untuk sebuah transaksi (menu transaksi / delivery). Server
     * membuat permintaan PENDING dan mengirim link persetujuan ke email laporan; alokasi baru berlaku
     * setelah disetujui. Balas {@code {"ok":true,"message":"...","emailed":true}} atau 422 dengan
     * {@code {"ok":false,"message":"..."}}. Branch-scoped by the token.
     */
    public JSONObject proposeAllocation(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/allocation-requests", body, cfg.getToken());
    }

    /**
     * Ajukan "detail bermasalah pelanggan SUDAH DIPERBAIKI" (+catatan). Server membuat permintaan
     * PENDING dan mengirim link persetujuan ke email laporan; masalah baru ditandai selesai
     * (issue_resolved_at) setelah owner menyetujui, lalu tersinkron balik. Balas
     * {@code {"ok":true,"emailed":true,"message":"..."}} atau 422. Branch-scoped by the token.
     */
    public JSONObject proposeIssueResolve(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/issue-resolve-requests", body, cfg.getToken());
    }

    /**
     * Ajukan VOID sebuah transaksi antrian delivery (+alasan wajib). Server membuat permintaan
     * PENDING dan mengirim link persetujuan ke email izin void; transaksi baru dibatalkan
     * (soft-delete + pasangan KEMBALI) setelah super admin menyetujui, lalu tombstone tersinkron
     * balik. Balas {@code {"ok":true,"emailed":true,"message":"..."}} atau 422. Branch-scoped.
     */
    public JSONObject proposeVoid(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/void-requests", body, cfg.getToken());
    }

    /**
     * EDIT sebuah transaksi antrian delivery — tambah/hapus baris produk, jumlah, harga, ongkir,
     * galon kembali AKTUAL. Server memutuskan jalurnya: order MASIH di antrian (PENDING/TERTUNDA)
     * → diterapkan LANGSUNG + email LAPORAN (balas {@code {"ok":true,"applied":true,"message":...}});
     * selain itu → tetap alur token izin lama, alasan jadi wajib (balas
     * {@code {"ok":true,"applied":false,"emailed":true,"message":...}}). Hasil akhir Rp 0 selalu
     * ditolak (422) — pakai Void. Branch-scoped.
     */
    public JSONObject proposeTrxEdit(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/edit-requests", body, cfg.getToken());
    }

    /**
     * "Ambil Alih": pindahkan rute satu order delivery dari perangkat lain ke perangkat INI.
     * Body: {@code {transaction_uuid, expected_device_uuid}} — {@code expected_device_uuid} adalah
     * pemilik order MENURUT layar saat tombol ditekan; server menolak (409) bila di sana sudah
     * berpindah, sehingga dua kurir yang menekan bersamaan tak sama-sama merasa menang.
     * Balas {@code {"ok":true,"message":"…"}} atau 422/409. Branch-scoped by the token.
     */
    public JSONObject claimDelivery(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/delivery/claim", body, cfg.getToken());
    }

    /**
     * "Kirim ke Perangkat Lain": pindahkan rute satu order dari antrian perangkat INI ke perangkat
     * lain yang dipilih. Body: {@code {transaction_uuid, target_device_uuid}}. Server menolak (409)
     * bila order sudah tak lagi di antrian perangkat ini (mis. sudah diambil alih kurir lain
     * duluan). Balas {@code {"ok":true,"message":"…"}} atau 422/409. Branch-scoped by the token.
     */
    public JSONObject routeDelivery(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/delivery/route", body, cfg.getToken());
    }

    /**
     * "Lepas": lepaskan satu order dari antrian perangkat INI menjadi "Pesanan Terbuka" — bisa
     * diklaim perangkat mana pun via {@link #claimDelivery}. Body: {@code {transaction_uuid}}.
     * Server menolak (422) bila order bukan milik antrian perangkat ini, sudah selesai/dibatalkan,
     * sedang dijalankan, atau sudah terbuka. Balas {@code {"ok":true,"message":"…"}} atau 422.
     * Branch-scoped by the token.
     */
    public JSONObject openDispatch(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/delivery/open", body, cfg.getToken());
    }

    /**
     * Paket PANTUN follow-up (sebagian korpus, bukan 10.000) untuk dipakai LURING oleh HP.
     * {@code have} = cap versi yang sedang dipegang perangkat; bila sama, server menjawab
     * {@code {"version":…,"unchanged":true}} TANPA isi sehingga pemeriksaan rutin nyaris gratis.
     * Balas {@code {version, count, items[]}} saat ada versi baru.
     */
    public JSONObject pantunPack(String have) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/pantun/pack")
                .newBuilder()
                .addQueryParameter("have", have != null ? have : "")
                .build();
        return get(built.toString(), cfg.getToken());
    }

    /**
     * "Jadwalkan Ulang" (Tunda): pindahkan satu order dari antrian perangkat INI ke TERTUNDA dengan
     * jadwal lanjut otomatis. Body: {@code {transaction_uuid, resume_at}} — resume_at wall-clock
     * lokal "yyyy-MM-dd HH:mm:ss". Server menolak (422) bila order bukan milik antrian perangkat
     * ini, bukan PENDING (sudah tertunda/selesai/dibatalkan), atau sedang dijalankan. Order langsung
     * hilang dari antrian aktif (server & HP, keduanya memfilter PENDING) begitu berhasil; tanggal
     * transaksinya ikut pindah ke jadwal itu. Balas {@code {"ok":true,"message":"…"}} atau 422.
     * Branch-scoped by the token.
     */
    public JSONObject postponeDelivery(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/delivery/postpone", body, cfg.getToken());
    }

    /**
     * "Jadikan Prioritas": tandai ⚡ prioritas order yang SUDAH ada di antrian (dibuat di web ATAU di
     * HP) — satu-satunya jalan RESMI HP menyetel delivery_priority_at/reason/by pada baris yang
     * sudah tersimpan (push sinkron biasa sengaja membuang ketiga kolom itu). Body:
     * {@code {transaction_uuid, reason?, requester_name?}}. Server menolak (422) bila order sudah
     * bukan PENDING/TERTUNDA. Balas {@code {"ok":true,...}} atau 404/422. Branch-scoped by the token.
     */
    public JSONObject markPriority(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/delivery/priority", body, cfg.getToken());
    }

    /**
     * "Peta Antrian Delivery": persebaran order aktif SE-CABANG (pin per order, warna per perangkat
     * penanggung jawab efektif) + roster perangkat delivery + posisi terakhir tiap perangkat.
     * Balas {@code {"devices":[...],"queue":[...],"positions":[...]}}. Branch-scoped by the token.
     */
    public JSONObject deliveryMap() throws Exception {
        return get(cfg.getBaseUrl() + "/api/delivery/map", cfg.getToken());
    }

    /**
     * Transaksi Baru: nama produk pembelian TERAKHIR (kedip "↩ Terakhir dibeli") + jumlah pengiriman
     * per lokasi (badge "Kirim ke") — SE-CABANG (bukan dari DB lokal HP): sync transaksi per-perangkat
     * SENGAJA terisolasi (lihat SyncEngine), jadi pelanggan yang biasa order lewat HP staf lain tak
     * akan pernah punya baris lokal di sini. Balas {@code {"last_jual_items":[...],
     * "delivery_counts":{"Nama Lokasi":N,...}}}. Branch-scoped by the token.
     */
    public JSONObject orderInsights(String customerUuid) throws Exception {
        return get(cfg.getBaseUrl() + "/api/customers/" + customerUuid + "/order-insights", cfg.getToken());
    }

    /**
     * Laporan Pelanggan Promosi: "Kirim WA Perkenalan" — server menyusun teks pesan (template +
     * daftar harga produk efektif pelanggan) dan menyetel stempel {@code promo_intro_wa_sent_at}.
     * Balas {@code {"text":"...", "link":"https://wa.me/..."}}. {@code promoDate} opsional (tanggal
     * akuisisi promo dari kohort, dipakai token {tanggal}); null → server pakai tanggal dibuatnya.
     */
    public JSONObject introWa(String customerUuid, @Nullable String promoDate) throws Exception {
        String url = cfg.getBaseUrl() + "/api/customers/" + customerUuid + "/intro-wa";
        if (promoDate != null && !promoDate.isEmpty()) {
            url = okhttp3.HttpUrl.parse(url).newBuilder()
                    .addQueryParameter("promo_date", promoDate).build().toString();
        }
        return post(url, new JSONObject(), cfg.getToken());
    }

    /**
     * "Pakai GMaps": tempel link Google Maps (panjang ATAU pendek, maps.app.goo.gl) atau teks
     * "lat, lng" → koordinat. Balas {@code {"lat":..,"lng":..}} atau 422 (bukan link Maps / tak ada
     * koordinat di link itu) / 429 (terlalu sering). Branch-scoped by the token.
     */
    public JSONObject resolveMapsLink(String url) throws Exception {
        JSONObject body = new JSONObject();
        body.put("url", url);
        return post(cfg.getBaseUrl() + "/api/maps-link/resolve", body, cfg.getToken());
    }

    public JSONObject version(String baseUrl) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(trim(baseUrl) + "/api/version")
                .header("Accept", "application/json")
                .get();
        // Send the device token so the server can identify this phone for a TARGETED (staged) rollout.
        // Without it the server sees an anonymous device → a targeted release would never reach it.
        String token = cfg.getToken();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return execute(b.build());
    }

    /** Device identity + branch + live config (e.g. location_interval_seconds). Versi APK ikut
     *  dilaporkan (?vc/&vn) di tiap heartbeat → kolom versi di dashboard selalu mutakhir, dan
     *  server membalas {@code version_blocked} bila versi ini dinonaktifkan (Kontrol Versi). */
    public JSONObject me() throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/me")
                .newBuilder()
                .addQueryParameter("vc", String.valueOf(com.crowja.damiupos.BuildConfig.VERSION_CODE))
                .addQueryParameter("vn", com.crowja.damiupos.BuildConfig.VERSION_NAME)
                .build();
        return get(built.toString(), cfg.getToken());
    }

    /** Admin broadcasts for this branch newer than {@code sinceIso}. */
    public JSONObject broadcasts(String sinceIso) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/broadcasts")
                .newBuilder()
                .addQueryParameter("since", sinceIso != null ? sinceIso : "")
                .build();
        return get(built.toString(), cfg.getToken());
    }

    /**
     * Laporkan hasil akhir sebuah perintah ke dashboard (mis. {@code wa_send} → {@code sent} /
     * {@code manual} / {@code no_whatsapp} / {@code expired}). Tanpa ini dashboard hanya bisa
     * bilang "diantrikan" dan tak pernah tahu pesannya benar-benar terkirim atau tidak.
     */
    public JSONObject ackCommand(long id, String status) throws Exception {
        JSONObject body = new JSONObject();
        body.put("id", id);
        body.put("status", status != null ? status : "");
        return post(cfg.getBaseUrl() + "/api/commands/ack", body, cfg.getToken());
    }

    /**
     * "Lihat Antrian Perangkat Lain" (read-only, on-demand — transaksi device-isolated jadi HP ini
     * tak pernah menyinkron baris milik perangkat lain secara lokal). Server balas
     * {@code {device:{uuid,name}, queue:[{id,name,phone,address,latitude,longitude,galon,total,
     * items,queued_at,is_priority,order_priority,order_priority_reason,pickup_only,dest_name,...}]}} —
     * bentuk kartu SAMA dgn Antrian Delivery web ({@code App\Support\Reports::deviceQueue}).
     */
    public JSONObject deviceQueue(String deviceUuid) throws Exception {
        return get(cfg.getBaseUrl() + "/api/devices/" + deviceUuid + "/queue", cfg.getToken());
    }

    /**
     * Checkbox "Tampilkan Antrian Perangkat Lain" di Antrian Delivery — antrian AKTIF SEMUA
     * perangkat LAIN di cabang digabung satu daftar (server sudah mengecualikan milik pemanggil
     * sendiri). Balasan {@code {queue:[{...bentuk sama dengan deviceQueue...}]}}.
     */
    public JSONObject devicesQueueAll() throws Exception {
        return get(cfg.getBaseUrl() + "/api/devices/queue-all", cfg.getToken());
    }

    /**
     * "Pencapaian Penjualan" (layar marketing/admin) — rekap penjualan per KARYAWAN se-cabang,
     * dihitung DI SERVER. Transaksi device-isolated di lapisan sync (HP hanya memegang barisnya
     * sendiri), jadi angka se-cabang mustahil dihitung dari DB lokal; ini panggilan on-demand
     * seperti {@link #devicesQueueAll()}.
     *
     * <p>Server memakai perakit yang SAMA dengan halaman web "Penjualan per Karyawan", jadi angka
     * di HP dan di dashboard tak pernah berbeda untuk preset yang sama.</p>
     *
     * @param preset  today|yesterday|week|week_prev|cutoff|last_cutoff|last_3m|custom
     *                (kosong/tak dikenal → periode potong gaji BERJALAN)
     * @param start   Y-m-d, hanya dipakai saat preset = custom
     * @param end     Y-m-d, hanya dipakai saat preset = custom
     * @param devices uuid perangkat dipisah koma; KOSONG = semua perangkat
     */
    public JSONObject salesAchievement(String preset, String start, String end, String devices) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/sales/achievement")
                .newBuilder()
                .addQueryParameter("preset", preset != null ? preset : "")
                .addQueryParameter("start", start != null ? start : "")
                .addQueryParameter("end", end != null ? end : "")
                .addQueryParameter("devices", devices != null ? devices : "")
                .build();
        return get(built.toString(), cfg.getToken());
    }

    /** Dashboard → device commands for this device newer than {@code sinceIso}. */
    public JSONObject commands(String sinceIso) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/commands")
                .newBuilder()
                .addQueryParameter("since", sinceIso != null ? sinceIso : "")
                .build();
        return get(built.toString(), cfg.getToken());
    }

    /**
     * Upload an image for a synced row. The server stores the file and returns its
     * public URL ({@code {"url": "..."}}); the caller stamps that onto the row's
     * photo_url column so it syncs to the dashboard. Branch-scoped by the token.
     *
     * @param entity server entity name (e.g. "customers", "attendance")
     * @param uuid   the row's sync_uuid
     * @param file   local image file
     */
    public JSONObject uploadMedia(String entity, String uuid, File file) throws Exception {
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("image/jpeg"));
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("entity", entity)
                .addFormDataPart("uuid", uuid)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();
        Request.Builder b = new Request.Builder()
                .url(cfg.getBaseUrl() + "/api/media/upload")
                .header("Accept", "application/json")
                .post(body);
        String token = cfg.getToken();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return execute(b.build());
    }

    /**
     * Unggah SATU foto koleksi lokasi pelanggan (maks 5 per lokasi — Edit Pelanggan). Beda dari
     * {@link #uploadMedia}: nama berkas server disisipi timestamp (banyak foto per lokasi, bukan
     * satu slot tetap), jadi balasannya HARUS langsung disisipkan ke {@code Location.photos} oleh
     * pemanggil — tak ada kolom photo_url tunggal yang otomatis membawanya seperti foto rumah.
     */
    public JSONObject uploadLocationPhoto(String customerUuid, String locationId, File file) throws Exception {
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("image/jpeg"));
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("customer_uuid", customerUuid)
                .addFormDataPart("location_id", locationId)
                .addFormDataPart("file", file.getName(), fileBody)
                .build();
        Request.Builder b = new Request.Builder()
                .url(cfg.getBaseUrl() + "/api/media/upload-location-photo")
                .header("Accept", "application/json")
                .post(body);
        String token = cfg.getToken();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return execute(b.build());
    }

    /**
     * Kampanye yang BOLEH ikut struk pelanggan ini — jawaban OTORITATIF server (jadwal, aturan
     * berhenti-melampir, kelayakan, urutan). Dipakai ReceiptActivity saat online supaya cermin
     * lokal di HP tak bisa menyimpang dari server; cermin itu tinggal jaring pengaman offline.
     * Delivery/token dibuat SERVER di sini — HP tak perlu membuatnya sendiri.
     *
     * @param trxUuid transaksi yang struknya disusun (boleh null) — menentukan tanggal penilaian
     *                jadwal untuk order TERTUNDA.
     */
    public JSONObject campaignAttachments(String customerUuid, String trxUuid) throws Exception {
        String url = cfg.getBaseUrl() + "/api/customers/" + customerUuid + "/campaign-attachments";
        if (trxUuid != null && !trxUuid.isEmpty()) {
            url += "?transaction_uuid=" + android.net.Uri.encode(trxUuid);
        }
        return get(url, cfg.getToken());
    }

    private JSONObject get(String url, String token) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return execute(b.build());
    }

    private JSONObject post(String url, JSONObject body, @Nullable String token) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return execute(b.build());
    }

    private JSONObject execute(Request req) throws Exception {
        try (Response r = client.newCall(req).execute()) {
            String s = r.body() != null ? r.body().string() : "{}";
            if (!r.isSuccessful()) throw new SyncException(r.code(), s);
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        }
    }

    private static String trim(String url) {
        if (url == null) return "";
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }
}
