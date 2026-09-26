package com.crowja.damiupos.wa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Kirim WA ke pelanggan lewat FREZ WA Bridge server DULU, baru jatuh ke jalur manual HP (deep-link
 * wa.me / share ke WhatsApp) bila Bridge gagal — SATU pintu untuk semua fitur aplikasi yang mengirim
 * WA ke pelanggan (struk, follow-up, WA Perkenalan, kendala pengiriman, bukti antar, jadwal ulang,
 * balasan pesanan masuk, perintah {@code wa_send} dashboard, …).
 *
 * <p><b>Akun pengirim dipilih server, bukan HP</b> ({@code POST /api/wa-bridge/send},
 * {@code App\Http\Controllers\Api\WaBridgeSendController}): urutan prioritas SAMA dengan auto-send
 * server — akun yang sudah 2 arah dengan pelanggan (akun perangkat yang ditugaskan diutamakan),
 * lalu akun pengirim cabang (setelan {@code wa_auto_sender_account}), lalu akun cadangan urut
 * {@code fallback_priority} bila akun itu offline / kuota proaktifnya habis. {@link Msg#type} menyaring
 * akun yang diizinkan untuk jenis pesan itu (kolom {@code send_*} di halaman Akun WhatsApp).</p>
 *
 * <p><b>Gagal = jalur manual lama, tak pernah diam.</b> HP belum terdaftar, tak ada jaringan, Bridge
 * belum dikonfigurasi, tak ada akun yang bisa dipakai, atau Bridge menolak → {@code fallback} yang
 * diberikan pemanggil dijalankan (perilaku lama persis), jadi staf tak pernah "tergantung" tanpa
 * cara mengirim.</p>
 */
public final class WaBridgeSend {

    private static final String TAG = "DAMIU";

    /** Lampiran di atas ini dikompres ulang ke JPEG sebelum diunggah — batas body server ~4 MB base64. */
    private static final long MEDIA_RAW_MAX_BYTES = 2_500_000L;

    /** Pesan yang sedang dikirim (nomor+isi) — cegah ketukan ganda mengirim dua kali lewat Bridge. */
    private static final Set<String> IN_FLIGHT = new HashSet<>();

    private WaBridgeSend() {}

    /** Satu pesan WA ke pelanggan. Hanya {@code phone} + ({@code text} atau media) yang wajib. */
    public static final class Msg {
        final String phone;
        final String text;
        String type;
        String kind = "reply";
        String customerUuid;
        long customerId;
        String transactionUuid;
        long transactionId;
        String mediaPath;
        String mimetype;
        String fileName;

        private Msg(String phone, String text) {
            this.phone = phone;
            this.text = text != null ? text : "";
        }

        public static Msg to(String phone, String text) { return new Msg(phone, text); }

        /** Jenis pesan (kunci {@code WaAccount::TYPE_COLUMNS} server): struk, followup, intro,
         *  obstacle, diantar, reschedule, broadcast, apology. Null = semua akun boleh. */
        public Msg type(String type) { this.type = type; return this; }

        /** Pesan pembuka/pemberitahuan yang bukan balasan chat (follow-up, intro, kendala, …) —
         *  kena kuota proaktif Bridge; server pindah ke akun cadangan bila kuota akun utama habis. */
        public Msg proactive() { this.kind = "proactive"; return this; }

        public Msg customerUuid(@Nullable String uuid) { this.customerUuid = uuid; return this; }

        /** id LOKAL pelanggan — uuid sinkronnya dicari sendiri (untuk cek 2 arah lewat LID). */
        public Msg customerId(long id) { this.customerId = id; return this; }

        public Msg transactionUuid(@Nullable String uuid) { this.transactionUuid = uuid; return this; }

        /** id LOKAL transaksi — uuid sinkronnya dicari sendiri (perangkat yang ditugaskan). */
        public Msg transactionId(long id) { this.transactionId = id; return this; }

        /** Foto/gambar lampiran; {@code text} jadi caption-nya. File tak ada → kirim teks saja. */
        public Msg media(@Nullable String path, @Nullable String mimetype, @Nullable String fileName) {
            this.mediaPath = path;
            this.mimetype = mimetype;
            this.fileName = fileName;
            return this;
        }
    }

    public static final class Result {
        public final boolean ok;
        /** Akun Bridge yang sungguh dipakai (null bila tak sampai terkirim). */
        @Nullable public final String account;
        @Nullable public final String code;
        @Nullable public final String message;

        Result(boolean ok, @Nullable String account, @Nullable String code, @Nullable String message) {
            this.ok = ok;
            this.account = account;
            this.code = code;
            this.message = message;
        }

        static Result fail(String code, String message) { return new Result(false, null, code, message); }
    }

    /** Nomor cukup panjang untuk dicoba lewat Bridge (server tetap memvalidasi ulang). */
    public static boolean hasUsablePhone(@Nullable String phone) {
        return phone != null && phone.replaceAll("[^0-9]", "").length() >= 9;
    }

    /**
     * Kirim lewat Bridge dan TUNGGU hasilnya. WAJIB dari thread background; tak pernah melempar.
     */
    public static Result sendBlocking(Context ctx, Msg m) {
        if (!hasUsablePhone(m.phone)) return Result.fail("invalid_phone", "Nomor tidak valid.");
        Context app = ctx.getApplicationContext();
        try {
            DatabaseHelper db = DatabaseHelper.getInstance(app);
            SyncSettings cfg = new SyncSettings(new SettingsDao(db));
            if (!cfg.isEnrolled()) return Result.fail("not_enrolled", "HP belum terhubung ke server.");

            JSONObject body = new JSONObject();
            body.put("to", m.phone);
            body.put("text", m.text);
            body.put("kind", m.kind);
            if (m.type != null) body.put("type", m.type);
            String custUuid = m.customerUuid;
            if ((custUuid == null || custUuid.isEmpty()) && m.customerId > 0) {
                try { custUuid = new CustomerDao(db).getSyncUuidById(m.customerId); } catch (Exception ignored) {}
            }
            if (custUuid != null && !custUuid.isEmpty()) body.put("customer_uuid", custUuid);
            String trxUuid = m.transactionUuid;
            if ((trxUuid == null || trxUuid.isEmpty()) && m.transactionId > 0) {
                try { trxUuid = new TransactionDao(db).getSyncUuidById(m.transactionId); } catch (Exception ignored) {}
            }
            if (trxUuid != null && !trxUuid.isEmpty()) body.put("transaction_uuid", trxUuid);
            // Kunci unik per percobaan — melindungi dari request yang terulang di tingkat jaringan,
            // tanpa "menelan" kirim ulang yang memang disengaja staf.
            body.put("idempotency_key", "app:" + UUID.randomUUID());

            if (m.mediaPath != null && !m.mediaPath.isEmpty()) {
                String[] media = encodeMedia(app, m.mediaPath, m.mimetype);
                if (media != null) {
                    body.put("media_base64", media[0]);
                    body.put("mimetype", media[1]);
                    if (m.fileName != null && !m.fileName.isEmpty()) {
                        body.put("file_name", "image/jpeg".equals(media[1]) && !m.fileName.endsWith(".jpg")
                                ? m.fileName.replaceAll("\\.[A-Za-z0-9]+$", "") + ".jpg" : m.fileName);
                    }
                }
            }
            if (m.text.trim().isEmpty() && !body.has("media_base64")) {
                return Result.fail("empty_message", "Pesan kosong.");
            }

            JSONObject res = new SyncApi(cfg).waBridgeSend(body);
            if (res != null && res.optBoolean("ok", false)) {
                return new Result(true, res.optString("account", null), null, null);
            }
            return parseError(res);
        } catch (SyncApi.SyncException e) {
            try {
                return parseError(new JSONObject(e.body));
            } catch (Exception ignored) {
                return Result.fail("http_" + e.code, "Server menjawab HTTP " + e.code + ".");
            }
        } catch (Exception e) {
            Log.w(TAG, "WA bridge send failed", e);
            return Result.fail("unreachable", "Server tidak terjangkau.");
        }
    }

    private static Result parseError(@Nullable JSONObject res) {
        JSONObject err = res != null ? res.optJSONObject("error") : null;
        String code = err != null ? err.optString("code", "bridge_error") : "bridge_error";
        String msg = err != null ? err.optString("message", "") : (res != null ? res.optString("message", "") : "");
        String account = res != null && res.has("account") ? res.optString("account", null) : null;
        return new Result(false, account, code, msg.isEmpty() ? "Bridge menolak pesan ini." : msg);
    }

    /** Kirim lewat Bridge tanpa dialog; {@code callback} dipanggil di UI thread dengan hasilnya. */
    public static void sendAsync(Context ctx, Msg m, java.util.function.Consumer<Result> callback) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result r = sendBlocking(app, m);
            main.post(() -> callback.accept(r));
        }, "wa-bridge-send").start();
    }

    /**
     * Coba kirim lewat Bridge; sukses → toast "terkirim via Bridge (akun X)" lalu {@code onSent};
     * gagal → toast alasannya lalu {@code fallback} (jalur manual lama pemanggil). Keduanya dijalankan
     * di UI thread. Selama menunggu, dialog progres non-batal menahan layar (bila {@code ctx} Activity)
     * supaya tak ada ketukan ganda / layar tertutup sebelum hasilnya diketahui.
     *
     * <p>Nomor tak layak → {@code fallback} langsung (tanpa ke server).</p>
     */
    public static void sendOrFallback(Context ctx, Msg m, @Nullable Runnable onSent, Runnable fallback) {
        if (!hasUsablePhone(m.phone)) {
            fallback.run();
            return;
        }
        final String key = m.phone.replaceAll("[^0-9]", "") + "|" + m.text.hashCode() + "|" + m.mediaPath;
        synchronized (IN_FLIGHT) {
            if (!IN_FLIGHT.add(key)) {
                Toast.makeText(ctx, "WA ini sedang dikirim…", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final AlertDialog progress = showProgress(ctx);
        sendAsync(ctx, m, r -> {
            synchronized (IN_FLIGHT) { IN_FLIGHT.remove(key); }
            try { if (progress != null && progress.isShowing()) progress.dismiss(); } catch (Exception ignored) {}
            Context toastCtx = ctx.getApplicationContext();
            if (r.ok) {
                Toast.makeText(toastCtx, "WA terkirim via Bridge"
                        + (r.account != null && !r.account.isEmpty() ? " (akun " + r.account + ")" : "") + ".",
                        Toast.LENGTH_SHORT).show();
                if (onSent != null) {
                    try { onSent.run(); } catch (Exception e) { Log.e(TAG, "WA bridge onSent failed", e); }
                }
                return;
            }
            Toast.makeText(toastCtx, "WA Bridge gagal" + (r.message != null ? ": " + r.message : "")
                    + " — kirim manual.", Toast.LENGTH_LONG).show();
            try {
                fallback.run();
            } catch (Exception e) {
                Log.e(TAG, "WA manual fallback failed", e);
                Toast.makeText(toastCtx, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Nullable
    private static AlertDialog showProgress(Context ctx) {
        if (!(ctx instanceof Activity)) return null;
        Activity act = (Activity) ctx;
        if (act.isFinishing() || act.isDestroyed()) return null;
        try {
            int pad = Math.round(20 * act.getResources().getDisplayMetrics().density);
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, pad, pad, pad);
            ProgressBar bar = new ProgressBar(act);
            row.addView(bar);
            TextView tv = new TextView(act);
            tv.setText("Mengirim WA lewat Bridge…");
            tv.setPadding(pad, 0, 0, 0);
            row.addView(tv);
            AlertDialog d = new AlertDialog.Builder(act).setView(row).setCancelable(false).create();
            d.show();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** {base64, mimetype} lampiran; gambar besar/format lain dikompres ke JPEG. Null bila tak terbaca. */
    @Nullable
    private static String[] encodeMedia(Context app, String path, @Nullable String mimetype) {
        File f = new File(path);
        if (!f.exists() || f.length() <= 0) return null;
        String mime = mimetype != null && !mimetype.isEmpty() ? mimetype : guessMime(path);
        File src = f;
        if (f.length() > MEDIA_RAW_MAX_BYTES || !("image/jpeg".equals(mime) || "image/png".equals(mime))) {
            File out = new File(app.getCacheDir(), "wa_bridge_" + System.currentTimeMillis() + ".jpg");
            if (!com.crowja.damiupos.util.BitmapUtils.compressForUpload(path, out, 1600, 82)) return null;
            src = out;
            mime = "image/jpeg";
        }
        try {
            byte[] bytes = readAll(src);
            return new String[]{android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), mime};
        } catch (Exception e) {
            Log.w(TAG, "WA bridge media read failed", e);
            return null;
        } finally {
            if (src != f) //noinspection ResultOfMethodCallIgnored
                src.delete();
        }
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(f.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
