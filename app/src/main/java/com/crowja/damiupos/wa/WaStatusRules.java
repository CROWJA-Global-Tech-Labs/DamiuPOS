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
}
