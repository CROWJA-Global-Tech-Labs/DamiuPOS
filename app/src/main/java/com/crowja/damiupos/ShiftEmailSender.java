package com.crowja.damiupos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
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
        props.put("mail.smtp.connectiontimeout", "20000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");

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
}
