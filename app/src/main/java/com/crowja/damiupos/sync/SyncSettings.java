package com.crowja.damiupos.sync;

import com.crowja.damiupos.db.SettingsDao;

/**
 * Persisted online-sync configuration (server URL, device token, branch, and the
 * per-entity pull cursors). Backed by the existing key-value {@code settings} table.
 */
public class SyncSettings {

    /** Production server, pre-filled so staff only need to type the provisioning code.
     *  Canonical domain is dashboard.airfrez.com — the old damiupos.hantechno.my.id host
     *  is retired (returns 410), so a fresh provision must default to the new domain. */
    public static final String DEFAULT_BASE_URL = "https://dashboard.airfrez.com";

    /** Customer-facing delivery-tracking base — a DEDICATED subdomain, separate from the
     *  API/dashboard host. Mirrors the backend config/track.php (TRACK_BASE_URL); the
     *  canonical link is {@code https://order.airfrez.com/tracking/{token}}. */
    public static final String DEFAULT_TRACK_BASE_URL = "https://order.airfrez.com";

    private static final String K_BASE_URL    = "sync_base_url";
    private static final String K_TRACK_BASE  = "sync_track_base_url"; // override link lacak (opsional)
    private static final String K_TOKEN       = "sync_token";
    private static final String K_DEVICE_UUID = "sync_device_uuid";
    private static final String K_BRANCH_CODE = "sync_branch_code";
    private static final String K_BRANCH_UUID = "sync_branch_uuid";
    private static final String K_BRANCH_NAME = "sync_branch_name";
    private static final String K_ENABLED     = "sync_enabled";
    private static final String K_LAST_AT     = "sync_last_at";
    private static final String K_CURSOR      = "sync_cursor_"; // + entity

    // Mandatory update: the server-published APK (url + its SHA-256) the device must converge to,
    // plus a snooze deadline ("Tunda 1 jam") and a cache of this device's own installed-APK hash.
    private static final String K_UPD_URL = "sync_upd_url";
    private static final String K_UPD_SHA = "sync_upd_sha";
    private static final String K_UPD_NAME = "sync_upd_name";
    private static final String K_UPD_SNOOZE = "sync_upd_snooze_until";   // epoch millis
    private static final String K_UPD_MYSHA = "sync_upd_my_sha";          // cached own-APK sha
    private static final String K_UPD_MYSHA_AT = "sync_upd_my_sha_at";    // PackageInfo.lastUpdateTime

    // Last admin broadcast we've shown (created_at) — REST poll cursor.
    private static final String K_BROADCAST_AT = "sync_broadcast_at";
    // Last device command we've run (created_at) — REST poll cursor.
    private static final String K_COMMAND_AT = "sync_command_at";
    // Throttle: /api/version & /api/me tak perlu tiap tick (60 dtk). Simpan epoch cek terakhir.
    private static final String K_VERSION_CHECK_AT = "sync_version_check_at";
    private static final String K_CONFIG_CHECK_AT  = "sync_config_check_at";

    // Track this device's staff location while clocked in (default on when enrolled).
    private static final String K_LOC_ENABLED = "sync_loc_enabled";
    // How often to report location while clocked in, in seconds (admin-configurable; default 10 min).
    private static final String K_LOC_INTERVAL = "sync_loc_interval";

    // Branch-authoritative gallon stock from the server (shown verbatim so the device's Stok Galon
    // == dashboard, regardless of which transactions this phone holds locally).
    private static final String K_STOK_HAVE     = "sync_stok_have";   // "1" once received at least once
    private static final String K_STOK_MASUK    = "sync_stok_masuk";
    private static final String K_STOK_KELUAR   = "sync_stok_keluar";
    private static final String K_STOK_KEMBALI  = "sync_stok_kembali";
    private static final String K_STOK_TERSEDIA = "sync_stok_tersedia";

    private final SettingsDao settings;

    public SyncSettings(SettingsDao settings) {
        this.settings = settings;
    }

    public String getBaseUrl()      { return trimSlash(settings.get(K_BASE_URL, "")); }
    public void setBaseUrl(String v){ settings.set(K_BASE_URL, trimSlash(v)); }

    /** Base link lacak pelanggan ({@link #DEFAULT_TRACK_BASE_URL}); bisa di-override
     *  (mis. dari app_settings dashboard) kalau domain tracking berubah. */
    public String getTrackBaseUrl() {
        String v = trimSlash(settings.get(K_TRACK_BASE, ""));
        return v.isEmpty() ? DEFAULT_TRACK_BASE_URL : v;
    }
    public void setTrackBaseUrl(String v) { settings.set(K_TRACK_BASE, v != null ? trimSlash(v) : ""); }

