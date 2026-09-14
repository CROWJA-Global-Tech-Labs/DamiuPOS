package com.crowja.damiupos.wa;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.crowja.damiupos.R;
import com.crowja.damiupos.sync.OnlineNotifier;

/**
 * Gateway WhatsApp (Opsi A): kirim pesan teks ke satu nomor atas perintah dashboard
 * (DeviceCommand {@code cmd="wa_send"}, payload {@code {phone, text}} — dijalankan oleh
 * {@link com.crowja.damiupos.sync.OnlineTasks}). Membuka chat WhatsApp lewat deep-link
 * {@code wa.me} dengan teks sudah terisi, lalu {@link WaAutoSendService} menekan "Kirim".
 *
 * <p><b>Hasilnya dilaporkan, bukan diasumsikan.</b> {@link #send} MENUNGGU sampai tombol Kirim
 * benar-benar ditekan (atau permintaan kedaluwarsa) lalu mengembalikan status yang dikirim balik ke
 * dashboard. Ini penting karena {@code startActivity} TIDAK melempar exception saat Android menahan
 * peluncuran dari background — tanpa menunggu bukti klik, kegagalan tampak seperti sukses.</p>
 *
 * <p><b>Keterbatasan Opsi A (disengaja):</b> AccessibilityService hanya bisa berinteraksi dengan
 * jendela yang sedang di depan, jadi WhatsApp WAJIB dibawa ke foreground sesaat — layar HP akan
 * berpindah ke WhatsApp saat mengirim. Bila layanan Aksesibilitas tidak aktif, pengiriman otomatis
 * MUSTAHIL; kita tidak berpura-pura berhasil melainkan memasang notifikasi sekali-ketuk yang membuka
 * chat dengan pesan sudah terisi.</p>
 */
public final class WaGateway {

    private WaGateway() {}

    /** ID notifikasi hasil/audit (tetap sama agar tidak menumpuk banyak notif). */
    public static final int NOTIF_ID = 7876;
    /** ID notifikasi cadangan "ketuk untuk kirim" (berbeda agar tak menimpa notif hasil). */
    private static final int NOTIF_TAP_ID = 7877;

    /** Berapa lama gateway rela antre bila alur lain (bagikan struk) sedang memakai auto-kirim.
     *  Sengaja pendek: pemanggilnya adalah poller yang juga mengurus heartbeat & GPS, jadi lebih
     *  baik jatuh ke notifikasi sekali-ketuk daripada menahan poller lama-lama. */
    private static final long WAIT_SLOT_MS = 8_000L;

    // Status yang dilaporkan balik ke dashboard.
    public static final String SENT = "sent";
    public static final String MANUAL = "manual";
    public static final String NO_WHATSAPP = "no_whatsapp";
    public static final String INVALID = "invalid";
    public static final String LAUNCH_FAILED = "launch_failed";

    // --- Status khusus alur CEK nomor ({@link #check}) -----------------------------------------
    /** Nomor tujuan TERBUKTI punya WhatsApp (chat sungguhan terbuka). */
    public static final String HAS_WHATSAPP = "has_whatsapp";
    /** WhatsApp tak terpasang di HP PENGECEK — bukan vonis atas nomor tujuan. Sengaja BUKAN
     *  {@link #NO_WHATSAPP}: itu satu-satunya status yang menandai pelanggan bermasalah, jadi
     *  kegagalan di sisi kita tak boleh memakainya. */
    public static final String CHECKER_WA_MISSING = "wa_app_missing";
    /** Aksesibilitas mati / slot dipakai proses lain → cek tak bisa dijalankan (tak konklusif). */
    public static final String CHECK_UNAVAILABLE = "check_unavailable";

    /** Normalisasi nomor Indonesia → {@code 62xxxxxxxx} (buang non-digit, 0→62, prefiks 62 bila perlu). */
    public static String normalize(String phone) {
        if (phone == null) return "";
        String n = phone.replaceAll("[^0-9]", "");
        if (n.isEmpty()) return "";
        if (n.startsWith("0")) n = "62" + n.substring(1);
        else if (!n.startsWith("62")) n = "62" + n;
        return n;
    }

