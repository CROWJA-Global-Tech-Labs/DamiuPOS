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
        public SyncException(int code, String message) {
            super("HTTP " + code + ": " + message);
            this.code = code;
        }
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final SyncSettings cfg;

    public SyncApi(SyncSettings cfg) {
        this.cfg = cfg;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    public JSONObject enroll(String baseUrl, String enrollKey, @Nullable String deviceUuid,
                             String name, int versionCode, String versionName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("enroll_key", enrollKey);
        if (deviceUuid != null && !deviceUuid.isEmpty()) body.put("device_uuid", deviceUuid);
        body.put("device_name", name);
        body.put("platform", "android");
        body.put("app_version_code", versionCode);
        body.put("app_version_name", versionName);
        return post(trim(baseUrl) + "/api/devices/enroll", body, null);
    }

    public JSONObject push(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/sync/push", body, cfg.getToken());
    }

    public JSONObject pull(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/sync/pull", body, cfg.getToken());
    }

    public JSONObject locationPing(JSONObject body) throws Exception {
        return post(cfg.getBaseUrl() + "/api/location/ping", body, cfg.getToken());
    }

    public JSONObject version(String baseUrl) throws Exception {
        Request req = new Request.Builder()
                .url(trim(baseUrl) + "/api/version")
                .header("Accept", "application/json")
                .get().build();
        return execute(req);
    }

    /** Device identity + branch + live config (e.g. location_interval_seconds). */
    public JSONObject me() throws Exception {
        return get(cfg.getBaseUrl() + "/api/me", cfg.getToken());
    }

    /** Admin broadcasts for this branch newer than {@code sinceIso}. */
    public JSONObject broadcasts(String sinceIso) throws Exception {
        okhttp3.HttpUrl built = okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/broadcasts")
                .newBuilder()
                .addQueryParameter("since", sinceIso != null ? sinceIso : "")
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