    public String getToken()        { return settings.get(K_TOKEN, ""); }
    public void setToken(String v)  { settings.set(K_TOKEN, v != null ? v : ""); }

    public String getDeviceUuid()       { return settings.get(K_DEVICE_UUID, ""); }
    public void setDeviceUuid(String v) { settings.set(K_DEVICE_UUID, v != null ? v : ""); }

    // Throttle cek versi/config: hemat request pada HP 24/7 (pull sudah membawa data + heartbeat).
    public long getVersionCheckAt() { return parseLong(settings.get(K_VERSION_CHECK_AT, "0")); }
    public void setVersionCheckAt(long v) { settings.set(K_VERSION_CHECK_AT, String.valueOf(v)); }
    public long getConfigCheckAt()  { return parseLong(settings.get(K_CONFIG_CHECK_AT, "0")); }
    public void setConfigCheckAt(long v)  { settings.set(K_CONFIG_CHECK_AT, String.valueOf(v)); }
    private static long parseLong(String s) {
        try { return Long.parseLong(s != null ? s.trim() : "0"); } catch (Exception e) { return 0; }
    }

    public String getBranchCode()       { return settings.get(K_BRANCH_CODE, ""); }
    public String getBranchUuid()       { return settings.get(K_BRANCH_UUID, ""); }
    public String getBranchName()       { return settings.get(K_BRANCH_NAME, ""); }
    public void setBranch(String code, String uuid, String name) {
        settings.set(K_BRANCH_CODE, code != null ? code : "");
        settings.set(K_BRANCH_UUID, uuid != null ? uuid : "");
        settings.set(K_BRANCH_NAME, name != null ? name : "");
    }

    public boolean isEnabled()        { return "1".equals(settings.get(K_ENABLED, "0")); }
    public void setEnabled(boolean v) { settings.set(K_ENABLED, v ? "1" : "0"); }

    public boolean isEnrolled() {
        return !getToken().isEmpty() && !getBaseUrl().isEmpty();
    }

    public String getLastSyncAt()       { return settings.get(K_LAST_AT, ""); }
    public void setLastSyncAt(String v) { settings.set(K_LAST_AT, v != null ? v : ""); }

    public String getCursor(String entity)            { return settings.get(K_CURSOR + entity, ""); }
    public void setCursor(String entity, String value){ settings.set(K_CURSOR + entity, value != null ? value : ""); }

    // ---- Branch-authoritative gallon stock (server-computed; device displays it verbatim) ----
    public void setStokGalon(int masuk, int keluar, int kembali, int tersedia) {
        settings.set(K_STOK_MASUK, String.valueOf(masuk));
        settings.set(K_STOK_KELUAR, String.valueOf(keluar));
        settings.set(K_STOK_KEMBALI, String.valueOf(kembali));
        settings.set(K_STOK_TERSEDIA, String.valueOf(tersedia));
        settings.set(K_STOK_HAVE, "1");
    }
    /** True once the server's branch stok has been pulled at least once (else fall back to local compute). */
    public boolean hasServerStok() { return "1".equals(settings.get(K_STOK_HAVE, "0")); }
    public int getStokMasuk()    { return parseIntOr(settings.get(K_STOK_MASUK, "0")); }
    public int getStokKeluar()   { return parseIntOr(settings.get(K_STOK_KELUAR, "0")); }
    public int getStokKembali()  { return parseIntOr(settings.get(K_STOK_KEMBALI, "0")); }
    public int getStokTersedia() { return parseIntOr(settings.get(K_STOK_TERSEDIA, "0")); }

    private static int parseIntOr(String s) {
        try { return Integer.parseInt(s != null ? s.trim() : "0"); } catch (Exception e) { return 0; }
    }

    /** One-time recovery flag: re-pull staff & products once after the "web staff appears" fix
     *  (their cursor had advanced past rows whose insert previously failed on the NOT NULL pin). */
    private static final String K_REPULL_STAFF = "sync_repull_staff_v1";
    public boolean needsStaffRepull() { return ! "1".equals(settings.get(K_REPULL_STAFF, "0")); }
    public void markStaffRepulled()   { settings.set(K_REPULL_STAFF, "1"); }

