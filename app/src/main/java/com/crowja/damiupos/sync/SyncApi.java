package com.crowja.damiupos.sync;

import androidx.annotation.Nullable;

import com.crowja.damiupos.util.PhoneConflictPolicy;

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

        /** Pesan ramah dari body galat server — {@code {"message":"..."}} atau
         *  {@code {"error":{"message":"..."}}}; null bila body kosong / bukan JSON / tanpa pesan. */
        @Nullable
        public String serverMessage() {
            if (body == null || body.trim().isEmpty()) return null;
            try {
                JSONObject o = new JSONObject(body);
                String m = o.optString("message", "");
                if (m.isEmpty()) {
                    JSONObject err = o.optJSONObject("error");
                    if (err != null) m = err.optString("message", "");
                }
                return m.isEmpty() ? null : m;
            } catch (Exception e) {
                return null;   // mis. halaman galat HTML dari proxy/CDN
            }
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

    /** Batas total satu pertanyaan phone-check (connect+tulis+baca). Pendek: ini dijalankan saat staf
     *  menekan Simpan; lewat batas → jatuh ke cek lokal, bukan menggantung. */
    public static final int PHONE_CHECK_TIMEOUT_SEC = 6;

    /**
     * Cek-pra-simpan "nomor ini sudah dipegang pelanggan AKTIF lain?" ({@code POST /api/customers/phone-check}).
     * Body {@code {"phones":[…1..15],"exclude_uuid":"<uuid yang sedang diedit>"}}; balasan 200
     * {@code {"ok":true,"conflicts":[{phone,canonical,customer:{uuid,name,phone,role}}]}} — kosong = bebas.
     * Cabang = cabang perangkat (token). Non-2xx (422/429/401/5xx) dilempar sebagai {@link SyncException};
     * jaringan putus / timeout dilempar sebagai IOException — pemanggil menjatuhkannya ke cek lokal.
     * Panggil DI LUAR main thread. Tak memakai org.json (diurai Gson di {@link PhoneConflictPolicy}).
     */
    public PhoneConflictPolicy.ServerResult customerPhoneCheck(java.util.List<String> phones,
                                                                @Nullable String excludeUuid) throws Exception {
        Request req = new Request.Builder()
                .url(cfg.getBaseUrl() + "/api/customers/phone-check")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + cfg.getToken())
                .post(RequestBody.create(PhoneConflictPolicy.buildRequestJson(phones, excludeUuid), JSON))
                .build();
        OkHttpClient shortClient = client.newBuilder()   // berbagi pool/dispatcher dgn Http.SHARED
                .callTimeout(PHONE_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        try (Response r = shortClient.newCall(req).execute()) {
            String s = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) throw new SyncException(r.code(), s);
            return PhoneConflictPolicy.parseResponse(s, excludeUuid);
        }
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
     * "🔗 Gabungkan" (Gabung Pelanggan dari HP, cermin tombol Gabung di web): lebur beberapa
     * pelanggan jadi satu. SERVER yang mengeksekusi — semua transaksi/data dipindah ke
     * {@code survivorUuid}, salinan lain dihapus (tombstone) lalu hilang dari HP lewat pull berikutnya.
     * Body {@code {survivor_uuid, uuids:[≥2, termasuk survivor], collect_phones, actor?}}; balas
     * {@code {"ok":true,"survivor_uuid":"…","merged":N,"message":"…"}}. Ditolak → {@link SyncException}
     * berbody {@code {"ok":false,"message":"…"}} (403 tak berwenang; 422 validasi: "Umum", kurang dari
     * 2 baris, survivor tak ada di daftar, lintas cabang) — ambil pesannya via
     * {@link SyncException#serverMessage()}. Branch-scoped by the token. Panggil DI LUAR main thread.
     *
     * <p>Token perangkat hanya mengenali PERANGKAT; server menggerbang ORANG-nya lewat header
     * {@code X-Staff-Uuid} (Admin aktif cabang ini). Tanpa identitas
     * staf server SELALU menolak 403 — jadi pemanggil wajib mengisi {@code staffUuid}. Dikirim juga
     * sebagai field body {@code staff_uuid} (fallback server bila header terbuang proxy).
     *
     * @param staffUuid sync_uuid staf yang login di HP (bukan id lokal)
     * @param actor nama operator yang tampil di HP (untuk jejak audit); null/kosong = tak dikirim
     */
    public JSONObject mergeCustomers(String survivorUuid, java.util.List<String> uuids,
                                     boolean collectPhones, String staffUuid,
                                     @Nullable String actor) throws Exception {
        JSONObject body = new JSONObject();
        body.put("survivor_uuid", survivorUuid);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String u : uuids) {
            if (u != null && !u.isEmpty()) arr.put(u);
        }
        body.put("uuids", arr);
        body.put("collect_phones", collectPhones);
        if (actor != null && !actor.trim().isEmpty()) body.put("actor", actor.trim());
        if (staffUuid != null && !staffUuid.isEmpty()) body.put("staff_uuid", staffUuid);
        return post(cfg.getBaseUrl() + "/api/customers/merge", body, cfg.getToken(), staffUuid);
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
     * "Refresh RIT": server menyusun ulang SELURUH antrean perangkat ini jadi urutan antar terpendek
     * (jarak tempuh OSRM, bukan garis lurus) mulai dari {@code {lat,lng}} posisi HP sekarang, lalu
     * menulisnya sebagai delivery_seq — urutan baru tiba lewat pull berikutnya. Body lat/lng boleh
     * kosong (server memakai ping GPS terakhir / cabang). Balas {@code {ok,message,km,trips,…}}.
     */
    public JSONObject optimizeRoute(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/delivery/optimize", body, cfg.getToken());
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
    /**
     * Nomor terdaftar di WhatsApp? Server bertanya ke FREZ WA Bridge. Balas {@code {on_whatsapp:
     * true|false|null}} — null = tak bisa dipastikan (Bridge mati/belum diatur), BUKAN "aman".
     */
    public JSONObject checkWhatsApp(String phone) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/customers-check-whatsapp")
                .newBuilder().addQueryParameter("phone", phone != null ? phone : "").build();
        return get(built.toString(), cfg.getToken());
    }

    /** true/false = jawaban pasti Bridge; null = tak konklusif (offline, Bridge mati, galat). */
    public static Boolean bridgeOnWhatsApp(SyncSettings cfg, String phone) {
        try {
            if (cfg == null || !cfg.isEnrolled()) return null;
            JSONObject r = new SyncApi(cfg).checkWhatsApp(phone);
            return (r.has("on_whatsapp") && !r.isNull("on_whatsapp")) ? r.optBoolean("on_whatsapp") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Histori transaksi pelanggan LINTAS PERANGKAT (semua salinan orang yang sama), terbaru dulu.
     *  Balas {@code {total, page, has_more, transactions:[...]}}. */
    public JSONObject customerTransactions(String customerUuid, int page) throws Exception {
        return get(cfg.getBaseUrl() + "/api/customers/" + customerUuid + "/transactions?per_page=30&page="
                + Math.max(1, page), cfg.getToken());
    }

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

    /**
     * "Hitung Ongkir": jarak tempuh (OSRM, fallback garis lurus) dari titik asal cabang ke
     * (lat,lng) → tarif tangga ongkir cabang. Balas {@code {"km":..,"method":..,"rate":..,"outOfRange":..}}
     * atau 422 (titik asal cabang belum diatur) / 429 (terlalu sering). Cermin tombol "Hitung Ongkir"
     * di web ({@see App\Http\Controllers\Web\OngkirCalcController}).
     */
    public JSONObject calculateOngkir(double lat, double lng) throws Exception {
        JSONObject body = new JSONObject();
        body.put("lat", lat);
        body.put("lng", lng);
        return post(cfg.getBaseUrl() + "/api/ongkir/hitung", body, cfg.getToken());
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

    /**
     * Kartu "📊 Status Pencapaian" (Asisten Operasional, layar Selesai — Pulang guided delivery) —
     * target penjualan periode potong gaji BERJALAN &amp; lalu untuk SATU karyawan, plus galon pekan
     * ini/lalu. Dihitung DI SERVER (sama seperti {@link #salesAchievement}) supaya HP tak perlu tahu
     * aturan target sama sekali. Balas {@code {"target_enabled":bool,"current":{...},"previous":{...},
     * "week":{"galon":N},"week_prev":{"galon":N}}} — {@code current}/{@code previous} null bila target
     * belum diset untuk karyawan itu. Branch-scoped by the token.
     *
     * @param staffUuid karyawan yang capaiannya ditanyakan (staf yang sedang login di perangkat ini)
     */
    public JSONObject salesTarget(String staffUuid) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/sales/target")
                .newBuilder()
                .addQueryParameter("staff_uuid", staffUuid != null ? staffUuid : "")
                .build();
        return get(built.toString(), cfg.getToken());
    }

    /**
     * "Rekor Pengiriman": hari &amp; bulan TERBAIK tiap perangkat + capaian berjalan, se-cabang.
     * Perakitnya di server SAMA dengan kartu di halaman Delivery web (App\Support\DeliveryRecord),
     * jadi rekor yang dilihat kurir identik dengan yang dilihat owner. Tak ada parameter: rekor
     * memang selalu se-cabang dan tidak mengikuti filter perangkat mana pun.
     */
    public JSONObject deliveryRecord() throws Exception {
        return get(cfg.getBaseUrl() + "/api/delivery/record", cfg.getToken());
    }

    // ------------------------------------------------ Pengisian Day-time (/api/fill/*)
    // Semua panggilan membawa header X-Staff-Uuid: token perangkat hanya mengenali perangkat &
    // cabang, sedangkan server memeriksa peran orangnya (pengisian/admin) dari tabel staff.
    // Angka rencana dirakit SERVER (App\Support\FillPlan) — HP tidak menghitung apa pun.

    /**
     * Rencana isi galon se-cabang. {@code rev} = fingerprint rencana terakhir yang sudah dipegang
     * HP; bila tak berubah server membalas {@code {"ok":true,"unchanged":true,"rev":...}} (murah).
     */
    public JSONObject fillPlan(@Nullable String rev, String staffUuid) throws Exception {
        okhttp3.HttpUrl.Builder hb = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/fill/plan").newBuilder();
        if (rev != null && !rev.isEmpty()) hb.addQueryParameter("rev", rev);
        return get(hb.build().toString(), cfg.getToken(), staffUuid);
    }

    /**
     * Catat isi galon. {@code uuid} dibuat SEKALI per ketukan (server idempoten per uuid).
     * {@code ageSeconds} = berapa detik lalu ketukan terjadi (0..21600; ketukan tertahan di kotak
     * keluar karena offline): server mencap {@code logged_at = now() - age_seconds} dengan jam
     * SERVER sendiri, jadi selisih jam HP tak berpengaruh. 0 = ketukan baru saja terjadi (dihilangkan).
     */
    public JSONObject fillLog(String staffUuid, String uuid, String productUuid, int qty,
                              int ageSeconds) throws Exception {
        JSONObject body = new JSONObject();
        body.put("uuid", uuid);
        body.put("product_uuid", productUuid);
        body.put("qty", qty);
        if (ageSeconds > 0) body.put("age_seconds", ageSeconds);
        return post(cfg.getBaseUrl() + "/api/fill/log", body, cfg.getToken(), staffUuid);
    }

    /**
     * Hitung stok rak: {@code counts} = [{product_uuid, qty}] (nilai ABSOLUT, satu baris per produk).
     * {@code ageSeconds}: lihat {@link #fillLog} — jangkar hitung harus bercap saat rak DIHITUNG,
     * bukan saat terkirim.
     */
    public JSONObject fillCount(String staffUuid, String batchUuid, org.json.JSONArray counts,
                                int ageSeconds) throws Exception {
        JSONObject body = new JSONObject();
        body.put("uuid", batchUuid);
        body.put("counts", counts);
        if (ageSeconds > 0) body.put("age_seconds", ageSeconds);
        return post(cfg.getBaseUrl() + "/api/fill/count", body, cfg.getToken(), staffUuid);
    }

    /** Batalkan satu catatan (uuid baris, atau batch_uuid hitung stok). Jendela batal 15 menit. */
    public JSONObject fillVoid(String staffUuid, String targetUuid) throws Exception {
        return post(cfg.getBaseUrl() + "/api/fill/log/" + android.net.Uri.encode(targetUuid) + "/void",
                new JSONObject(), cfg.getToken(), staffUuid);
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

    /**
     * Log percakapan WhatsApp komplain untuk SATU order (badge 😠 Komplain) — diambil ON-DEMAND
     * (bukan lewat sinkron push/pull biasa; transactions.complained_at sendiri TETAP ikut pull
     * seperti kolom lain, lihat DatabaseHelper.COL_COMPLAINED_AT) saat pengguna membuka viewer
     * chat dari daftar transaksi. Balas {@code {transaction_uuid, complained_at, messages:[{
     * wa_message_id, direction, wa_account, sender_name, type, text, media_url, media_mimetype,
     * wa_timestamp}, ...]}}, terurut wa_timestamp. Lihat ChatLogActivity untuk pemakainya.
     */
    public JSONObject complaintLog(String transactionUuid) throws Exception {
        String url = cfg.getBaseUrl() + "/api/transactions/" + transactionUuid + "/complaint-log";
        return get(url, cfg.getToken());
    }

    /**
     * Hasil satu panggilan "💬 Chat Pesanan" — BUKAN dilempar sebagai {@link SyncException}, karena
     * pemanggil butuh status HTTP-nya persis (200 terkirim, 202 tertunda, 409 status tak diketahui,
     * 422/403/429 gagal). {@code status == 0} = tak ada respons (jaringan putus / timeout klien).
     * Galat server {@code {"ok":false,"error":{code,message},"new_key_required":true}} diurai seperti
     * {@code WaBridgeSend.parseError}.
     */
    public static class OrderChatResult {
        public final int status;
        /** Body JSON (juga saat gagal); null bila tak ada respons / bukan JSON. */
        @Nullable public final JSONObject body;
        @Nullable public final String errorCode;
        @Nullable public final String errorMessage;
        /** Server minta kunci idempoten BARU untuk kirim ulang (pengiriman lama sudah final gagal). */
        public final boolean newKeyRequired;
        /** Header Retry-After (ms) pada 429; 0 bila tak ada. */
        public final long retryAfterMs;

        private OrderChatResult(int status, @Nullable JSONObject body, @Nullable String errorCode,
                                @Nullable String errorMessage, boolean newKeyRequired, long retryAfterMs) {
            this.status = status;
            this.body = body;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.newKeyRequired = newKeyRequired;
            this.retryAfterMs = retryAfterMs;
        }

        /** 2xx DAN body JSON {@code ok:true} — body kosong / tanpa {@code ok} (mis. halaman proxy)
         *  BUKAN sukses: kirim yang dijawab begitu diulang dengan kunci SAMA, tak dianggap terkirim. */
        public boolean isOk() {
            return status >= 200 && status < 300 && body != null && body.optBoolean("ok", false);
        }

        /** Tak ada respons sama sekali (jaringan/timeout) — aman diulang dengan kunci yang SAMA. */
        public boolean isNoResponse() { return status == 0; }

        static OrderChatResult noResponse(String message) {
            return new OrderChatResult(0, null, "unreachable", message, false, 0L);
        }

        static OrderChatResult of(int status, @Nullable JSONObject body) {
            return of(status, body, 0L);
        }

        static OrderChatResult of(int status, @Nullable JSONObject body, long retryAfterMs) {
            if (status >= 200 && status < 300) {
                if (body == null) return new OrderChatResult(status, null, "bad_response", "Balasan server tidak terbaca.", false, 0L);
                if (!body.optBoolean("ok", false)) {
                    return new OrderChatResult(status, body, "bad_response", "Balasan server tidak lengkap.", false, 0L);
                }
                return new OrderChatResult(status, body, null, null, false, 0L);
            }
            JSONObject err = body != null ? body.optJSONObject("error") : null;
            String code = err != null ? err.optString("code", "") : "";
            if (code.isEmpty()) code = status == 429 ? "rate_limited" : status == 401 ? "unauthenticated" : "http_" + status;
            String msg = err != null ? err.optString("message", "") : "";
            if (msg.isEmpty() && body != null) msg = body.optString("message", "");
            // 401 = token perangkat dicabut ("Unauthenticated." bawaan Laravel berbahasa Inggris).
            if (status == 401) msg = "Perangkat tidak lagi terotorisasi — hubungkan ulang di Pengaturan.";
            if (msg.isEmpty()) msg = status == 429
                    ? "Terlalu sering. Coba lagi sebentar."
                    : "Server menjawab HTTP " + status + ".";
            boolean newKey = (body != null && body.optBoolean("new_key_required", false))
                    || (err != null && err.optBoolean("new_key_required", false));
            return new OrderChatResult(status, body, code, msg, newKey, retryAfterMs);
        }
    }

    /**
     * "💬 Chat Pesanan" — potongan percakapan WA order agen AI (§4 spec chat-session). Tanpa
     * {@code cursor}/{@code rev} → respons PENUH ({@code reset:true}); dengan keduanya → hanya baris
     * yang berubah sejak {@code cursor} ({@code reset:false}). Balas {@code {ok, session:{…},
     * messages:[…], reset, cursor, has_more, truncated}}; 404 {@code no_session} bila order ini tak
     * punya sesi chat aktif.
     *
     * @param rev {@code session.window_rev} terakhir yang dipegang klien; ≤ 0 = belum tahu
     */
    public OrderChatResult orderChat(String trxUuid, @Nullable String cursor, int rev) {
        okhttp3.HttpUrl base = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/transactions/"
                + android.net.Uri.encode(trxUuid) + "/chat");
        if (base == null) return OrderChatResult.noResponse("Alamat server tidak valid.");
        okhttp3.HttpUrl.Builder ub = base.newBuilder();
        if (cursor != null && !cursor.isEmpty()) ub.addQueryParameter("cursor", cursor);
        if (rev > 0) ub.addQueryParameter("rev", String.valueOf(rev));
        Request.Builder b = new Request.Builder()
                .url(ub.build())
                .header("Accept", "application/json")
                .get();
        return executeOrderChat(b);
    }

    /**
     * Kirim balasan staf ke pelanggan lewat FREZ WA Bridge pada akun + chat SAMA dengan asal order.
     * Body: {@code {text, client_key, staff_name, media_base64?, mimetype?, file_name?}}. Status:
     * 200 terkirim ({@code message}), 202 tertunda (ulangi dengan client_key SAMA), 409
     * {@code send_unknown}, 422/403/429 gagal ({@code new_key_required}).
     */
    public OrderChatResult orderChatSend(String trxUuid, JSONObject body) {
        return postOrderChat("/api/transactions/" + android.net.Uri.encode(trxUuid) + "/chat/send", body);
    }

    /** "Ambil alih dari AI" ({@code paused=true}) / "Serahkan ke AI" ({@code false}). Balas
     *  {@code {ok, agent_paused_until, agent_paused_by}}. */
    public OrderChatResult orderChatAgent(String trxUuid, boolean paused) {
        JSONObject body = new JSONObject();
        try {
            body.put("paused", paused);
        } catch (Exception ignored) {
            // JSONObject.put(String, boolean) tak pernah gagal untuk kunci non-null
        }
        return postOrderChat("/api/transactions/" + android.net.Uri.encode(trxUuid) + "/chat/agent", body);
    }

    private OrderChatResult postOrderChat(String path, JSONObject body) {
        okhttp3.HttpUrl url = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + path);
        if (url == null) return OrderChatResult.noResponse("Alamat server tidak valid.");
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        return executeOrderChat(b);
    }

    private OrderChatResult executeOrderChat(Request.Builder b) {
        String token = cfg.getToken();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        try (Response r = client.newCall(b.build()).execute()) {
            String s = r.body() != null ? r.body().string() : "";
            JSONObject json = null;
            try {
                json = s.isEmpty() ? new JSONObject() : new JSONObject(s);
            } catch (Exception ignored) {
                // bukan JSON (mis. halaman galat proxy/CDN) — status HTTP tetap dilaporkan
            }
            long retryAfterMs = 0L;
            String ra = r.header("Retry-After");
            if (ra != null) {
                try {
                    retryAfterMs = Math.max(0L, Long.parseLong(ra.trim())) * 1000L;
                } catch (NumberFormatException ignored) {
                    // bentuk tanggal HTTP — abaikan, pemanggil memakai jeda bawaan
                }
            }
            return OrderChatResult.of(r.code(), json, retryAfterMs);
        } catch (java.io.IOException e) {
            return OrderChatResult.noResponse("Server tidak terjangkau.");
        }
    }

    /** Status FREZ WA Bridge server ({@code ok}+{@code configured}) — dipakai sebagai syarat auto-kirim WA. */
    public JSONObject waBridgeStatus() throws Exception {
        return get(cfg.getBaseUrl() + "/api/wa-bridge/status", cfg.getToken());
    }

    /**
     * Kirim WA ke pelanggan lewat FREZ WA Bridge server — akun pengirim dipilih SERVER dengan urutan
     * prioritas yang sama dengan auto-send (2 arah → akun perangkat → akun pengirim cabang → cadangan
     * {@code fallback_priority}). Body: {@code {to, text, type?, kind?, customer_uuid?,
     * transaction_uuid?, idempotency_key?, media_base64?, mimetype?, file_name?}}. Balas
     * {@code {"ok":true,"account":"RAFI",...}}; gagal → 422 {@code {"ok":false,"error":{code,message}}}
     * (dilempar sebagai {@link SyncException}). Dipakai {@link com.crowja.damiupos.wa.WaBridgeSend}.
     */
    public JSONObject waBridgeSend(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/wa-bridge/send", body, cfg.getToken());
    }

    private JSONObject get(String url, String token) throws Exception {
        return get(url, token, null);
    }

    /** @param staffUuid bila terisi dikirim sebagai header {@code X-Staff-Uuid} (identitas ORANG). */
    private JSONObject get(String url, String token, @Nullable String staffUuid) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        if (staffUuid != null && !staffUuid.isEmpty()) b.header("X-Staff-Uuid", staffUuid);
        return execute(b.build());
    }

    private JSONObject post(String url, JSONObject body, @Nullable String token) throws Exception {
        return post(url, body, token, null);
    }

    private JSONObject post(String url, JSONObject body, @Nullable String token,
                            @Nullable String staffUuid) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON));
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        if (staffUuid != null && !staffUuid.isEmpty()) b.header("X-Staff-Uuid", staffUuid);
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
