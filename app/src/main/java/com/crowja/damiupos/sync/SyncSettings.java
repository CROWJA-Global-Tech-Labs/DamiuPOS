package com.crowja.damiupos.sync;

import com.crowja.damiupos.db.SettingsDao;

/**
 * Persisted online-sync configuration (server URL, device token, branch, and the
 * per-entity pull cursors). Backed by the existing key-value {@code settings} table.
 */
public class SyncSettings {

    private static final String K_BASE_URL    = "sync_base_url";
    private static final String K_TOKEN       = "sync_token";
    private static final String K_DEVICE_UUID = "sync_device_uuid";
    private static final String K_BRANCH_CODE = "sync_branch_code";
    private static final String K_BRANCH_UUID = "sync_branch_uuid";
    private static final String K_BRANCH_NAME = "sync_branch_name";
    private static final String K_ENABLED     = "sync_enabled";
    private static final String K_LAST_AT     = "sync_last_at";
    private static final String K_CURSOR      = "sync_cursor_"; // + entity

    // MQTT broker config (handed out at enrollment).
    private static final String K_MQTT_HOST = "sync_mqtt_host";
    private static final String K_MQTT_PORT = "sync_mqtt_port";
    private static final String K_MQTT_TLS  = "sync_mqtt_tls";
    private static final String K_MQTT_USER = "sync_mqtt_user";
    private static final String K_MQTT_PASS = "sync_mqtt_pass";
    private static final String K_MQTT_PREFIX = "sync_mqtt_prefix";

    // Latest published app version + the version the user already dismissed.
    private static final String K_VER_CODE = "sync_ver_code";
    private static final String K_VER_NAME = "sync_ver_name";
    private static final String K_VER_URL  = "sync_ver_url";
    private static final String K_VER_LOG  = "sync_ver_log";
    private static final String K_VER_MAND = "sync_ver_mandatory";
    private static final String K_VER_DISMISSED = "sync_ver_dismissed";

    // Track this device's staff location while clocked in (default on when enrolled).
    private static final String K_LOC_ENABLED = "sync_loc_enabled";

    private final SettingsDao settings;

    public SyncSettings(SettingsDao settings) {
        this.settings = settings;
    }

    public String getBaseUrl()      { return trimSlash(settings.get(K_BASE_URL, "")); }
    public void setBaseUrl(String v){ settings.set(K_BASE_URL, trimSlash(v)); }

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

    // ---- MQTT broker config ----
    public void setMqtt(String host, int port, boolean tls, String user, String pass, String prefix) {
        settings.set(K_MQTT_HOST, host != null ? host : "");
        settings.set(K_MQTT_PORT, String.valueOf(port));
        settings.set(K_MQTT_TLS, tls ? "1" : "0");
        settings.set(K_MQTT_USER, user != null ? user : "");
        settings.set(K_MQTT_PASS, pass != null ? pass : "");
        settings.set(K_MQTT_PREFIX, prefix != null ? prefix : "damiupos");
    }
    public String getMqttHost()   { return settings.get(K_MQTT_HOST, ""); }
    public int getMqttPort()      { try { return Integer.parseInt(settings.get(K_MQTT_PORT, "8883")); } catch (Exception e) { return 8883; } }
    public boolean isMqttTls()    { return "1".equals(settings.get(K_MQTT_TLS, "1")); }
    public String getMqttUser()   { return settings.get(K_MQTT_USER, ""); }
    public String getMqttPass()   { return settings.get(K_MQTT_PASS, ""); }
    public String getMqttPrefix() { return settings.get(K_MQTT_PREFIX, "damiupos"); }
    public boolean isMqttConfigured() { return !getMqttHost().isEmpty() && !getMqttUser().isEmpty(); }

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

    public boolean isLocationTrackingEnabled()  { return "1".equals(settings.get(K_LOC_ENABLED, "1")); }
    public void setLocationTrackingEnabled(boolean v) { settings.set(K_LOC_ENABLED, v ? "1" : "0"); }

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
