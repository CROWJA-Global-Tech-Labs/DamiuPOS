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

    /** Run config + version + broadcasts over REST. Call off the main thread. */
    public static void tick(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled()) return;
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

    /** /api/me → apply live config (location reporting interval). */
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
            String maxAt = cfg.getCommandCursor();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                runCommand(ctx, cfg, o.optString("cmd", ""), o.optJSONObject("payload"));
                String at = o.optString("created_at", "");
                if (at.compareTo(maxAt) > 0) maxAt = at;
            }
            if (!maxAt.equals(cfg.getCommandCursor())) cfg.setCommandCursor(maxAt);
        } catch (Throwable ignored) {}
    }

    /** Execute one dashboard → device command. */
    private static void runCommand(Context ctx, SyncSettings cfg, String cmd, JSONObject payload) {
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
                LocationService.stop(ctx);
                OnlineNotifier.postNotif(ctx, "Akses dicabut",
                        "Perangkat dilepas oleh admin. Daftar ulang (provisioning) untuk terhubung lagi.",
                        7872);
                break;
            default:
                break;
        }
    }
}
