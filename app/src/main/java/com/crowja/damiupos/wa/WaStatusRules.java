package com.crowja.damiupos.wa;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Aturan tampilan form "Status WhatsApp" di HP — cermin skrip form web (resources/views/wa/status_form.blade.php):
 * saran centang "menutup order" dari kata kunci, label tombol simpan, dan pesan konfirmasi sebelum menyimpan.
 * Server ({@code /api/admin/wa/status}) tetap penentu aturan sebenarnya. Java murni supaya bisa diuji unit.
 */
public final class WaStatusRules {

    private WaStatusRules() {}

    /** Kata kunci pengumuman tutup — hanya MENYARANKAN centang, tak pernah menutup order sendiri. */
    private static final Pattern CLOSE_RE =
            Pattern.compile("\\b(tutup|libur|close order|sudah penuh)\\b", Pattern.CASE_INSENSITIVE);

    /** Batas teks Status (unit UTF-16, emoji = 2) — sama dengan server & Bridge. */
    public static final int MAX_TEXT = 700;

    public static boolean suggestsClose(@Nullable String text) {
        return text != null && CLOSE_RE.matcher(text).find();
    }

    /** Panjang dalam unit UTF-16 (cara Bridge menghitung): {@link String#length()}. */
    public static int units(@Nullable String s) {
        return s == null ? 0 : s.length();
    }

    /** Label tombol simpan sesuai pilihan jadwal. */
    public static String saveLabel(String when, boolean processed) {
        if (processed) {
            switch (when) {
                case "now": return "Posting ulang sekarang";
                case "schedule": return "Jadwalkan ulang";
                case "draft": return "Jadikan draf";
                default: return "Simpan perubahan";
            }
        }
        switch (when) {
            case "schedule": return "Jadwalkan";
            case "draft": return "Simpan draf";
            default: return "Posting sekarang";
        }
    }

    /**
     * Pesan konfirmasi sebelum menyimpan (null = langsung simpan). Sama dengan form web; tambahan HP: "Posting
     * sekarang" Status baru ke banyak penonton selalu dikonfirmasi (dua ketukan).
     */
    @Nullable
    public static String submitConfirm(String when, boolean processed, boolean wasLive, boolean closesChecked,
                                       boolean closesSaved, boolean postedToday, boolean canCloseToday,
                                       boolean savedRepeatEmpty, int accountCount) {
        List<String> msgs = new ArrayList<>();
        if ("keep".equals(when)) {
            // Centang BARU pada Status yang tayang hari ini → order hari ini langsung ditutup saat disimpan.
            if (closesChecked && !closesSaved && postedToday && canCloseToday && savedRepeatEmpty) {
                msgs.add("Status ini sudah tayang hari ini — order hari ini langsung DITUTUP begitu disimpan; order baru dijadwalkan untuk besok.");
            }
        } else if ("draft".equals(when)) {
            if (processed && wasLive) {
                msgs.add("Jadikan draf menghapus riwayat tayang (akun ✓, jumlah penonton) dari daftar. Status lama tetap tampil di WhatsApp sampai kedaluwarsa.");
            }
        } else {
            if (processed) {
                msgs.add(wasLive
                        ? "Ini memasang Status BARU ke akun terpilih dan menggantikan riwayat tayang di daftar. Status lama tetap tampil di WhatsApp sampai kedaluwarsa (24 jam sejak tayang)."
                        : "Ini memposting Status ke akun terpilih.");
            }
            if (closesChecked) {
                msgs.add("Postingan ini akan MENUTUP order " + ("now".equals(when) ? "hari ini" : "pada hari jadwalnya")
                        + " begitu terposting — order baru dijadwalkan untuk besok.");
            }
            if (msgs.isEmpty() && "now".equals(when)) {
                msgs.add("Status langsung tayang di " + accountCount + " akun WhatsApp.");
            }
        }
        if (msgs.isEmpty()) return null;
        if (msgs.size() == 1) return msgs.get(0) + " Lanjutkan?";
        StringBuilder sb = new StringBuilder();
        for (String m : msgs) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m);
        }
        return sb.append("\n\nLanjutkan?").toString();
    }

    /** Konfirmasi tombol "Posting sekarang" di daftar (draf / terjadwal). */
    public static String postNowConfirm(boolean willClose, boolean closesOrders, int accountCount) {
        if (willClose) return "Postingan ini akan MENUTUP order hari ini begitu terposting. Posting sekarang?";
        if (closesOrders) return "Posting sekarang? Postingan ini TIDAK menutup order hari ini secara otomatis — tutup manual di halaman Status Order bila perlu.";
        return "Posting Status ini sekarang ke " + accountCount + " akun WhatsApp?";
    }

    /** Label tombol aktif/nonaktif Status terjadwal (sama dengan web). */
    public static String pauseLabel(boolean paused) {
        return paused ? "▶ Aktifkan" : "⏸ Nonaktifkan";
    }

    /** Konfirmasi aktif/nonaktif — jadwal & pengulangan tetap tersimpan. */
    public static String pauseConfirm(boolean paused, @Nullable String name) {
        String n = name == null || name.isEmpty() ? "Status ini" : "\"" + name + "\"";
        return paused
                ? "Aktifkan lagi " + n + "? Jadwal yang terlewat selama nonaktif digeser ke putaran berikutnya."
                : "Nonaktifkan " + n + "? Status tidak diposting sampai diaktifkan lagi; jadwal & pengulangan tetap tersimpan.";
    }

    /** Baris keterangan penampil Status tayang: waktu tayang · hilang jam · nama postingan / "dari HP" · penonton. */
    public static String liveMeta(@Nullable String posted, @Nullable String expires, @Nullable String postName,
                                  @Nullable String source, int audience) {
        List<String> parts = new ArrayList<>();
        if (posted != null && !posted.isEmpty()) parts.add(posted);
        if (expires != null && !expires.isEmpty()) parts.add(expires);
        if (postName != null && !postName.isEmpty()) parts.add("“" + postName + "”");
        else if ("phone".equals(source)) parts.add("diposting dari HP");
        if (audience > 0) parts.add("dikirim ke " + audience + " kontak");
        return String.join(" · ", parts);
    }

    /** Tombol penonton di penampil reels: "👁 12 penonton" (null = Bridge belum mencatat → "👁 Penonton"). */
    public static String viewsButton(@Nullable Integer views) {
        return views == null ? "👁 Penonton" : "👁 " + views + " penonton";
    }

    /** Judul daftar penonton. */
    public static String viewersTitle(int count, int customers) {
        return "👁 " + count + " penonton · " + customers + " pelanggan";
    }

    /** Satu baris daftar penonton: "Bu Sari ✓ pelanggan\n0812… · 18 Nov 09:01". */
    public static String viewerLine(String name, @Nullable String phone, @Nullable String at, boolean customer,
                                    @Nullable String waName) {
        StringBuilder b = new StringBuilder(name);
        if (customer) b.append("  ✓ pelanggan");
        List<String> sub = new ArrayList<>();
        if (phone != null && !phone.isEmpty()) sub.add(phone);
        if (customer && waName != null && !waName.isEmpty() && !waName.equals(name)) sub.add("WA: " + waName);
        if (at != null && !at.isEmpty()) sub.add(at);
        if (!sub.isEmpty()) b.append("\n").append(String.join(" · ", sub));
        return b.toString();
    }

    /** Konfirmasi tarik Status dari WhatsApp. */
    public static String liveDeleteConfirm(String account) {
        return "Status ini dihapus dari WhatsApp " + account + " — penonton tidak bisa melihatnya lagi. Tidak bisa dibatalkan.";
    }
}
