package com.crowja.damiupos.sync;

import android.content.Context;

import androidx.annotation.Nullable;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Klien "Chat WA" ({@code /api/wa-inbox/*} di DAMIUPOS-Online, lihat docs/WA_INBOX_API.md di repo
 * server). Semua panggilan SINKRON — jalankan di thread background. Peran staf yang sedang login
 * dikirim lewat header {@code X-Staff-Uuid}; server yang memeriksanya (Admin/Marketing/SPV).
 *
 * <p>Tak pernah melempar: kegagalan jaringan menjadi {@link Res} dengan {@code status == 0}.</p>
 */
public final class WaInboxApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static final class Res {
        public final int status;
        @Nullable public final JSONObject body;
        public final String error;

        Res(int status, @Nullable JSONObject body, String error) {
            this.status = status;
            this.body = body;
            this.error = error;
        }

        public boolean ok() {
            return status >= 200 && status < 300 && body != null && body.optBoolean("ok", false);
        }
    }

    private final SyncSettings cfg;
    private final String staffUuid;

    public WaInboxApi(Context ctx) {
        Context app = ctx.getApplicationContext();
        DatabaseHelper db = DatabaseHelper.getInstance(app);
        SettingsDao sd = new SettingsDao(db);
        this.cfg = new SyncSettings(sd);
        String u = null;
        try {
            long uid = sd.getCurrentUserId();
            if (uid > 0) u = new UserDao(db).getSyncUuidById(uid);
        } catch (Exception ignored) {
            // tanpa uuid staf server menolak dengan forbidden_role — pesan galatnya jelas
        }
        this.staffUuid = u != null ? u : "";
    }

    /** Akun WA cabang + badge belum dibaca. */
    public Res accounts() {
        return get("/api/wa-inbox/accounts", null);
    }

    /** @param days jendela hari; {@code <= 0} = semua waktu. */
    public Res conversations(String account, int days) {
        return get("/api/wa-inbox/conversations", new String[]{"account", account,
                "days", days <= 0 ? "all" : String.valueOf(days)});
    }

    public Res messages(String account, String jid, int limit) {
        return get("/api/wa-inbox/messages", new String[]{"account", account, "jid", jid,
                "limit", String.valueOf(limit)});
    }

    public Res markRead(String account, String jid) {
        return post("/api/wa-inbox/read", obj("account", account, "jid", jid));
    }

    /** Kirim teks dan/atau lampiran; {@code mediaBase64 == null} = teks saja. Audio/* = voice note. */
    public Res send(String account, String jid, String text, @Nullable String quotedId, String clientKey,
                    @Nullable String mediaBase64, @Nullable String mimetype, @Nullable String fileName) {
        JSONObject b = obj("account", account, "jid", jid, "text", text, "client_key", clientKey);
        try {
            if (quotedId != null) b.put("quoted_id", quotedId);
            if (mediaBase64 != null) {
                b.put("media_base64", mediaBase64);
                b.put("mimetype", mimetype);
                if (fileName != null) b.put("file_name", fileName);
            }
        } catch (Exception ignored) {
            // put dengan kunci non-null tak pernah gagal
        }
        return post("/api/wa-inbox/send", b);
    }

    /** {@code emoji} kosong = cabut reaksi. */
    public Res react(String account, String jid, String messageId, String emoji, boolean fromMe) {
        JSONObject b = obj("account", account, "jid", jid, "message_id", messageId, "emoji", emoji);
        try {
            b.put("from_me", fromMe);
        } catch (Exception ignored) {
            // lihat send()
        }
        return post("/api/wa-inbox/react", b);
    }

    /** Unduh lampiran ke {@code out}. Balas null bila sukses, selain itu pesan galat. */
    @Nullable
    public String downloadMedia(String account, String messageId, @Nullable String mimetype, File out) {
        HttpUrl base = HttpUrl.parse(cfg.getBaseUrl() + "/api/wa-inbox/media");
        if (base == null) return "Alamat server tidak valid.";
        HttpUrl.Builder ub = base.newBuilder().addQueryParameter("account", account)
                .addQueryParameter("message_id", messageId);
        if (mimetype != null) ub.addQueryParameter("mimetype", mimetype);
        try (Response r = Http.SHARED.newCall(auth(new Request.Builder().url(ub.build()).get()).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                return r.code() == 404 ? "Lampiran sudah tidak tersedia." : "Lampiran gagal dimuat (HTTP " + r.code() + ").";
            }
            try (InputStream in = r.body().byteStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return null;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return "Server tidak terjangkau.";
        }
    }

    /** Unduh foto profil kontak ke {@code out}. Balas 200 = ada, 404 = tak punya foto, selain itu galat sementara. */
    public int downloadAvatar(String account, String jid, File out) {
        HttpUrl base = HttpUrl.parse(cfg.getBaseUrl() + "/api/wa-inbox/avatar");
        if (base == null) return 0;
        HttpUrl url = base.newBuilder().addQueryParameter("account", account).addQueryParameter("jid", jid).build();
        try (Response r = Http.SHARED.newCall(auth(new Request.Builder().url(url).get()).build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return r.code();
            try (InputStream in = r.body().byteStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return 200;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------

    private Res get(String path, @Nullable String[] query) {
        HttpUrl base = HttpUrl.parse(cfg.getBaseUrl() + path);
        if (base == null) return new Res(0, null, "Alamat server tidak valid.");
        HttpUrl.Builder ub = base.newBuilder();
        for (int i = 0; query != null && i + 1 < query.length; i += 2) ub.addQueryParameter(query[i], query[i + 1]);
        return exec(auth(new Request.Builder().url(ub.build()).get()));
    }

    private Res post(String path, JSONObject body) {
        HttpUrl url = HttpUrl.parse(cfg.getBaseUrl() + path);
        if (url == null) return new Res(0, null, "Alamat server tidak valid.");
        return exec(auth(new Request.Builder().url(url).post(RequestBody.create(body.toString(), JSON))));
    }

    private Request.Builder auth(Request.Builder b) {
        b.header("Accept", "application/json").header("X-Staff-Uuid", staffUuid);
        String token = cfg.getToken();
        if (token != null && !token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return b;
    }

    private Res exec(Request.Builder b) {
        try (Response r = Http.SHARED.newCall(b.build()).execute()) {
            String s = r.body() != null ? r.body().string() : "";
            JSONObject json = null;
            try {
                json = s.isEmpty() ? new JSONObject() : new JSONObject(s);
            } catch (Exception ignored) {
                // bukan JSON (halaman galat proxy) — status HTTP tetap dilaporkan
            }
            String err = "";
            if (!r.isSuccessful()) {
                JSONObject e = json != null ? json.optJSONObject("error") : null;
                err = e != null ? e.optString("message", "") : "";
                if (r.code() == 401) err = "Perangkat tidak lagi terotorisasi — hubungkan ulang di Pengaturan.";
                if (err.isEmpty()) err = "Server menjawab HTTP " + r.code() + ".";
            }
            return new Res(r.code(), json, err);
        } catch (Exception e) {
            return new Res(0, null, "Server tidak terjangkau.");
        }
    }

    private static JSONObject obj(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (Exception ignored) {
            // kunci non-null
        }
        return o;
    }
}
