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

    /** Run config + version + broadcasts over REST. Call off the main thread. */
    public static void tick(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled()) return;
        SyncApi api = new SyncApi(cfg);
        refreshConfig(ctx, cfg, api);
        checkVersion(ctx, cfg, api);
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

    /** /api/version → store latest + notify if newer, then pre-download the APK in the
     *  background so the in-app prompt can install instantly (UI prompt is separate). */
    private static void checkVersion(Context ctx, SyncSettings cfg, SyncApi api) {
        try {
            JSONObject r = api.version(cfg.getBaseUrl());
            if (r.optBoolean("available", false)) {
                OnlineNotifier.handleVersion(ctx, cfg, r);
            }
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
                OnlineNotifier.postNotif(ctx,
                        o.optString("title", "DAMIU POS"),
                        o.optString("body", ""),
                        7861 + (i % 40));   // distinct ids; avoids the version notif (7842)
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
                OnlineNotifier.postNotif(ctx, title, body, 7871);
                break;
            }
            case "locate":
                // Push a fresh GPS fix if a shift is currently tracking location.
                LocationService.reconfigure(ctx);
                break;
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
