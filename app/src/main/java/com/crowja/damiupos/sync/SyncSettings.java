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

    // Latest published app version + the version the user already dismissed.
    private static final String K_VER_CODE = "sync_ver_code";
    private static final String K_VER_NAME = "sync_ver_name";
    private static final String K_VER_URL  = "sync_ver_url";
    private static final String K_VER_LOG  = "sync_ver_log";
    private static final String K_VER_MAND = "sync_ver_mandatory";
    private static final String K_VER_DISMISSED = "sync_ver_dismissed";
    /** versionCode whose APK has already been downloaded & is ready to install. */
    private static final String K_VER_DOWNLOADED = "sync_ver_downloaded";

    // Last admin broadcast we've shown (created_at) — REST poll cursor.
    private static final String K_BROADCAST_AT = "sync_broadcast_at";
    // Last device command we've run (created_at) — REST poll cursor.
    private static final String K_COMMAND_AT = "sync_command_at";

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

    /** Rising-edge flag: sudah pernah memperingatkan admin soal jenis galon ganda
     *  (Kasus B upgrade). Diset saat duplikat terdeteksi, di-reset saat sudah bersih,
     *  supaya notifikasi tidak spam tiap sinkron. */
    private static final String K_WARNED_DUP_PRODUCTS = "sync_warned_dup_products";
    public boolean wasDupProductsWarned() { return "1".equals(settings.get(K_WARNED_DUP_PRODUCTS, "0")); }
    public void setDupProductsWarned(boolean v) { settings.set(K_WARNED_DUP_PRODUCTS, v ? "1" : "0"); }

    // ---- Latest published app version ----
    public void setLatestVersion(int code, String name, String url, String changelog, boolean mandatory) {
        settings.set(K_VER_CODE, String.valueOf(code));
        settings.set(K_VER_NAME, name != null ? name : "");
        settings.set(K_VER_URL, url != null ? url : "");
        settings.set(K_VER_LOG, changelog != null ? changelog : "");
        settings.set(K_VER_MAND, mandatory ? "1" : "0");
    }
    public int getLatestVersionCode() { try { return Integer.parseInt(settings.get(K_VER_CODE, "0")); } catch (Exception e) { return 0; } }
    public String getLatestVersionName()      { return settings.get(K_VER_NAME, ""); }
    public String getLatestVersionUrl()       { return settings.get(K_VER_URL, ""); }
    public String getLatestVersionChangelog() { return settings.get(K_VER_LOG, ""); }
    public boolean isLatestVersionMandatory() { return "1".equals(settings.get(K_VER_MAND, "0")); }
    public int getDismissedVersion()          { try { return Integer.parseInt(settings.get(K_VER_DISMISSED, "0")); } catch (Exception e) { return 0; } }
    public void setDismissedVersion(int code) { settings.set(K_VER_DISMISSED, String.valueOf(code)); }
    public int getDownloadedVersion()          { try { return Integer.parseInt(settings.get(K_VER_DOWNLOADED, "0")); } catch (Exception e) { return 0; } }
    public void setDownloadedVersion(int code) { settings.set(K_VER_DOWNLOADED, String.valueOf(code)); }

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
        try { return Math.max(60, Integer.parseInt(settings.get(K_LOC_INTERVAL, "600"))); }
        catch (Exception e) { return 600; }
    }
    public void setLocationIntervalSeconds(int sec) {
        settings.set(K_LOC_INTERVAL, String.valueOf(Math.max(60, sec)));
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
