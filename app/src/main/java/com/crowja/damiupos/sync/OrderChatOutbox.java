package com.crowja.damiupos.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.ChatMessage;
import com.crowja.damiupos.util.BitmapUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Antrean kirim "💬 Chat Pesanan" tingkat PROSES — bukan milik satu ChatLogActivity.
 *
 * <p>Dulu kirim staf yang belum final (mengirim / ⏳ menunggu / ❗ gagal / ❓ tak diketahui) hanya
 * hidup di memori Activity: tombol Back, putar layar, atau proses mati membuangnya diam-diam,
 * padahal baris order_chat_sends di server tetap 'pending' (server tak pernah mengirim ulang
 * sendiri) dan jeda AI 2 jam sudah terpasang — pelanggan tak dapat balasan staf maupun AI.
 * Di sini ulangan kunci-SAMA berjalan di Handler main-looper milik proses, jadi tetap jalan walau
 * layar ditutup, dan entri disimpan ke SharedPreferences supaya gelembungnya muncul lagi saat chat
 * dibuka ulang. Setelah proses mati, entri yang masih tertunda dilanjutkan dengan kunci SAMA hanya
 * bila masih muda ({@link #RESUME_MAX_AGE_MS}); yang lebih tua menjadi "status tak diketahui" —
 * balasan staf tak boleh tiba berjam-jam terlambat tanpa ditanyakan dulu.
 *
 * <p>Arti tiap jawaban server: {@link OrderChatSendPolicy}. Semua method publik (kecuali
 * {@link #pruneFiles}) dipanggil di UI thread.
 */
public final class OrderChatOutbox {

    /** Perubahan satu kirim, dipanggil di UI thread. {@code e.status == null} = terkirim (entri
     *  sudah keluar dari antrean; {@code body.message} = barisnya). {@code body} = body JSON jawaban
     *  sukses ({@code ok:true}: agent_paused_until, message) atau null. */
    public interface Listener {
        void onOutboxUpdate(Entry e, @Nullable JSONObject body);
    }

    public static final class Entry {
        public final String trxUuid;
        public final String clientKey;
        public final String text;
        @Nullable public final String imagePath;
        public final String staffName;
        /** Waktu lokal "yyyy-MM-dd HH:mm:ss" untuk gelembung optimis. */
        public final String localTime;
        public final long createdAt;
        /** Permintaan yang sudah dikirim untuk kunci ini (dipersist: sesudah proses mati, ulangan
         *  tak boleh dikira percobaan pertama). */
        public int attempts;
        /** ChatMessage.STATUS_*; null = terkirim. */
        @Nullable public String status;
        @Nullable public String error;
        boolean inFlight;
        /** Gambar base64 — hanya di memori, dibuang begitu kirim final. */
        @Nullable String mediaB64;

        Entry(String trxUuid, String clientKey, String text, @Nullable String imagePath, String staffName,
              String localTime, long createdAt) {
            this.trxUuid = trxUuid;
            this.clientKey = clientKey;
            this.text = text;
            this.imagePath = imagePath;
            this.staffName = staffName;
            this.localTime = localTime;
            this.createdAt = createdAt;
        }

        /** Masih dikirim (SENDING / PENDING) — bukan FAILED / UNKNOWN. */
        public boolean isInFlightState() {
            return ChatMessage.STATUS_SENDING.equals(status) || ChatMessage.STATUS_PENDING.equals(status);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("trx", trxUuid);
            o.put("key", clientKey);
            o.put("text", text);
            if (imagePath != null) o.put("img", imagePath);
            o.put("staff", staffName);
            o.put("local_time", localTime);
            o.put("created_at", createdAt);
            o.put("attempts", attempts);
            if (status != null) o.put("status", status);
            if (error != null) o.put("error", error);
            return o;
        }

        @Nullable
        static Entry fromJson(JSONObject o) {
            String trx = o.optString("trx", "");
            String key = o.optString("key", "");
            String st = o.isNull("status") ? null : o.optString("status", null);
            if (trx.isEmpty() || key.isEmpty() || st == null) return null;
            Entry e = new Entry(trx, key, o.optString("text", ""),
                    o.isNull("img") ? null : o.optString("img", null),
                    o.optString("staff", ""), o.optString("local_time", ""), o.optLong("created_at", 0L));
            e.attempts = o.optInt("attempts", 0);
            e.status = st;
            e.error = o.isNull("error") ? null : o.optString("error", null);
            return e;
        }
    }

    private static final String PREFS = "order_chat_outbox";
    private static final String K_ENTRIES = "entries";
    /** Kirim yang tertunda saat proses mati dilanjutkan (kunci SAMA) hanya bila semuda ini. */
    static final long RESUME_MAX_AGE_MS = 10L * 60_000L;
    private static final long RESUME_DELAY_MS = 1000L;
    /** Entri dibuang setelah ini (lampirannya juga, lihat {@link #pruneFiles}). */
    static final long ENTRY_TTL_MS = 24L * 3600_000L;
    /** Salinan media pelanggan di HP dipangkas setelah ini. */
    static final long MEDIA_CACHE_TTL_MS = 7L * 24L * 3600_000L;
    /** Batas gambar kiriman staf (spec §0 SEND_MEDIA_MAX, hasil decode). */
    public static final long SEND_MEDIA_MAX = 4L * 1024 * 1024;

    private static OrderChatOutbox instance;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Listener> listeners = new HashMap<>();

    public static synchronized OrderChatOutbox get(Context ctx) {
        if (instance == null) instance = new OrderChatOutbox(ctx.getApplicationContext());
        return instance;
    }

    private OrderChatOutbox(Context app) {
        this.app = app;
        load();
    }

    // ---------------------------------------------------------------- API (UI thread)

    /** Kirim baru dengan client_key BARU; permintaan pertama langsung berjalan. */
    public Entry enqueue(String trxUuid, @Nullable String text, @Nullable String imagePath,
                         @Nullable String staffName, String localTime) {
        Entry e = new Entry(trxUuid, UUID.randomUUID().toString(), text != null ? text : "", imagePath,
                staffName != null ? staffName : "", localTime, System.currentTimeMillis());
        e.status = ChatMessage.STATUS_SENDING;
        entries.put(e.clientKey, e);
        save();
        attempt(e);
        return e;
    }

    /** Entri transaksi ini, urut waktu kirim. */
    public List<Entry> entries(String trxUuid) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.trxUuid.equals(trxUuid)) out.add(e);
        }
        return out;
    }

    /** Keluarkan dari antrean (ulangan dibatalkan): baris server-nya sudah tiba lewat poll, atau
     *  pengguna menghapus / mengirim ulang sebagai pesan baru. */
    public void discard(@Nullable String clientKey) {
        Entry e = clientKey != null ? entries.remove(clientKey) : null;
        if (e == null) return;
        handler.removeCallbacksAndMessages(e);
        e.mediaB64 = null;
        save();
    }

    public void setListener(String trxUuid, Listener l) {
        listeners.put(trxUuid, l);
    }

    /** Lepas {@code l} — hanya bila masih pendengar transaksi itu (Activity pengganti setelah putar
     *  layar sudah memasang miliknya sendiri). */
    public void removeListener(String trxUuid, Listener l) {
        if (listeners.get(trxUuid) == l) listeners.remove(trxUuid);
    }

    // ---------------------------------------------------------------- kirim

    private void attempt(final Entry e) {
        if (e.inFlight || entries.get(e.clientKey) != e) return;
        e.inFlight = true;
        e.attempts++;
        save();
        final Context ctx = app;
        new Thread(() -> {
            SyncApi.OrderChatResult res = null;
            String localError = null;
            try {
                JSONObject body = new JSONObject();
                if (!e.text.isEmpty()) body.put("text", e.text);
                body.put("client_key", e.clientKey);
                if (!e.staffName.isEmpty()) body.put("staff_name", e.staffName);
                if (e.imagePath != null) {
                    if (e.mediaB64 == null) e.mediaB64 = encodeImage(e.imagePath);
                    if (e.mediaB64 == null) {
                        localError = "gambar tidak terbaca";
                    } else {
                        body.put("media_base64", e.mediaB64);
                        body.put("mimetype", "image/jpeg");
                        body.put("file_name", new File(e.imagePath).getName());
                    }
                }
                if (localError == null) {
                    SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
                    res = new SyncApi(cfg).orderChatSend(e.trxUuid, body);
                }
            } catch (Exception ex) {
                localError = "gagal menyusun pesan";
            }
            final SyncApi.OrderChatResult fRes = res;
            final String fErr = localError;
            handler.post(() -> onResult(e, fRes, fErr));
        }, "order-chat-send").start();
    }

    private void onResult(Entry e, @Nullable SyncApi.OrderChatResult res, @Nullable String localError) {
        e.inFlight = false;
        if (entries.get(e.clientKey) != e) return;   // dibuang selagi permintaan berjalan
        JSONObject body = res != null ? res.body : null;
        OrderChatSendPolicy.Outcome out = res == null
                ? OrderChatSendPolicy.Outcome.FAILED
                : OrderChatSendPolicy.decide(res.status, res.isOk(),
                        body != null && body.optBoolean("pending", false),
                        body != null && body.optJSONObject("message") != null,
                        res.errorCode, res.newKeyRequired, e.attempts <= 1);
        switch (out) {
            case SENT:
                entries.remove(e.clientKey);
                e.status = null;
                e.error = null;
                e.mediaB64 = null;
                break;
            case RETRY:
            case RETRY_SLOW:
                if (e.attempts > OrderChatSendPolicy.MAX_RETRIES) {
                    e.status = ChatMessage.STATUS_UNKNOWN;
                    e.error = null;
                    e.mediaB64 = null;
                } else {
                    e.status = ChatMessage.STATUS_PENDING;
                    e.error = null;
                    schedule(e, out == OrderChatSendPolicy.Outcome.RETRY_SLOW
                            ? OrderChatSendPolicy.rateLimitDelayMs(res.retryAfterMs)
                            : OrderChatSendPolicy.retryDelayMs(e.attempts));
                }
                break;
            case UNKNOWN:
                e.status = ChatMessage.STATUS_UNKNOWN;
                e.error = res != null ? res.errorMessage : null;
                e.mediaB64 = null;
                break;
            default:
                e.status = ChatMessage.STATUS_FAILED;
                e.error = res != null ? res.errorMessage : localError;
                e.mediaB64 = null;
                break;
        }
        save();
        Listener l = listeners.get(e.trxUuid);
        if (l != null) l.onOutboxUpdate(e, res != null && res.isOk() ? body : null);
    }

    private void schedule(Entry e, long delayMs) {
        handler.removeCallbacksAndMessages(e);
        handler.postAtTime(() -> attempt(e), e, SystemClock.uptimeMillis() + Math.max(0L, delayMs));
    }

    @Nullable
    private static String encodeImage(String path) {
        File f = new File(path);
        if (!f.exists() || f.length() <= 0 || f.length() > SEND_MEDIA_MAX) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- simpan / muat

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries.values()) arr.put(e.toJson());
            prefs().edit().putString(K_ENTRIES, arr.toString()).apply();
        } catch (Exception ignored) {
            // best-effort: antrean di memori tetap berjalan
        }
    }

    private void load() {
        String raw;
        try {
            raw = prefs().getString(K_ENTRIES, null);
        } catch (Exception ex) {
            raw = null;
        }
        if (raw == null || raw.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean dirty = false;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                Entry e = o != null ? Entry.fromJson(o) : null;
                if (e == null || now - e.createdAt > ENTRY_TTL_MS || e.createdAt - now > ENTRY_TTL_MS) {
                    dirty = true;
                    continue;
                }
                entries.put(e.clientKey, e);
                if (e.isInFlightState()) {
                    dirty = true;
                    if (now - e.createdAt <= RESUME_MAX_AGE_MS && e.attempts <= OrderChatSendPolicy.MAX_RETRIES) {
                        e.status = ChatMessage.STATUS_PENDING;
                        schedule(e, RESUME_DELAY_MS);
                    } else {
                        e.status = ChatMessage.STATUS_UNKNOWN;
                        e.error = null;
                    }
                }
            }
        } catch (Exception ex) {
            dirty = true;
        }
        if (dirty) save();
    }

    // ---------------------------------------------------------------- berkas lokal

    /** Folder lampiran gambar yang disiapkan untuk dikirim staf. */
    public static File attachDir(Context ctx) {
        File dir = new File(ctx.getCacheDir(), "order_chat");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /**
     * Pangkas berkas lokal chat WA: lampiran kiriman staf ({@code cacheDir/order_chat}, lebih tua
     * dari masa hidup entri antrean + 1 jam) dan salinan media pelanggan
     * ({@code cacheDir/remote_img/chat_media}, &gt; 7 hari — server menyimpannya sampai 180 hari
     * setelah jendela ditutup, salinan di tiap HP staf tak perlu selama itu). OFF main thread;
     * dipanggil saat layar chat dibuka dan tiap siklus SyncWorker. Best-effort.
     */
    public static void pruneFiles(Context ctx) {
        if (ctx == null) return;
        try {
            BitmapUtils.pruneDir(new File(ctx.getCacheDir(), "order_chat"), ENTRY_TTL_MS + 3600_000L);
            BitmapUtils.pruneDir(BitmapUtils.remoteCacheDir(ctx, BitmapUtils.CHAT_MEDIA_DIR), MEDIA_CACHE_TTL_MS);
        } catch (Throwable ignored) {
            // best-effort
        }
    }
}
