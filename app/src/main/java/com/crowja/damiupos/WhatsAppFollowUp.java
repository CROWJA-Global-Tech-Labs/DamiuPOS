package com.crowja.damiupos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Membuka chat WhatsApp berisi pesan follow-up (template configurable) untuk
 * satu pelanggan, lalu menandai pelanggan sudah di-follow-up hari ini.
 *
 * <p>Logika tunggal yang dipakai baik dari daftar Follow Up
 * ({@link FollowUpActivity}) maupun dari pin di Peta Follow Up
 * ({@link FollowUpMapActivity}) supaya pesan & penandaan selalu konsisten.
 */
public final class WhatsAppFollowUp {

    private WhatsAppFollowUp() {}

    private static final SimpleDateFormat SDF_PARSE_FULL =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_PARSE_DATE =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_OUT_DATE =
            new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));
    private static final SimpleDateFormat SDF_OUT_DAY =
            new SimpleDateFormat("EEEE", new Locale("id", "ID"));

    /**
     * Buka WhatsApp ke nomor pelanggan dengan pesan dari template follow-up
     * (placeholder {nama} {hari} {tanggal} {hari_lalu}). Setelah dibuka,
     * pelanggan ditandai sudah di-follow-up hari ini (untuk laporan harian).
     */
    public static void open(Context ctx, Customer c,
                            SettingsDao settingsDao, CustomerDao customerDao) {
        // PANTUN adalah isi follow-up BAWAAN (bukan menu terpisah): sejak korpus ditulis ulang,
        // pantunnya sendiri yang menanyakan "mau dikirim lagi hari ini/besok?". Kalau paket pantun
        // belum terunduh atau Nama Merek belum diatur, open() otomatis kembali ke template biasa —
        // jadi menyalakannya di sini tak pernah bisa menggagalkan pengiriman.
        open(ctx, c, settingsDao, customerDao, true, true);
    }

    /**
     * @param markFollowedUp false = jangan stempel last_followup_at (syncUpdate men-dirty seluruh
     *        baris → push LWW bisa menimpa edit lebih baru milik perangkat lain). Dipakai Daftar
     *        Kunjungan untuk salinan pelanggan yang BUKAN milik perangkat ini.
     */
    public static void open(Context ctx, Customer c, SettingsDao settingsDao,
                            CustomerDao customerDao, boolean markFollowedUp) {
        open(ctx, c, settingsDao, customerDao, markFollowedUp, true);
    }

    /**
     * @param withPantun true = pakai PANTUN sebagai isi pesan. Pantunnya diambil BERURUTAN dari
     *        paket lokal (lihat {@link PantunPicker}) — tiap kiriman maju satu langkah dan
     *        melingkar, jadi seluruh isi paket kebagian. Bila paket belum terunduh ATAU merek belum
     *        diatur, pesan otomatis kembali ke template biasa — fitur ini tak pernah menggagalkan
     *        pengiriman.
     */
    public static void open(Context ctx, Customer c, SettingsDao settingsDao,
                            CustomerDao customerDao, boolean markFollowedUp, boolean withPantun) {
        String phone = c != null ? c.getPhone() : null;
        if (phone == null || phone.isEmpty()) {
            Toast.makeText(ctx, "Pelanggan belum memiliki nomor WhatsApp",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String normalized = phone.replaceAll("[^0-9]", "");
        if (normalized.startsWith("0")) normalized = "62" + normalized.substring(1);
        else if (!normalized.startsWith("62")) normalized = "62" + normalized;

        String lastTs = c.getCreatedAt();
        String hari = formatDayName(lastTs);
        String tanggal = formatDate(lastTs);
        long days = daysSince(lastTs);

        // Template configurable dari Pengaturan. Placeholder:
        //   {nama} {hari} {tanggal} {hari_lalu} {hari_kirim}
        String template = settingsDao.getFollowUpTemplate();
        String msg = template
                .replace("{nama}", c.getName() != null ? c.getName() : "")
                .replace("{hari}", hari)
                .replace("{tanggal}", tanggal)
                .replace("{hari_lalu}", String.valueOf(days));
        // Kata hari pengiriman: "hari ini" selama masih di jam operasi cabang, "besok" begitu
        // lewat jam tutup (ops_close_time, Konfigurasi web — tersinkron). Bila "besok", frasa
        // literal "hari ini" pada template lama ikut diganti ({hari_lalu} sudah jadi angka, aman).
        // CERMIN logika web (FollowUpWa::deliveryDayWord) — jaga selaras.
        String word = deliveryDayWord(settingsDao.getOpsCloseTime());
        msg = msg.replace("{hari_kirim}", word);
        if ("besok".equals(word)) msg = msg.replace("hari ini", "besok");

        // PANTUN: bila tersedia, ia MENGGANTIKAN badan pesan template di atas (lihat alasannya di
        // dalam blok). Cermin FollowUpWa::deeplink di web.
        if (withPantun) {
            // Pantun BERIKUTNYA dalam putaran — takeNext() sekaligus memajukan kursor, jadi ia
            // dipanggil TEPAT SEKALI di sini, pada jalur yang benar-benar mengirim pesan.
            String pantun = PantunPicker.takeNext(settingsDao, word);
            if (pantun != null && !pantun.trim().isEmpty()) {
                // Pantun MENGGANTI badan pesan, bukan ditempel di bawahnya: isi pantun sendiri sudah
                // menanyakan "mau dikirim lagi?", jadi mengirim template lengkap sekalian berarti
                // bertanya dua kali. Bentuk akhir: pembuka (salam) / baris kosong / pantun / baris
                // kosong / penutup (pertanyaan penegas) — dua-duanya bisa diubah owner di Konfigurasi
                // dan tersinkron ke sini. CERMIN FollowUpWa::deeplink di web — jaga selaras.
                String nama = c.getName() != null ? c.getName() : "";
                String opener = settingsDao.getFollowUpOpener()
                        .replace("{nama}", nama).replace("{hari_kirim}", word).trim();
                String closer = settingsDao.getFollowUpCloser()
                        .replace("{nama}", nama).replace("{hari_kirim}", word).trim();
                StringBuilder sb = new StringBuilder();
                if (!opener.isEmpty()) sb.append(opener).append("\n\n");
                sb.append(pantun.trim());
                if (!closer.isEmpty()) sb.append("\n\n").append(closer);
                msg = sb.toString();
            }
        }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + normalized + "?text=" + Uri.encode(msg)));
            try {
                ctx.getPackageManager().getPackageInfo("com.whatsapp", 0);
                i.setPackage("com.whatsapp");
            } catch (Exception ignored) {
                try {
                    ctx.getPackageManager().getPackageInfo("com.whatsapp.w4b", 0);
                    i.setPackage("com.whatsapp.w4b");
                } catch (Exception ignored2) {}
            }
            if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            // Catat: pelanggan ini di-follow-up hari ini (untuk laporan harian).
            if (markFollowedUp) customerDao.markFollowedUp(c.getId());
        } catch (Exception e) {
            Toast.makeText(ctx, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }

    /** "hari ini" bila sekarang masih sebelum jam tutup operasional (HH:mm), else "besok".
     *  Jam tak terparse → "hari ini" (perilaku lama). Dini hari (sebelum buka) tetap "hari ini"
     *  karena pengiriman masih mungkin terjadi hari itu. */
    static String deliveryDayWord(String closeTime) {
        try {
            String[] p = closeTime.trim().split(":");
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            java.util.Calendar now = java.util.Calendar.getInstance();
            int minutesNow = now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                    + now.get(java.util.Calendar.MINUTE);
            return minutesNow >= h * 60 + m ? "besok" : "hari ini";
        } catch (Exception e) {
            return "hari ini";
        }
    }

    private static long daysSince(String ts) {
        if (ts == null || ts.isEmpty()) return 0;
        try {
            Date d = SDF_PARSE_FULL.parse(ts);
            if (d == null) return 0;
            long diff = System.currentTimeMillis() - d.getTime();
            return Math.max(0, diff / (1000L * 60 * 60 * 24));
        } catch (Exception e) {
            try {
                Date d2 = SDF_PARSE_DATE.parse(ts.substring(0, Math.min(10, ts.length())));
                if (d2 == null) return 0;
                long diff = System.currentTimeMillis() - d2.getTime();
                return Math.max(0, diff / (1000L * 60 * 60 * 24));
            } catch (Exception ignored) { return 0; }
        }
    }

    private static String formatDate(String ts) {
        if (ts == null || ts.isEmpty()) return "-";
        try {
            Date d = SDF_PARSE_FULL.parse(ts);
            return d != null ? SDF_OUT_DATE.format(d) : ts;
        } catch (Exception e) {
            return ts.length() >= 10 ? ts.substring(0, 10) : ts;
        }
    }

    /** Nama hari dalam Bahasa Indonesia (Senin, Selasa, dst.) */
    private static String formatDayName(String ts) {
        if (ts == null || ts.isEmpty()) return "-";
        try {
            Date d = SDF_PARSE_FULL.parse(ts);
            return d != null ? SDF_OUT_DAY.format(d) : "-";
        } catch (Exception e) {
            try {
                Date d2 = SDF_PARSE_DATE.parse(ts.substring(0, Math.min(10, ts.length())));
                return d2 != null ? SDF_OUT_DAY.format(d2) : "-";
            } catch (Exception ignored) { return "-"; }
        }
    }
}