    /** Paket WhatsApp terpasang (utamakan WA konsumen), atau null bila tak ada. */
    private static String installedWaPackage(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try { pm.getPackageInfo(p, 0); return p; } catch (Exception ignored) {}
        }
        return null;
    }

    /** Deep-link chat WA dengan pesan sudah terisi. */
    private static Intent chatIntent(String waPackage, String phone, String text) {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/" + phone + "?text=" + Uri.encode(text)));
        i.setPackage(waPackage);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /**
     * Kirim pesan WA ke satu nomor dan kembalikan status akhirnya. Aman (dan WAJIB) dipanggil dari
     * thread background: memblokir sampai tombol Kirim ditekan atau permintaan kedaluwarsa.
     *
     * @return salah satu {@link #SENT}, {@link #MANUAL}, {@link #NO_WHATSAPP}, {@link #INVALID},
     *         {@link #LAUNCH_FAILED}, atau hasil mentah dari {@link WaAutoSendService.Req#await()}
     *         ({@code expired} / {@code preempted} / {@code cancelled} / {@code service_stopped}).
     */
    public static String send(Context ctx, String phoneRaw, String text) {
        String phone = normalize(phoneRaw);
        if (phone.length() < 8 || text == null || text.trim().isEmpty()) {
            notif(ctx, "Kirim WhatsApp gagal", "Nomor atau pesan tidak valid.", NOTIF_ID);
            return INVALID;
        }
        String wa = installedWaPackage(ctx);
        if (wa == null) {
            notif(ctx, "Kirim WhatsApp gagal",
                    "WhatsApp tidak terpasang di perangkat ini.", NOTIF_ID);
            return NO_WHATSAPP;
        }

        Intent chat = chatIntent(wa, phone, text);

        // Auto-kirim hanya mungkin bila layanan Aksesibilitas HIDUP: ia yang menekan Kirim, dan
        // konteksnya pula yang boleh membuka Activity dari background (lihat serviceContext()).
        Context launcher = WaAutoSendService.serviceContext();
        if (launcher == null || !WaAutoSendService.isEnabled(ctx)) {
            postTapToSend(ctx, chat, phone, launcher == null
                    ? "Aksesibilitas \"Auto-kirim\" belum aktif di HP ini."
                    : "Auto-kirim sedang dimatikan di Pengaturan aplikasi.");
            return MANUAL;
        }

        WaAutoSendService.Req req = WaAutoSendService.acquire(phone, text, WAIT_SLOT_MS);
        if (req == null) {
            postTapToSend(ctx, chat, phone, "Auto-kirim sedang dipakai proses lain.");
            return MANUAL;
        }

        try {
            launcher.startActivity(chat);
            // Sejak titik ini chat tujuan terbuka & terisi pesan kita → alur struk tak boleh
            // menimpa permintaan ini dengan mode "chat mana pun" (bisa mengirim ke chat ini).
            WaAutoSendService.markLaunched(req);
        } catch (Exception e) {
            WaAutoSendService.cancel(req);
            notif(ctx, "Kirim WhatsApp gagal",
                    "Tidak dapat membuka WhatsApp: " + e.getMessage(), NOTIF_ID);
            return LAUNCH_FAILED;
        }

        // Tunggu bukti nyata bahwa Kirim ditekan (bukan sekadar "intent sudah dilempar").
        String outcome = req.await();
        if (SENT.equals(outcome)) {
            notif(ctx, "Gateway WhatsApp", "Pesan terkirim ke " + phone + ".", NOTIF_ID);
        } else {
            // Gagal terverifikasi → beri jalan sekali-ketuk, jangan mengaku sukses.
            postTapToSend(ctx, chat, phone, "Pengiriman otomatis tidak terkonfirmasi (" + outcome + ").");
        }
        return outcome;
    }

    /**
     * Apakah {@code phoneRaw} terdaftar di WhatsApp? Membuka chat lewat deep-link lalu membaca
     * layarnya — TIDAK mengirim apa pun (lihat {@link WaAutoSendService.Req#checkOnly}). WAJIB dari
     * thread background: memblokir sampai simpulan didapat atau kedaluwarsa.
     *
     * <p>Hanya {@link #NO_WHATSAPP} yang berarti "nomor ini memang tak punya WhatsApp" — dan itulah
     * satu-satunya status yang boleh menandai pelanggan bermasalah. Semua hasil lain
     * ({@link #CHECK_UNAVAILABLE}, {@link #CHECKER_WA_MISSING}, {@code expired}, …) berarti TAK
     * KONKLUSIF: kegagalan di sisi kita tak boleh menuduh nomor pelanggan mati.</p>
     */
    public static String check(Context ctx, String phoneRaw) {
        String phone = normalize(phoneRaw);
        if (phone.length() < 8) return INVALID;

        String wa = installedWaPackage(ctx);
        if (wa == null) return CHECKER_WA_MISSING;

        // Auto-cek butuh layanan Aksesibilitas hidup: ia yang membaca layar WhatsApp, dan
        // konteksnya pula yang boleh membuka Activity dari background (lihat serviceContext()).
        Context launcher = WaAutoSendService.serviceContext();
        if (launcher == null || !WaAutoSendService.isEnabled(ctx)) return CHECK_UNAVAILABLE;

        WaAutoSendService.Req req = WaAutoSendService.acquire(phone, null, WAIT_SLOT_MS, true);
        if (req == null) return CHECK_UNAVAILABLE;

        // Tanpa "?text=": pesan terisi hanya berguna untuk mengirim, sedangkan cek tak pernah
        // mengirim — kotak ketik dibiarkan kosong supaya tak ada yang bisa salah terkirim.
        Intent chat = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone));
        chat.setPackage(wa);
        chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            launcher.startActivity(chat);
            WaAutoSendService.markLaunched(req);
        } catch (Exception e) {
            WaAutoSendService.cancel(req);
            return LAUNCH_FAILED;
        }
        return req.await();
    }

    /**
     * Notifikasi cadangan: ketuk untuk membuka chat WA dengan pesan sudah terisi (staf tinggal
     * menekan Kirim). Mengetuk notifikasi memberi konteks foreground, jadi peluncuran ini selalu
     * diizinkan Android — berbeda dengan peluncuran dari poller background.
     */
    private static void postTapToSend(Context ctx, Intent chat, String phone, String why) {
        OnlineNotifier.ensureChannel(ctx);   // Android O+: channel wajib ada dulu
        // Satu notifikasi per NOMOR tujuan: id tetap akan membuat pesan gagal kedua menimpa yang
        // pertama, menghapus satu-satunya jalan pemulihan pesan itu (dan PendingIntent-nya ikut
        // ditimpa karena request code sama → ketukan membuka chat yang salah).
        int slot = Math.abs(phone.hashCode() % 500);
        int id = NOTIF_TAP_ID + slot;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, chat, flags);
        String body = "Ketuk untuk membuka chat " + phone + " (pesan sudah terisi) lalu tekan Kirim. "
                + why;
        try {
            Notification n = new NotificationCompat.Builder(ctx, OnlineNotifier.CHANNEL_PUSH)
                    .setSmallIcon(R.drawable.ic_info)
                    .setContentTitle("Kirim WhatsApp ke " + phone)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            NotificationManagerCompat.from(ctx).notify(id, n);
        } catch (Throwable ignored) {
            notif(ctx, "Kirim WhatsApp ke " + phone, body, NOTIF_ID);
        }
    }

    private static void notif(Context ctx, String title, String body, int id) {
        // Pastikan channel ada (postNotif yang membuatnya) — sekaligus jalur notifikasi standar.
        OnlineNotifier.postNotif(ctx, title, body, id);
    }
}