    /** One-time full re-pull of customers when the app first runs after the "customers are
     *  branch-wide" change: the local cursor sits past other devices' existing customers (older
     *  updated_at), so reset it once to fetch ALL branch customers + their is_mine flag.
     *  <p>Bumped to v2: the v1 re-pull ran against the pre-fix single-page, tie-UNsafe pull, so
     *  devices whose cursor stalled on a big same-timestamp customer cluster (bulk import) are
     *  missing rows. v2 forces one more epoch re-pull now that pull() drains all pages and the
     *  server cursor is tie-safe (updated_at,uuid) — recovering every branch customer.
     *  <p>Bumped to v3: this build adds pull-only branch aggregate columns (srv_trx/ordered/…) +
     *  origin_label; a fresh epoch re-pull populates them on every existing customer row. */
    private static final String K_REPULL_CUSTOMERS = "sync_repull_customers_v4";
    public boolean needsCustomerRepull() { return ! "1".equals(settings.get(K_REPULL_CUSTOMERS, "0")); }
    public void markCustomerRepulled()   { settings.set(K_REPULL_CUSTOMERS, "1"); }

    /** Rising-edge flag: sudah pernah memperingatkan admin soal jenis galon ganda
     *  (Kasus B upgrade). Diset saat duplikat terdeteksi, di-reset saat sudah bersih,
     *  supaya notifikasi tidak spam tiap sinkron. */
    private static final String K_WARNED_DUP_PRODUCTS = "sync_warned_dup_products";
    public boolean wasDupProductsWarned() { return "1".equals(settings.get(K_WARNED_DUP_PRODUCTS, "0")); }
    public void setDupProductsWarned(boolean v) { settings.set(K_WARNED_DUP_PRODUCTS, v ? "1" : "0"); }

    // ---- Mandatory APK update (hash-based) ----
    /** Store the server-published APK to converge to. A NEW apk (different sha) resets any snooze. */
    public void setUpdate(String url, String sha, String name) {
        String prev = getUpdateSha();
        settings.set(K_UPD_URL, url != null ? url : "");
        settings.set(K_UPD_SHA, sha != null ? sha : "");
        settings.set(K_UPD_NAME, name != null ? name : "");
        if (sha != null && !sha.equalsIgnoreCase(prev)) settings.set(K_UPD_SNOOZE, "0");
    }
    public String getUpdateUrl()  { return settings.get(K_UPD_URL, ""); }
    public String getUpdateSha()  { return settings.get(K_UPD_SHA, ""); }
    public String getUpdateName() { return settings.get(K_UPD_NAME, ""); }
    public long getSnoozeUntil()  { try { return Long.parseLong(settings.get(K_UPD_SNOOZE, "0")); } catch (Exception e) { return 0L; } }
    public void setSnoozeUntil(long ms) { settings.set(K_UPD_SNOOZE, String.valueOf(ms)); }
    public String getCachedMyApkSha()   { return settings.get(K_UPD_MYSHA, ""); }
    public long getCachedMyApkShaAt()   { try { return Long.parseLong(settings.get(K_UPD_MYSHA_AT, "0")); } catch (Exception e) { return 0L; } }
    public void setCachedMyApkSha(String sha, long lastUpdateTime) {
        settings.set(K_UPD_MYSHA, sha != null ? sha : "");
        settings.set(K_UPD_MYSHA_AT, String.valueOf(lastUpdateTime));
    }

    /** Cursor for the broadcasts REST poll (last shown created_at). */
    public String getBroadcastCursor()       { return settings.get(K_BROADCAST_AT, ""); }
    public void setBroadcastCursor(String v) { settings.set(K_BROADCAST_AT, v != null ? v : ""); }

    /** Cursor for the device-commands REST poll (last run created_at). */
    public String getCommandCursor()         { return settings.get(K_COMMAND_AT, ""); }
    public void setCommandCursor(String v)   { settings.set(K_COMMAND_AT, v != null ? v : ""); }

    public boolean isLocationTrackingEnabled()  { return "1".equals(settings.get(K_LOC_ENABLED, "1")); }
    public void setLocationTrackingEnabled(boolean v) { settings.set(K_LOC_ENABLED, v ? "1" : "0"); }

    /** Location reporting interval while clocked in, in ms (default 10 min). Min 60s. */
    public long getLocationIntervalMs() { return getLocationIntervalSeconds() * 1000L; }
    public int getLocationIntervalSeconds() {
        // Floor 10s (not 60): the server hands back 15s while a delivery is in progress for live
        // tracking; a 60s floor would silently cap that back to 60s and break near-real-time GPS.
        try { return Math.max(10, Integer.parseInt(settings.get(K_LOC_INTERVAL, "600"))); }
        catch (Exception e) { return 600; }
    }
    public void setLocationIntervalSeconds(int sec) {
        settings.set(K_LOC_INTERVAL, String.valueOf(Math.max(10, sec)));
    }

    /** Forget enrollment (e.g. unbind device). */
    public void clear() {
        setToken("");
        setEnabled(false);
    }

    private static String trimSlash(String url) {
        if (url == null) return "";
        url = url.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }
}
