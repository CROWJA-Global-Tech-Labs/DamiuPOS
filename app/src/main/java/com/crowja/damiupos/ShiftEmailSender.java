package com.crowja.damiupos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

/**
 * Auto-kirim laporan shift via email SMTP saat Pulang (clock out) — berjalan di
 * background thread, tanpa membuka aplikasi email. Memakai JavaMail (android-mail).
 *
 * <p>Hasil (sukses/gagal) ditampilkan sebagai Toast; kegagalan tidak fatal —
 * file laporan tetap tersimpan di folder exports sebagai cadangan.
 */
public final class ShiftEmailSender {

    private ShiftEmailSender() {}

    /** Callback hasil tes koneksi SMTP (dipanggil di main thread). */
    public interface TestCallback {
        void onResult(boolean ok, String message);
    }

    /** Callback hasil kirim email (dipanggil di background thread pengirim). */
    public interface SendCallback {
        void onDone(boolean success, String error);
    }

    /**
     * Versi sendAsync dengan callback hasil & TANPA toast — pemanggil yang
     * menentukan tindak lanjut (mis. tandai rekap terkirim hanya kalau sukses,
     * supaya retry di login berikutnya kalau gagal).
     */
    public static void sendAsync(Context ctx, String host, int port,
                                 String user, String pass, String to,
                                 String subject, String body, List<File> attachments,
                                 SendCallback cb) {
        new Thread(() -> {
            String err = null;
            try {
                send(host, port, user, pass, to, subject, body, attachments);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            if (cb != null) cb.onDone(err == null, err);
        }, "shift-email-cb").start();
    }

    /**
     * Tes konfigurasi SMTP: connect + auth + kirim 1 email tes ke {@code to}.
     * Hasil (sukses/pesan error) dikirim ke {@code cb} di main thread.
     */
    public static void testAsync(String host, int port, String user, String pass,
                                 String to, TestCallback cb) {
        new Thread(() -> {
            String err = null;
            try {
                send(host, port, user, pass, to,
                        "Tes SMTP — DAMIU POS",
                        "Ini email tes dari DAMIU POS.\n\nKalau Anda menerima pesan ini, "
                                + "konfigurasi SMTP & email admin sudah benar dan laporan "
                                + "shift akan terkirim otomatis saat Pulang.",
                        (List<File>) null);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final String fErr = err;
            new Handler(Looper.getMainLooper()).post(() ->
                    cb.onResult(fErr == null, fErr));
        }, "smtp-test").start();
    }

    public static void sendAsync(Context ctx, String host, int port,
                                 String user, String pass, String to,
                                 String subject, String body, List<File> attachments) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String err = null;
            try {
                send(host, port, user, pass, to, subject, body, attachments);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final String fErr = err;
            // Sukses → diam (tanpa toast). Hanya tampilkan toast kalau gagal.
            if (fErr != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, "Gagal kirim email laporan: " + fErr,
                                Toast.LENGTH_LONG).show());
            }
        }, "shift-email").start();
    }

    private static void send(String host, int port, String user, String pass,
                             String to, String subject, String body, List<File> attachments)
            throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        // 465 = SSL implisit; selain itu STARTTLS (587 Gmail dll).
        if (port == 465) {
            props.put("mail.smtp.socketFactory.port", String.valueOf(port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        // Timeout lebih longgar untuk jaringan lapangan yang lambat (upload foto
        // selfie bisa makan waktu di sinyal lemah). Aman karena pengiriman shift
        // report ditahan WakeLock + retry, jadi tidak ter-kill saat layar mati.
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "45000");
        props.put("mail.smtp.writetimeout", "60000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(user));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setSubject(subject);

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(body, "UTF-8");

        MimeMultipart mp = new MimeMultipart();
        mp.addBodyPart(textPart);

        if (attachments != null) {
            for (File f : attachments) {
                if (f == null || !f.exists()) continue;
                MimeBodyPart att = new MimeBodyPart();
                att.setDataHandler(new DataHandler(new FileDataSource(f)));
                att.setFileName(f.getName());
                mp.addBodyPart(att);
            }
        }

        msg.setContent(mp);
        Transport.send(msg);
    }

    // =======================================================================
    // Pengiriman laporan shift yang TAHAN-BANTING untuk kondisi lapangan.
    //
    // Root cause kegagalan di lapangan: dulu email dikirim di Thread lepas tanpa
    // WakeLock. Saat staf "Pulang" lalu langsung mengunci HP, CPU tidur dan
    // thread ter-suspend di tengah kirim (apalagi upload foto selfie di sinyal
    // lemah) → koneksi putus, tanpa retry → laporan hilang. Saat testing layar
    // menyala jadi selalu berhasil.
    //
    // Perbaikan: (1) tulis dulu ke antrian "pending" (write-ahead) supaya tidak
    // hilang walau proses mati; (2) tahan CPU dgn PARTIAL_WAKE_LOCK; (3) retry
    // beberapa kali dgn backoff; (4) sukses → hapus file pending; gagal → biarkan
    // & coba lagi saat login berikutnya (flushPending).
    // =======================================================================

    private static final String PENDING_DIR = "pending_reports";
    private static final int SEND_ATTEMPTS = 3;
    /** Serialize semua pengiriman pending supaya thread Pulang & flush-login
     *  tidak memproses file yang sama bersamaan (cegah dobel kirim). */
    private static final Object SEND_LOCK = new Object();

    /**
     * Kirim laporan shift dengan durable + retry. Dipanggil saat Pulang.
     * Semua IO (tulis antrian + kirim) dilakukan di background thread.
     */
    public static void sendShiftReport(Context ctx, String subject, String body,
                                       List<File> attachments) {
        final Context app = ctx.getApplicationContext();
        // Snapshot path lampiran (cepat); file IO dilakukan di thread.
        final ArrayList<String> paths = new ArrayList<>();
        if (attachments != null) {
            for (File f : attachments) if (f != null) paths.add(f.getAbsolutePath());
        }
        new Thread(() -> {
            PowerManager.WakeLock wl = acquireWakeLock(app);
            try {
                File pending = writePending(app, "Laporan Shift", subject, body, paths, null);
                boolean ok;
                synchronized (SEND_LOCK) {
                    ok = (pending != null)
                            ? trySendPending(app, pending)
                            : sendDirect(app, subject, body, paths); // fallback tanpa antrian
                }
                if (!ok) {
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                            "Laporan shift belum terkirim — akan dicoba lagi otomatis saat login berikutnya",
                            Toast.LENGTH_LONG).show());
                }
            } finally {
                releaseWakeLock(wl);
            }
        }, "shift-report").start();
    }

    /** Kirim ulang semua laporan pending (dipanggil saat login berikutnya). */
    public static void flushPending(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            File dir = new File(app.getFilesDir(), PENDING_DIR);
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            if (!new SettingsDao(DatabaseHelper.getInstance(app)).isShiftEmailConfigured()) return;
            PowerManager.WakeLock wl = acquireWakeLock(app);
            try {
                synchronized (SEND_LOCK) {
                    for (File f : files) {
                        if (f.getName().endsWith(".json")) trySendPending(app, f);
                    }
                }
            } finally {
                releaseWakeLock(wl);
            }
        }, "shift-report-flush").start();
    }

    /**
     * Simpan laporan ke antrian pending TANPA langsung kirim — dipakai saat
     * pengiriman pertama (mis. rekap bulanan/pekanan) gagal, supaya di-retry
     * otomatis tiap login berikutnya (flushPending) & bisa di-resend manual
     * oleh admin. {@code cleanupFiles} = file milik laporan ini (mis. ZIP/PDF)
     * yang dihapus setelah berhasil terkirim.
     */
    public static void enqueue(Context ctx, String type, String subject, String body,
                               List<File> attachments, List<File> cleanupFiles) {
        Context app = ctx.getApplicationContext();
        writePending(app, type, subject, body, pathsOf(attachments), pathsOf(cleanupFiles));
    }

    private static ArrayList<String> pathsOf(List<File> files) {
        ArrayList<String> out = new ArrayList<>();
        if (files != null) for (File f : files) if (f != null) out.add(f.getAbsolutePath());
        return out;
    }

    /** Ringkasan 1 laporan pending (gagal terkirim) untuk UI admin. */
    public static class PendingInfo {
        public String fileName;
        public String type;
        public String subject;
        public String createdAt;
    }

    /** Daftar semua laporan pending (gagal terkirim), terbaru dulu. */
    public static List<PendingInfo> listPending(Context ctx) {
        List<PendingInfo> out = new ArrayList<>();
        File dir = new File(ctx.getApplicationContext().getFilesDir(), PENDING_DIR);
        File[] files = dir.listFiles();
        if (files == null) return out;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = new JSONObject(readFile(f));
                PendingInfo p = new PendingInfo();
                p.fileName = f.getName();
                p.type = o.optString("type", "Laporan");
                p.subject = o.optString("subject", "");
                p.createdAt = o.optString("createdAt", "");
                out.add(p);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Resend 1 laporan pending (by file name). Callback dipanggil di main thread. */
    public static void resendOneAsync(Context ctx, String fileName, SendCallback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            PowerManager.WakeLock wl = acquireWakeLock(app);
            boolean ok;
            try {
                File f = new File(new File(app.getFilesDir(), PENDING_DIR), fileName);
                synchronized (SEND_LOCK) {
                    ok = trySendPending(app, f);
                }
            } finally {
                releaseWakeLock(wl);
            }
            final boolean fok = ok;
            if (cb != null) {
                new Handler(Looper.getMainLooper()).post(() ->
                        cb.onDone(fok, fok ? null : "Masih gagal terkirim"));
            }
        }, "report-resend").start();
    }

    /** Callback hasil "Resend Semua". */
    public interface ResendAllCallback { void onDone(int sent, int remaining); }

    /** Resend semua laporan pending sekaligus (background + WakeLock). */
    public static void resendAllAsync(Context ctx, ResendAllCallback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            int sent = 0, remaining = 0;
            PowerManager.WakeLock wl = acquireWakeLock(app);
            try {
                File dir = new File(app.getFilesDir(), PENDING_DIR);
                File[] files = dir.listFiles();
                synchronized (SEND_LOCK) {
                    if (files != null) {
                        for (File f : files) {
                            if (!f.getName().endsWith(".json")) continue;
                            if (trySendPending(app, f)) sent++; else remaining++;
                        }
                    }
                }
            } finally {
                releaseWakeLock(wl);
            }
            final int fs = sent, fr = remaining;
            if (cb != null) {
                new Handler(Looper.getMainLooper()).post(() -> cb.onDone(fs, fr));
            }
        }, "report-resend-all").start();
    }

    /** Progress pengiriman antrian (dipakai dialog "Mengirim Laporan"). */
    public interface ProgressCallback {
        void onProgress(int current, int total, String label);
        void onComplete(int sent, int remaining);
    }

    /**
     * Kirim SEMUA laporan pending sekarang sambil melaporkan progress —
     * dipakai saat Pulang supaya user lihat progress bar & tetap standby sampai
     * laporan terkirim. Callback di main thread.
     */
    public static void flushAllWithProgress(Context ctx, ProgressCallback cb) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int sent = 0, remaining = 0;
            PowerManager.WakeLock wl = acquireWakeLock(app);
            try {
                File dir = new File(app.getFilesDir(), PENDING_DIR);
                File[] files = dir.listFiles();
                List<File> jsons = new ArrayList<>();
                if (files != null) {
                    for (File f : files) if (f.getName().endsWith(".json")) jsons.add(f);
                }
                final int total = jsons.size();
                synchronized (SEND_LOCK) {
                    for (int i = 0; i < jsons.size(); i++) {
                        final File f = jsons.get(i);
                        final int cur = i + 1;
                        final String label = labelOf(f);
                        if (cb != null) main.post(() -> cb.onProgress(cur, total, label));
                        if (trySendPending(app, f)) sent++; else remaining++;
                    }
                }
            } finally {
                releaseWakeLock(wl);
            }
            final int fs = sent, fr = remaining;
            if (cb != null) main.post(() -> cb.onComplete(fs, fr));
        }, "report-flush-progress").start();
    }

    /** Baca label "type" dari file pending (untuk teks progress). */
    private static String labelOf(File f) {
        try {
            return new JSONObject(readFile(f)).optString("type", "Laporan");
        } catch (Throwable t) {
            return "Laporan";
        }
    }

    /** Coba kirim 1 file pending (retry+backoff). Sukses → hapus file. */
    private static boolean trySendPending(Context app, File pending) {
        if (pending == null || !pending.exists()) return false;
        // Baca file: error baca = transien → biarkan, retry nanti (JANGAN hapus).
        String json;
        try {
            json = readFile(pending);
        } catch (Throwable ioErr) {
            return false;
        }
        // Parse: hanya hapus kalau benar-benar korup (JSONException).
        String subject, body;
        List<File> atts = new ArrayList<>();
        List<File> cleanup = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            subject = o.optString("subject");
            body = o.optString("body");
            JSONArray arr = o.optJSONArray("paths");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    File f = new File(arr.optString(i));
                    if (f.exists()) atts.add(f); // lampiran hilang → lewati, email tetap terkirim
                }
            }
            JSONArray cl = o.optJSONArray("cleanup");
            if (cl != null) {
                for (int i = 0; i < cl.length(); i++) cleanup.add(new File(cl.optString(i)));
            }
        } catch (org.json.JSONException corrupt) {
            pending.delete();
            return false;
        }
        SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(app));
        if (!s.isShiftEmailConfigured()) return false; // belum diatur → retry nanti
        if (attemptSend(s, subject, body, atts)) {
            pending.delete();
            // Hapus file milik laporan ini (mis. ZIP/PDF rekap) setelah terkirim.
            for (File f : cleanup) {
                try { if (f.exists()) f.delete(); } catch (Throwable ignored) {}
            }
            return true;
        }
        return false; // biarkan file → retry saat login berikutnya
    }

    /** Fallback kirim langsung tanpa file antrian (kalau gagal nulis antrian). */
    private static boolean sendDirect(Context app, String subject, String body,
                                      List<String> paths) {
        SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(app));
        if (!s.isShiftEmailConfigured()) return false;
        List<File> atts = new ArrayList<>();
        for (String p : paths) {
            File f = new File(p);
            if (f.exists()) atts.add(f);
        }
        return attemptSend(s, subject, body, atts);
    }

    /** Kirim dengan retry + backoff. true kalau salah satu percobaan sukses. */
    private static boolean attemptSend(SettingsDao s, String subject, String body,
                                       List<File> atts) {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                send(s.getSmtpHost(), s.getSmtpPort(), s.getSmtpUser(), s.getSmtpPass(),
                        s.getAdminEmail(), subject, body, atts);
                return true;
            } catch (Throwable e) {
                if (attempt < SEND_ATTEMPTS) {
                    try { Thread.sleep(attempt * 6000L); } catch (InterruptedException ignored) {}
                }
            }
        }
        return false;
    }

    private static File writePending(Context app, String type, String subject, String body,
                                     List<String> paths, List<String> cleanup) {
        try {
            File dir = new File(app.getFilesDir(), PENDING_DIR);
            if (!dir.exists()) dir.mkdirs();
            JSONObject o = new JSONObject();
            o.put("type", type != null ? type : "Laporan");
            o.put("subject", subject != null ? subject : "");
            o.put("body", body != null ? body : "");
            JSONArray arr = new JSONArray();
            if (paths != null) {
                for (String p : paths) if (p != null) arr.put(p);
            }
            o.put("paths", arr);
            JSONArray cl = new JSONArray();
            if (cleanup != null) {
                for (String p : cleanup) if (p != null) cl.put(p);
            }
            o.put("cleanup", cl);
            o.put("createdAt", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date()));
            // millis + nanoTime → unik walau 2 laporan ter-enqueue di ms yang sama.
            File out = new File(dir, "report_" + System.currentTimeMillis()
                    + "_" + (System.nanoTime() & 0xFFFFFFL) + ".json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(o.toString().getBytes("UTF-8"));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFile(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int read = 0, n;
            while (read < buf.length && (n = in.read(buf, read, buf.length - read)) > 0) read += n;
        }
        return new String(buf, "UTF-8");
    }

    private static PowerManager.WakeLock acquireWakeLock(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return null;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "DAMIU::ShiftReport");
            wl.setReferenceCounted(false);
            wl.acquire(5 * 60 * 1000L); // safety cap 5 menit (> worst-case 3x retry)
            return wl;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void releaseWakeLock(PowerManager.WakeLock wl) {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
    }
}
