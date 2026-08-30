package com.crowja.damiupos.sync;

import android.content.Context;

import com.crowja.damiupos.LocationService;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The online "tick" over plain REST — no MQTT. Refreshes live config (location
 * interval), checks for an app update, and pulls admin broadcasts. Runs from the
 * background {@link SyncWorker} (periodic + on every "sync now") so opening the app
 * or the 15-min worker keeps everything current without a persistent connection.
 *
 * <p>Each step is best-effort and isolated: a failure in one never blocks the rest.
 */
public final class OnlineTasks {

    private OnlineTasks() {}

    private static final long CONFIG_CHECK_INTERVAL_MS  = 15 * 60 * 1000L;   // /me (interval lokasi) tiap 15 mnt
    private static final long VERSION_CHECK_INTERVAL_MS  = 60 * 60 * 1000L;  // /version tiap 1 jam

    /** Perintah wa_send lebih tua dari ini dibuang (HP mati seharian → jangan kirim pesan basi). */
    private static final long WA_SEND_TTL_MS = 15 * 60 * 1000L;

    /** Maksimal pesan WA yang dikirim dalam satu tick (sisanya menyusul ~60 dtk kemudian). */
    private static final int MAX_WA_SEND_PER_TICK = 2;

    /** Dua pemicu tick berjalan bebas (poller LocationService ~60 dtk & SyncWorker "sync now").
     *  Tanpa penjaga ini keduanya bisa menarik daftar perintah yang SAMA sebelum kursor tersimpan →
     *  satu perintah dijalankan dua kali (pesan WA dobel ke pelanggan). */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Run config + version + broadcasts over REST. Call off the main thread. */
    public static void tick(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled()) return;
        if (!RUNNING.compareAndSet(false, true)) return;   // tick lain masih jalan → lewati
        try {
            tickLocked(ctx, cfg);
        } finally {
            RUNNING.set(false);
        }
    }

    private static void tickLocked(Context ctx, SyncSettings cfg) {
        SyncApi api = new SyncApi(cfg);
        long now = System.currentTimeMillis();
        // /me & /version tak perlu tiap tick (~60 dtk) — pull sudah membawa data live + heartbeat
        // last_seen. Throttle keduanya untuk hemat radio/CPU pada HP 24/7. Broadcast & command
        // (pesan/perintah admin) tetap tiap tick agar tiba segera.
        if (now - cfg.getConfigCheckAt() > CONFIG_CHECK_INTERVAL_MS) {
            refreshConfig(ctx, cfg, api);
            cfg.setConfigCheckAt(now);
        }
        if (now - cfg.getVersionCheckAt() > VERSION_CHECK_INTERVAL_MS) {
            checkVersion(ctx, cfg, api);
            cfg.setVersionCheckAt(now);
        }
        fetchBroadcasts(ctx, cfg, api);
        fetchCommands(ctx, cfg, api);
    }

    /** /api/me → apply live config (location reporting interval) + status Kontrol Versi. */
    private static void refreshConfig(Context ctx, SyncSettings cfg, SyncApi api) {
        try {
            JSONObject r = api.me();
            if (r.has("location_interval_seconds")) {
                int sec = r.optInt("location_interval_seconds", cfg.getLocationIntervalSeconds());
                if (sec > 0 && sec != cfg.getLocationIntervalSeconds()) {
                    cfg.setLocationIntervalSeconds(sec);
                    LocationService.reconfigure(ctx);   // apply now if a shift is tracking
                }
            }
            // Kontrol Versi: versi APK ini dinonaktifkan dari dashboard → simpan flag; popup
            // ditampilkan VersionUpdater.maybePromptBlocked saat aplikasi dibuka/di-resume.
            boolean blocked = r.optBoolean("version_blocked", false);
            cfg.setVersionBlocked(blocked, com.crowja.damiupos.BuildConfig.VERSION_CODE,
                    r.optString("blocked_note", ""));
            // Roster perangkat cabang → cache untuk picker "Perangkat yang ditugaskan" (marketing/SPV).
            JSONArray devices = r.optJSONArray("devices");
            if (devices != null) cfg.setDeviceRoster(devices.toString());

            // Muatan max perangkat ini (Strategi Pengiriman) — diatur admin di dashboard.
            if (r.has("max_load")) cfg.setMaxLoad(r.optInt("max_load", 0));
            // Kode prefix ID transaksi struk perangkat ini (App\Support\ReceiptNumber di web) —
            // dipakai TransactionDao.insert() menyusun receipt_no baris JUAL baru, offline.
            if (r.has("receipt_code")) cfg.setReceiptCode(r.optString("receipt_code", ""));
            // Slug cabang (Kelola Cabang) → link statis airfrez.com/{slug}-qris di struk WA QRIS.
            // Dari /me (bukan hanya enroll) supaya terisi walau diatur admin SETELAH HP terdaftar.
            JSONObject branchObj = r.optJSONObject("branch");
            if (branchObj != null) cfg.setBranchSlug(branchObj.optString("slug", ""));
            // Wilayah penugasan → cache pusat cabang + sektor (default perangkat + filter wilayah).
            JSONObject bc = r.optJSONObject("branch_center");
            cfg.setBranchCenter(bc != null ? bc.toString() : "");
            JSONArray qz = r.optJSONArray("wilayah_zones");
            cfg.setWilayahZones(qz != null ? qz.toString() : "");
            // "Petugas WA Perkenalan": perangkat ini bertugas menyapa pelanggan promosi baru →
            // badge + notifikasi kedatangan, dibatasi index sektor di intro_wa_zones.
            cfg.setIntroWaDevice(r.optBoolean("is_intro_wa", false));
            JSONArray iwz = r.optJSONArray("intro_wa_zones");
            cfg.setIntroWaZones(iwz != null ? iwz.toString() : "");
            // Paket PANTUN follow-up: dicek bareng /me (tiap 15 menit) tapi hanya benar-benar
            // diunduh saat versinya berubah — praktis sekali saja lalu diam. Sengaja TIDAK lewat
            // pipa sinkron: korpusnya 10.000 baris identik untuk semua cabang (lihat docblock
            // migrasi create_pantun_table). Gagal unduh = diabaikan; pantun cuma opsi tambahan.
            refreshPantunPack(ctx, api);

            if (blocked && System.currentTimeMillis() >= cfg.getSnoozeUntil()
                    && !VersionUpdater.updateNeeded(ctx, cfg)) {
                // Pembaruan-hash punya notifnya sendiri (7842) — ini hanya untuk kasus blokir murni.
                OnlineNotifier.postNotif(ctx, "Versi Aplikasi Dinonaktifkan",
                        "Versi aplikasi ini dinonaktifkan admin. Buka aplikasi lalu lakukan update.", 7845);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * /api/pantun/pack → simpan paket pantun LOKAL supaya pesan follow-up bisa dirakit tanpa
     * jaringan. Mengirim {@code ?have=<versi yang dipegang>}: kalau server menjawab
     * {@code unchanged}, tak ada isi yang ikut terkirim — jadi pemeriksaan rutin ini cuma
     * beberapa puluh byte, dan unduhan penuh (±25 KB ter-gzip) hanya terjadi saat korpus berubah.
     */
    private static void refreshPantunPack(Context ctx, SyncApi api) {
        try {
            com.crowja.damiupos.db.SettingsDao sdao = new com.crowja.damiupos.db.SettingsDao(
                    com.crowja.damiupos.db.DatabaseHelper.getInstance(ctx));
            String have = sdao.getPantunPackVersion();
            JSONObject r = api.pantunPack(have);
            if (r == null || r.optBoolean("unchanged", false)) return;
            JSONArray items = r.optJSONArray("items");
            if (items == null || items.length() == 0) return;
            sdao.setPantunPack(r.toString(), r.optString("version", ""));
        } catch (Throwable ignored) {}
    }

    /** /api/version → store the published APK + notify if our hash differs (mandatory), then
     *  pre-download it in the background so "Update Sekarang" installs instantly. */
    private static void checkVersion(Context ctx, SyncSettings cfg, SyncApi api) {
        try {
            JSONObject r = api.version(cfg.getBaseUrl());
            OnlineNotifier.handleUpdate(ctx, cfg, r);   // handles available + cleared cases
        } catch (Throwable ignored) {}
        // Auto-download the published update (no-op once it's already downloaded).
        try { VersionUpdater.autoDownloadIfNeeded(ctx); } catch (Throwable ignored) {}
    }

    /** /api/broadcasts?since=cursor → notify each new admin message, advance cursor. */
    private static void fetchBroadcasts(Context ctx, SyncSettings cfg, SyncApi api) {
        try {
            JSONObject r = api.broadcasts(cfg.getBroadcastCursor());
            JSONArray arr = r.optJSONArray("broadcasts");
            if (arr == null || arr.length() == 0) return;
            String maxAt = cfg.getBroadcastCursor();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                OnlineNotifier.deliverAdminMessage(ctx,
                        o.optString("title", "DAMIU POS"),
                        o.optString("body", ""),
                        7861 + (i % 40));   // notif + popup in-app; distinct ids (avoids version 7842)
                String at = o.optString("created_at", "");
                if (at.compareTo(maxAt) > 0) maxAt = at;
            }
            if (!maxAt.equals(cfg.getBroadcastCursor())) cfg.setBroadcastCursor(maxAt);
        } catch (Throwable ignored) {}
    }

    /** /api/commands?since=cursor → run each queued dashboard command, advance cursor. */
    private static void fetchCommands(Context ctx, SyncSettings cfg, SyncApi api) {
        try {
            JSONObject r = api.commands(cfg.getCommandCursor());
            JSONArray arr = r.optJSONArray("commands");
            if (arr == null || arr.length() == 0) return;
            String serverTime = r.optString("server_time", "");
            int waSent = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String cmd = o.optString("cmd", "");
                // wa_send/wa_check memblokir sampai WhatsApp memberi bukti (bisa puluhan detik) dan
                // HARUS berurutan — satu WhatsApp di depan pada satu waktu. Batasi per tick agar
                // poller (heartbeat + GPS) tak tertahan lama; sisanya diambil tick berikutnya karena
                // kursor sengaja TIDAK dimajukan melewatinya.
                boolean blockingWa = "wa_send".equals(cmd) || "wa_check".equals(cmd);
                if (blockingWa && waSent >= MAX_WA_SEND_PER_TICK) break;
                String at = o.optString("created_at", "");
                // Kursor dimajukan SEBELUM menjalankan, dan disimpan per-perintah (bukan sekali di
                // akhir batch). Perintah tak-idempoten seperti wa_send mengirim pesan NYATA ke
                // pelanggan: satu pesan gagal jauh lebih murah daripada pesan dobel bila langkah
                // berikutnya melempar dan seluruh batch terulang tiap 60 detik.
                if (at.compareTo(cfg.getCommandCursor()) > 0) cfg.setCommandCursor(at);
                try {
                    runCommand(ctx, cfg, api, cmd, o.optJSONObject("payload"),
                            o.optLong("id", 0L), at, serverTime);
                } catch (Throwable ignored) {}   // satu perintah gagal tak membatalkan sisanya
                if (blockingWa) waSent++;
            }
        } catch (Throwable ignored) {}
    }

    /** Selisih milidetik dua stempel server ("Y-m-d H:i:s[.u]"); 0 bila tak terparse. Keduanya
     *  diurai dengan formatter & zona yang sama, jadi selisihnya sahih tanpa peduli zona. */
    private static long ageMs(String createdAt, String serverTime) {
        long a = parseTs(createdAt), b = parseTs(serverTime);
        return (a == 0L || b == 0L) ? 0L : b - a;
    }

    private static long parseTs(String s) {
        if (s == null || s.length() < 19) return 0L;
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            java.util.Date d = f.parse(s.substring(0, 19));
            return d != null ? d.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Execute one dashboard → device command. */
    private static void runCommand(Context ctx, SyncSettings cfg, SyncApi api, String cmd,
                                   JSONObject payload, long id, String createdAt, String serverTime) {
        if (cmd == null) return;
        switch (cmd) {
            case "sync":
                // The worker already syncs before this tick runs, so the device is
                // fresh; advancing the cursor acknowledges the request.
                break;
            case "message": {
                String title = payload != null ? payload.optString("title", "DAMIU POS") : "DAMIU POS";
                String body = payload != null ? payload.optString("body", "") : "";
                OnlineNotifier.deliverAdminMessage(ctx, title, body, 7871);   // notif + popup in-app
                break;
            }
            case "locate":
                // Push a fresh GPS fix if a shift is currently tracking location.
                LocationService.reconfigure(ctx);
                break;
            case "wa_send": {
                // Gateway WhatsApp (Opsi A): buka chat wa.me nomor tujuan dengan pesan terisi lalu
                // tekan "Kirim" otomatis via Aksesibilitas. WhatsApp dibawa ke foreground sesaat.
                String phone = payload != null ? payload.optString("phone", "") : "";
                String text = payload != null ? payload.optString("text", "") : "";
                // Pesan basi jangan dikirim: HP mati/offline seharian lalu menyala malam hari tak
                // boleh tiba-tiba mengirim "galon sudah kami antar ya" dari pagi tadi.
                long age = ageMs(createdAt, serverTime);
                String status = age > WA_SEND_TTL_MS
                        ? "expired_ttl"
                        : com.crowja.damiupos.wa.WaGateway.send(ctx, phone, text);
                ackCommand(api, id, status);
                break;
            }
            case "wa_check": {
                // Cek nomor pelanggan yang didaftarkan/diedit lewat WEB (di HP tak ada yang bisa
                // mengeceknya sendiri). Membuka chat wa.me lalu MEMBACA layarnya — tidak mengirim
                // apa pun. Server yang menandai pelanggan bermasalah saat menerima ack-nya.
                String phone = payload != null ? payload.optString("phone", "") : "";
                long age = ageMs(createdAt, serverTime);
                String status = age > WA_SEND_TTL_MS
                        ? "expired_ttl"
                        : com.crowja.damiupos.wa.WaGateway.check(ctx, phone);
                ackCommand(api, id, status);
                break;
            }
            case "pull_data": {
                // Dashboard "Pull Data": full-upload customers/transactions/expenses for import.
                SyncEngine.Result r = new SyncEngine(ctx).fullExport();
                OnlineNotifier.postNotif(ctx, "Tarik Data",
                        r.ok ? (r.pushed + " data dikirim ke server" + (r.pulled > 0 ? " · " + r.pulled + " bentrok untuk ditinjau" : ""))
                             : ("Gagal mengirim data: " + (r.error != null ? r.error : "tidak diketahui")),
                        7873);
                break;
            }
            case "pull_settings": {
                // Dashboard "Tarik Pengaturan": upload this phone's shareable settings for review.
                SyncEngine.Result r = new SyncEngine(ctx).exportSettings();
                OnlineNotifier.postNotif(ctx, "Tarik Pengaturan",
                        r.ok ? (r.pushed + " pengaturan dikirim ke server untuk ditinjau di dashboard")
                             : ("Gagal mengirim pengaturan: " + (r.error != null ? r.error : "tidak diketahui")),
                        7874);
                break;
            }
            case "unbind":
                cfg.clear();                      // drop token + disable sync
                SyncScheduler.cancelAll(ctx);     // stop periodic worker
                ServiceRestartReceiver.cancel(ctx);   // stop watchdog resurrection alarm
                LocationService.stop(ctx);
                OnlineNotifier.postNotif(ctx, "Akses dicabut",
                        "Perangkat dilepas oleh admin. Daftar ulang (provisioning) untuk terhubung lagi.",
                        7872);
                break;
            default:
                break;
        }
    }

    /** Laporkan hasil sebuah perintah balik ke dashboard (best-effort; kegagalan lapor tak
     *  mengulang perintahnya — kursor sudah maju agar pesan tak terkirim dobel). */
    private static void ackCommand(SyncApi api, long id, String status) {
        if (id <= 0) return;
        try { api.ackCommand(id, status); } catch (Throwable ignored) {}
    }
}
